/// Model tiers (`docs/MODEL_TIERS.md`): products name a tier — low, medium,
/// high — never a model, and resolve it to the provider's newest model at run
/// time, with the person's own key.
///
/// [ModelResolver.resolve] does exactly what the doc's "Resolving a tier"
/// says: list the provider's models, keep the tier's pattern minus its
/// exclusions, pick the newest; cache 24 hours; fall back to the cache and
/// then the pinned ID; and [ModelResolver.run] heals once when a request says
/// the model is gone.
library;

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import 'model_tiers_table.dart';

enum ModelTier {
  low,
  medium,
  high;

  /// The tier for a stored name; the default (`low`) for anything else.
  static ModelTier parse(String? name) => switch (name) {
        'medium' => ModelTier.medium,
        'high' => ModelTier.high,
        _ => ModelTier.low,
      };

  /// The words the person sees (MODEL_TIERS.md, "The choice the person sees").
  String label(String language) => switch ((this, language)) {
        (ModelTier.low, 'ar') => 'سريع',
        (ModelTier.medium, 'ar') => 'متوازن',
        (ModelTier.high, 'ar') => 'الأدق',
        (ModelTier.low, _) => 'Fast',
        (ModelTier.medium, _) => 'Balanced',
        (ModelTier.high, _) => 'Best',
      };

  /// Claude's `output_config.effort` for this tier.
  String get effort => name;
}

/// The providers a tier resolves on. Names as in `model-tiers.json`.
enum ModelVendor { anthropic, openai, google }

class TierSpec {
  TierSpec(
      {required this.pattern,
      this.exclude,
      required this.pinned,
      this.alias,
      required this.verified});
  final RegExp pattern;
  final RegExp? exclude;
  final String pinned;

  /// Google's always-newest alias (`gemini-flash-latest`), preferred when the
  /// list has it.
  final String? alias;
  final bool verified;

  bool matches(String id) =>
      pattern.hasMatch(id) && !(exclude?.hasMatch(id) ?? false);
}

/// `catalog/model-tiers.json`.
class TierTable {
  TierTable._(this.version, this.checked, this._specs);

  final int version;
  final String checked;
  final Map<(ModelVendor, ModelTier), TierSpec> _specs;

  TierSpec spec(ModelVendor vendor, ModelTier tier) => _specs[(vendor, tier)]!;

  /// The table this SDK was released with (`model_tiers_table.dart`, kept
  /// equal to `catalog/model-tiers.json` by a test).
  static final TierTable bundled = TierTable.parse(
      jsonDecode(bundledModelTiersJson) as Map<String, dynamic>);

  static TierTable parse(Map<String, dynamic> json) {
    final providers = json['providers'] as Map<String, dynamic>;
    final specs = <(ModelVendor, ModelTier), TierSpec>{};
    for (final vendor in ModelVendor.values) {
      final p = providers[vendor.name] as Map<String, dynamic>?;
      if (p == null) throw FormatException('model-tiers: no ${vendor.name}');
      final tiers = p['tiers'] as Map<String, dynamic>;
      for (final tier in ModelTier.values) {
        final t = tiers[tier.name] as Map<String, dynamic>?;
        if (t == null) {
          throw FormatException('model-tiers: no ${vendor.name}.${tier.name}');
        }
        specs[(vendor, tier)] = TierSpec(
          pattern: RegExp(t['pattern'] as String),
          exclude: t['exclude'] == null ? null : RegExp(t['exclude'] as String),
          pinned: t['pinned'] as String,
          alias: t['alias'] as String?,
          verified: t['verified'] == true,
        );
      }
    }
    return TierTable._((json['version'] as num).toInt(),
        json['checked'] as String? ?? '', specs);
  }
}

/// One model a provider lists.
class ListedModel {
  ListedModel(this.id, {this.created});
  final String id;

  /// When the provider says it was made (Anthropic `created_at`, OpenAI
  /// `created`); Google gives none.
  final DateTime? created;
}

/// Where a resolved ID came from.
enum ModelSource { exact, live, cache, pinned }

class ResolvedModel {
  ResolvedModel(this.id, this.source);
  final String id;
  final ModelSource source;
  @override
  String toString() => '$id (${source.name})';
}

/// Device-local storage for resolved IDs: never in backups or exports.
abstract class TierCache {
  Future<(String id, DateTime at)?> read(String key);
  Future<void> write(String key, String id, DateTime at);
  Future<void> drop(String key);
}

class MemoryTierCache implements TierCache {
  final _m = <String, (String, DateTime)>{};
  @override
  Future<(String, DateTime)?> read(String key) async => _m[key];
  @override
  Future<void> write(String key, String id, DateTime at) async =>
      _m[key] = (id, at);
  @override
  Future<void> drop(String key) async => _m.remove(key);
}

class ModelResolver {
  ModelResolver(
      {http.Client? client,
      TierCache? cache,
      TierTable? table,
      DateTime Function()? clock,
      this.timeout = const Duration(seconds: 5),
      this.ttl = const Duration(hours: 24)})
      : _http = client ?? http.Client(),
        cache = cache ?? MemoryTierCache(),
        table = table ?? TierTable.bundled,
        _clock = clock ?? DateTime.now;

  final http.Client _http;
  final TierCache cache;
  final TierTable table;
  final DateTime Function() _clock;
  final Duration timeout;
  final Duration ttl;

  static String cacheKey(ModelVendor vendor, ModelTier tier) =>
      'model-tier.${vendor.name}.${tier.name}';

  /// The model for [tier] on [vendor]. [exact], when the person pinned one,
  /// is used as it is and never changed.
  Future<ResolvedModel> resolve(ModelVendor vendor, ModelTier tier, String key,
      {String? exact}) async {
    if (exact != null && exact.trim().isNotEmpty) {
      return ResolvedModel(exact.trim(), ModelSource.exact);
    }
    final k = cacheKey(vendor, tier);
    final cached = await cache.read(k);
    if (cached != null && _clock().difference(cached.$2) < ttl) {
      return ResolvedModel(cached.$1, ModelSource.cache);
    }
    final live = await _live(vendor, tier, key);
    if (live != null) {
      await cache.write(k, live, _clock());
      return ResolvedModel(live, ModelSource.live);
    }
    // Offline, no permission to list, nothing matched: the last good answer,
    // however old, beats the table.
    if (cached != null) return ResolvedModel(cached.$1, ModelSource.cache);
    return ResolvedModel(table.spec(vendor, tier).pinned, ModelSource.pinned);
  }

  /// Runs [call] with the resolved model. If it fails because the model is
  /// gone ([isModelGone]), drops the cache, resolves again once, and retries
  /// once. Never loops.
  Future<T> run<T>(ModelVendor vendor, ModelTier tier, String key,
      Future<T> Function(String model) call,
      {String? exact, required bool Function(Object error) isModelGone}) async {
    final first = await resolve(vendor, tier, key, exact: exact);
    try {
      return await call(first.id);
    } catch (e) {
      if (!isModelGone(e) || first.source == ModelSource.exact) rethrow;
      await cache.drop(cacheKey(vendor, tier));
      final again = await resolve(vendor, tier, key);
      if (again.id == first.id) rethrow;
      return call(again.id);
    }
  }

  /// Whether an HTTP answer means "that model does not exist (any more)".
  static bool modelGone(int status, String body) {
    if (status != 404 && status != 400) return false;
    final b = body.toLowerCase();
    return b.contains('not_found') ||
        b.contains('model_not_found') ||
        b.contains('not found') ||
        b.contains('deprecated') ||
        (status == 404 && b.contains('model'));
  }

  Future<String?> _live(ModelVendor vendor, ModelTier tier, String key) async {
    try {
      final models = await list(vendor, key).timeout(timeout);
      return pickNewest(vendor, table.spec(vendor, tier), models);
    } catch (_) {
      return null;
    }
  }

  /// The newest of [models] for [spec] (MODEL_TIERS.md step 1), or null.
  static String? pickNewest(
      ModelVendor vendor, TierSpec spec, List<ListedModel> models) {
    final ids = models.map((m) => m.id).toSet();
    if (vendor == ModelVendor.google &&
        spec.alias != null &&
        ids.contains(spec.alias)) {
      return spec.alias;
    }
    final fit = [
      for (final m in models)
        if (spec.matches(m.id)) m
    ];
    if (fit.isEmpty) return null;
    if (vendor == ModelVendor.google) {
      fit.sort((a, b) => _compareVersions(_version(b.id), _version(a.id)));
      return fit.first.id;
    }
    fit.sort((a, b) {
      final ca = a.created, cb = b.created;
      if (ca == null && cb == null) return b.id.compareTo(a.id);
      if (ca == null) return 1;
      if (cb == null) return -1;
      final byDate = cb.compareTo(ca);
      return byDate != 0 ? byDate : b.id.compareTo(a.id);
    });
    return fit.first.id;
  }

  static List<int> _version(String id) => [
        for (final m in RegExp(r'\d+').allMatches(id)) int.parse(m.group(0)!),
      ];

  static int _compareVersions(List<int> a, List<int> b) {
    for (var i = 0; i < a.length && i < b.length; i++) {
      if (a[i] != b[i]) return a[i].compareTo(b[i]);
    }
    return a.length.compareTo(b.length);
  }

  /// Every model [vendor] lists for [key], all pages.
  Future<List<ListedModel>> list(ModelVendor vendor, String key) =>
      switch (vendor) {
        ModelVendor.anthropic => _anthropic(key),
        ModelVendor.openai => _openai(key),
        ModelVendor.google => _google(key),
      };

  Future<Map<String, dynamic>> _get(
      Uri url, Map<String, String> headers) async {
    final r = await _http.get(url, headers: headers);
    if (r.statusCode != 200) {
      throw http.ClientException(
          'listing models answered ${r.statusCode}', url);
    }
    return jsonDecode(utf8.decode(r.bodyBytes)) as Map<String, dynamic>;
  }

  Future<List<ListedModel>> _anthropic(String key) async {
    final out = <ListedModel>[];
    String? after;
    for (var page = 0; page < 20; page++) {
      final j = await _get(
          Uri.https('api.anthropic.com', '/v1/models',
              {'limit': '1000', if (after != null) 'after_id': after}),
          {'x-api-key': key, 'anthropic-version': '2023-06-01'});
      for (final m in (j['data'] as List? ?? const [])) {
        if (m is Map && m['id'] is String) {
          out.add(ListedModel(m['id'] as String,
              created: DateTime.tryParse('${m['created_at']}')));
        }
      }
      if (j['has_more'] != true) break;
      after = j['last_id'] as String?;
      if (after == null) break;
    }
    return out;
  }

  Future<List<ListedModel>> _openai(String key) async {
    final j = await _get(Uri.https('api.openai.com', '/v1/models'),
        {'authorization': 'Bearer $key'});
    return [
      for (final m in (j['data'] as List? ?? const []))
        if (m is Map && m['id'] is String)
          ListedModel(m['id'] as String,
              created: m['created'] is num
                  ? DateTime.fromMillisecondsSinceEpoch(
                      (m['created'] as num).toInt() * 1000,
                      isUtc: true)
                  : null),
    ];
  }

  Future<List<ListedModel>> _google(String key) async {
    final out = <ListedModel>[];
    String? token;
    for (var page = 0; page < 20; page++) {
      // The key goes in a header, never the URL (MODEL_TIERS.md).
      final j = await _get(
          Uri.https('generativelanguage.googleapis.com', '/v1beta/models',
              {'pageSize': '1000', if (token != null) 'pageToken': token}),
          {'x-goog-api-key': key});
      for (final m in (j['models'] as List? ?? const [])) {
        if (m is Map && m['name'] is String) {
          final methods = m['supportedGenerationMethods'];
          if (methods is List && !methods.contains('generateContent')) continue;
          out.add(
              ListedModel((m['name'] as String).replaceFirst('models/', '')));
        }
      }
      token = j['nextPageToken'] as String?;
      if (token == null || token.isEmpty) break;
    }
    return out;
  }

  void close() => _http.close();
}

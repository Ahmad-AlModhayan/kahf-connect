/// A connector's manifest (SPEC §3): who it is, where it runs, how it
/// authenticates and what it supplies. Parsed strictly: anything the schema
/// forbids is a [ManifestError] naming the rule, so every SDK can agree on
/// the same verdicts over `fixtures/manifests`.
library;

import 'uri_check.dart';

/// Text in several languages; `en` is always there.
class Localised {
  Localised(Map<String, String> values) : values = Map.unmodifiable(values);

  final Map<String, String> values;

  /// In [language] when given, else English.
  String of(String language) => values[language] ?? values['en']!;

  Map<String, String> toJson() => values;

  static final _lang = RegExp(r'^[a-z]{2}(-[A-Z]{2})?$');

  static Localised parse(Object? raw, String field) {
    if (raw is! Map) throw ManifestError('localised', '$field is not a map');
    final out = <String, String>{};
    for (final e in raw.entries) {
      final k = e.key, v = e.value;
      if (k is! String || !_lang.hasMatch(k)) {
        throw ManifestError('localised', '$field has a bad language "$k"');
      }
      if (v is! String || v.isEmpty) {
        throw ManifestError('localised', '$field.$k is empty');
      }
      out[k] = v;
    }
    if (!out.containsKey('en')) {
      throw ManifestError('localised', '$field has no "en"');
    }
    return Localised(out);
  }
}

enum HostKind { remote, builtin, device }

enum AuthKind { none, bearer, oauth2 }

enum SettingKind { text, url, secret, secretUrl }

/// The capabilities this version of the SDK knows (SPEC §4).
abstract final class Capabilities {
  static const timeBusy = 'time.busy';
  static const contextRead = 'context.read';
  static const actionsPropose = 'actions.propose';

  static const known = {timeBusy, contextRead, actionsPropose};

  /// The MCP tool a capability is served as: `time.busy@1` →
  /// `kahf_time_busy_v1`.
  static String tool(String id, int version) =>
      'kahf_${id.replaceAll('.', '_')}_v$version';
}

class CapabilityDecl {
  CapabilityDecl(this.id, this.version, this.reason);
  final String id;
  final int version;
  final Localised reason;

  String get tool => Capabilities.tool(id, version);
  String get key => '$id@$version';

  Map<String, Object?> toJson() =>
      {'id': id, 'version': version, 'reason': reason.toJson()};
}

class SettingDecl {
  SettingDecl(this.key, this.kind, this.label,
      {this.help, this.required = false});
  final String key;
  final SettingKind kind;
  final Localised label;
  final Localised? help;
  final bool required;

  /// Kept in secure storage, never logged or synced (SPEC §3).
  bool get secret =>
      kind == SettingKind.secret || kind == SettingKind.secretUrl;

  Map<String, Object?> toJson() => {
        'key': key,
        'kind': switch (kind) {
          SettingKind.secretUrl => 'secret-url',
          _ => kind.name,
        },
        'label': label.toJson(),
        if (help != null) 'help': help!.toJson(),
        if (required) 'required': true,
      };
}

class Manifest {
  Manifest._({
    required this.id,
    required this.name,
    required this.description,
    required this.version,
    required this.authorName,
    this.authorUrl,
    this.homepage,
    this.privacyUrl,
    required this.hostKind,
    this.hostUrl,
    this.hostPackage,
    required this.authKind,
    this.authSetting,
    this.authorizeUrl,
    this.tokenUrl,
    this.scopes = const [],
    required this.capabilities,
    this.settings = const [],
    required Map<String, Object?> raw,
  }) : raw = Map.unmodifiable(raw);

  final String id;
  final Localised name;
  final Localised description;
  final String version;
  final String authorName;
  final Uri? authorUrl;
  final Uri? homepage;
  final Uri? privacyUrl;
  final HostKind hostKind;
  final Uri? hostUrl;
  final String? hostPackage;
  final AuthKind authKind;
  final String? authSetting;
  final Uri? authorizeUrl;
  final Uri? tokenUrl;
  final List<String> scopes;
  final List<CapabilityDecl> capabilities;
  final List<SettingDecl> settings;

  /// The manifest as it was given, for storing and re-parsing.
  final Map<String, Object?> raw;

  CapabilityDecl? capability(String id) {
    for (final c in capabilities) {
      if (c.id == id) return c;
    }
    return null;
  }

  Map<String, Object?> toJson() => raw;

  static final _id = RegExp(r'^[a-z][a-z0-9-]*(\.[a-z0-9][a-z0-9-]*){2,}$');
  static final _version = RegExp(r'^[0-9]+\.[0-9]+\.[0-9]+$');
  static final _package =
      RegExp(r'^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$');
  static final _settingKey = RegExp(r'^[a-z][a-z0-9_]*$');

  static const _top = {
    'kahf_connect',
    'id',
    'name',
    'description',
    'version',
    'author',
    'homepage',
    'privacy_url',
    'host',
    'auth',
    'capabilities',
    'settings',
  };

  /// Parses and checks [json]. Throws [ManifestError] on the first broken
  /// rule.
  static Manifest parse(Map<String, Object?> json) {
    for (final k in json.keys) {
      if (!_top.contains(k)) throw ManifestError('unknown-field', k);
    }
    if (json['kahf_connect'] != '1') {
      throw ManifestError('version-of-spec', '${json['kahf_connect']}');
    }
    final id = json['id'];
    if (id is! String || id.length > 128 || !_id.hasMatch(id)) {
      throw ManifestError('id', '$id');
    }
    final version = json['version'];
    if (version is! String || !_version.hasMatch(version)) {
      throw ManifestError('version', '$version');
    }
    final author = json['author'];
    if (author is! Map ||
        author['name'] is! String ||
        (author['name'] as String).isEmpty ||
        author.keys.any((k) => k != 'name' && k != 'url')) {
      throw ManifestError('author', '$author');
    }

    final host = json['host'];
    if (host is! Map) throw ManifestError('host', 'missing');
    final hostKind = switch (host['kind']) {
      'remote' => HostKind.remote,
      'builtin' => HostKind.builtin,
      'device' => HostKind.device,
      final k => throw ManifestError('host', 'kind "$k"'),
    };
    final hostUrl = _uri(host['url'], 'host.url');
    if (hostKind == HostKind.remote) {
      if (hostUrl == null) throw ManifestError('host-url', 'remote needs url');
      if (!_secure(hostUrl)) throw ManifestError('host-https', '$hostUrl');
    }
    final package = host['package'];
    if (hostKind == HostKind.device &&
        (package is! String || !_package.hasMatch(package))) {
      throw ManifestError('host-package', '$package');
    }

    final auth = json['auth'];
    if (auth is! Map) throw ManifestError('auth', 'missing');
    final authKind = switch (auth['kind']) {
      'none' => AuthKind.none,
      'bearer' => AuthKind.bearer,
      'oauth2' => AuthKind.oauth2,
      final k => throw ManifestError('auth', 'kind "$k"'),
    };

    final caps = json['capabilities'];
    if (caps is! List || caps.isEmpty) {
      throw ManifestError('capabilities', 'none declared');
    }
    final capabilities = <CapabilityDecl>[];
    for (final c in caps) {
      if (c is! Map) throw ManifestError('capabilities', '$c');
      final cid = c['id'];
      if (cid is! String || !Capabilities.known.contains(cid)) {
        throw ManifestError('capability-unknown', '$cid');
      }
      final v = c['version'];
      if (v is! int || v < 1) throw ManifestError('capability-version', '$v');
      if (c['reason'] == null) throw ManifestError('capability-reason', cid);
      final decl =
          CapabilityDecl(cid, v, Localised.parse(c['reason'], '$cid.reason'));
      if (capabilities.any((d) => d.id == cid)) {
        throw ManifestError('capability-duplicate', cid);
      }
      capabilities.add(decl);
    }

    final settings = <SettingDecl>[];
    final rawSettings = json['settings'];
    if (rawSettings != null) {
      if (rawSettings is! List) throw ManifestError('settings', 'not a list');
      for (final s in rawSettings) {
        if (s is! Map) throw ManifestError('settings', '$s');
        final key = s['key'];
        if (key is! String || !_settingKey.hasMatch(key)) {
          throw ManifestError('setting-key', '$key');
        }
        if (settings.any((d) => d.key == key)) {
          throw ManifestError('setting-duplicate', key);
        }
        final kind = switch (s['kind']) {
          'text' => SettingKind.text,
          'url' => SettingKind.url,
          'secret' => SettingKind.secret,
          'secret-url' => SettingKind.secretUrl,
          final k => throw ManifestError('setting-kind', '$k'),
        };
        settings.add(SettingDecl(
          key,
          kind,
          Localised.parse(s['label'], 'settings.$key.label'),
          help: s['help'] == null
              ? null
              : Localised.parse(s['help'], 'settings.$key.help'),
          required: s['required'] == true,
        ));
      }
    }

    final authSetting = auth['setting'];
    if (authKind == AuthKind.bearer) {
      if (authSetting is! String) {
        throw ManifestError('auth-setting', 'bearer needs a setting');
      }
      final s = settings.where((d) => d.key == authSetting);
      if (s.isEmpty || !s.first.secret) {
        throw ManifestError(
            'auth-setting', 'bearer token must be a secret setting');
      }
    }
    final authorizeUrl = _uri(auth['authorize_url'], 'auth.authorize_url');
    final tokenUrl = _uri(auth['token_url'], 'auth.token_url');
    if (authKind == AuthKind.oauth2 &&
        (authorizeUrl == null || tokenUrl == null)) {
      throw ManifestError('auth-oauth2', 'needs authorize_url and token_url');
    }
    for (final u in [authorizeUrl, tokenUrl]) {
      if (u != null && !_secure(u)) {
        throw ManifestError('auth-oauth2-https', '$u');
      }
    }

    return Manifest._(
      id: id,
      name: Localised.parse(json['name'], 'name'),
      description: Localised.parse(json['description'], 'description'),
      version: version,
      authorName: author['name'] as String,
      authorUrl: _uri(author['url'], 'author.url'),
      homepage: _uri(json['homepage'], 'homepage'),
      privacyUrl: _uri(json['privacy_url'], 'privacy_url'),
      hostKind: hostKind,
      hostUrl: hostUrl,
      hostPackage: package is String ? package : null,
      authKind: authKind,
      authSetting: authSetting is String ? authSetting : null,
      authorizeUrl: authorizeUrl,
      tokenUrl: tokenUrl,
      scopes: [
        if (auth['scopes'] is List)
          for (final s in auth['scopes'] as List) '$s'
      ],
      capabilities: capabilities,
      settings: settings,
      raw: json,
    );
  }

  /// HTTPS, or plain HTTP on this machine for development.
  static bool _secure(Uri u) =>
      u.scheme == 'https' ||
      (u.scheme == 'http' && (u.host == 'localhost' || u.host == '127.0.0.1'));

  static Uri? _uri(Object? raw, String field) {
    if (raw == null) return null;
    final u = raw is String ? checkedUri(raw) : null;
    if (u == null) throw ManifestError('uri', '$field: $raw');
    return u;
  }
}

/// A manifest that breaks a rule. [rule] is stable: the conformance fixtures
/// name it, so every SDK must throw the same one.
class ManifestError implements Exception {
  ManifestError(this.rule, [this.detail = '']);
  final String rule;
  final String detail;
  @override
  String toString() => 'ManifestError($rule: $detail)';
}

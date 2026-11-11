import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:kahf_connect/kahf_connect.dart';
import 'package:test/test.dart';

/// MODEL_TIERS.md, "Resolving a tier": the newest match wins, the answer is
/// cached a day, an offline device falls back to the cache and then the
/// pinned ID, and a "model not found" heals once and never loops.
void main() {
  final t0 = DateTime.utc(2026, 10, 27, 9);

  http.Response json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status,
          headers: {'content-type': 'application/json'});

  /// A provider that answers its model list from [models], counting calls.
  (MockClient, List<http.Request>) provider(Map<String, Object> answers) {
    final seen = <http.Request>[];
    return (
      MockClient((r) async {
        seen.add(r);
        final a = answers[r.url.host];
        if (a == null) return http.Response('offline', 503);
        return a is http.Response ? a : json(a);
      }),
      seen
    );
  }

  final anthropicModels = {
    'data': [
      {'id': 'claude-haiku-4-5', 'created_at': '2025-10-01T00:00:00Z'},
      {'id': 'claude-haiku-5-5', 'created_at': '2026-09-01T00:00:00Z'},
      {'id': 'claude-sonnet-5-5', 'created_at': '2026-09-01T00:00:00Z'},
      {'id': 'claude-opus-5-5', 'created_at': '2026-09-15T00:00:00Z'},
      {'id': 'claude-opus-5', 'created_at': '2026-05-01T00:00:00Z'},
      {'id': 'claude-fable-5-1', 'created_at': '2026-10-01T00:00:00Z'},
    ],
    'has_more': false,
  };

  test('the table this SDK carries is catalog/model-tiers.json', () {
    final catalog =
        jsonDecode(File('../catalog/model-tiers.json').readAsStringSync())
            as Map;
    expect(jsonDecode(bundledModelTiersJson), catalog);
    expect(TierTable.bundled.spec(ModelVendor.anthropic, ModelTier.low).pinned,
        'claude-haiku-5-5');
  });

  test('the default tier is low, and the labels are the doc\'s', () {
    expect(ModelTier.parse(null), ModelTier.low);
    expect(ModelTier.parse('nonsense'), ModelTier.low);
    expect(ModelTier.high.label('en'), 'Best');
    expect(ModelTier.medium.label('ar'), 'متوازن');
    expect(ModelTier.high.effort, 'high');
  });

  group('the newest match wins', () {
    test('Claude: by created_at, the tier\'s family only', () async {
      final (client, _) = provider({'api.anthropic.com': anthropicModels});
      final r = ModelResolver(client: client, clock: () => t0);
      expect((await r.resolve(ModelVendor.anthropic, ModelTier.low, 'k')).id,
          'claude-haiku-5-5');
      expect((await r.resolve(ModelVendor.anthropic, ModelTier.high, 'k')).id,
          'claude-opus-5-5',
          reason: 'Fable is newer but not the high tier');
    });

    test('Claude: every page is read', () async {
      var page = 0;
      final client = MockClient((r) async {
        page++;
        if (r.url.queryParameters['after_id'] == null) {
          return json({
            'data': [
              {'id': 'claude-sonnet-5', 'created_at': '2026-03-01T00:00:00Z'}
            ],
            'has_more': true,
            'last_id': 'claude-sonnet-5',
          });
        }
        return json({
          'data': [
            {'id': 'claude-sonnet-5-5', 'created_at': '2026-09-01T00:00:00Z'}
          ],
          'has_more': false,
        });
      });
      final r = ModelResolver(client: client, clock: () => t0);
      expect((await r.resolve(ModelVendor.anthropic, ModelTier.medium, 'k')).id,
          'claude-sonnet-5-5');
      expect(page, 2);
    });

    test('ChatGPT: by created, the family pattern only', () async {
      final (client, _) = provider({
        'api.openai.com': {
          'data': [
            {'id': 'gpt-6-luna', 'created': 1780000000},
            {'id': 'gpt-6.1-luna', 'created': 1790000000},
            {'id': 'gpt-6.1-luna-preview', 'created': 1795000000},
            {'id': 'gpt-6.1-sol', 'created': 1790000000},
          ]
        }
      });
      final r = ModelResolver(client: client, clock: () => t0);
      expect((await r.resolve(ModelVendor.openai, ModelTier.low, 'k')).id,
          'gpt-6.1-luna');
      expect((await r.resolve(ModelVendor.openai, ModelTier.high, 'k')).id,
          'gpt-6.1-sol');
    });

    test('Gemini: the -latest alias when listed, else the highest version',
        () async {
      final (client, seen) = provider({
        'generativelanguage.googleapis.com': {
          'models': [
            {
              'name': 'models/gemini-3.5-flash-lite',
              'supportedGenerationMethods': ['generateContent']
            },
            {
              'name': 'models/gemini-4-flash-lite',
              'supportedGenerationMethods': ['generateContent']
            },
            {
              'name': 'models/gemini-flash-latest',
              'supportedGenerationMethods': ['generateContent']
            },
            {
              'name': 'models/gemini-4-flash',
              'supportedGenerationMethods': ['generateContent']
            },
            {
              'name': 'models/text-embedding-9',
              'supportedGenerationMethods': ['embedContent']
            },
          ]
        }
      });
      final r = ModelResolver(client: client, clock: () => t0);
      expect((await r.resolve(ModelVendor.google, ModelTier.low, 'gk')).id,
          'gemini-4-flash-lite');
      expect((await r.resolve(ModelVendor.google, ModelTier.medium, 'gk')).id,
          'gemini-flash-latest');
      // The key travels in a header, never the URL.
      expect(seen.every((q) => !q.url.toString().contains('gk')), isTrue);
      expect(seen.first.headers['x-goog-api-key'], 'gk');
    });
  });

  test('cached for a day, then asked again', () async {
    var now = t0;
    final (client, seen) = provider({'api.anthropic.com': anthropicModels});
    final r = ModelResolver(client: client, clock: () => now);
    expect((await r.resolve(ModelVendor.anthropic, ModelTier.low, 'k')).source,
        ModelSource.live);
    now = t0.add(const Duration(hours: 23));
    final again = await r.resolve(ModelVendor.anthropic, ModelTier.low, 'k');
    expect(again.source, ModelSource.cache);
    expect(seen.length, 1);
    now = t0.add(const Duration(hours: 25));
    expect((await r.resolve(ModelVendor.anthropic, ModelTier.low, 'k')).source,
        ModelSource.live);
    expect(seen.length, 2);
  });

  test('offline: the stale cache, and with no cache the pinned ID', () async {
    final cache = MemoryTierCache();
    await cache.write(ModelResolver.cacheKey(ModelVendor.openai, ModelTier.low),
        'gpt-6.1-luna', t0.subtract(const Duration(days: 9)));
    final (client, _) = provider({});
    final r = ModelResolver(client: client, cache: cache, clock: () => t0);
    final stale = await r.resolve(ModelVendor.openai, ModelTier.low, 'k');
    expect((stale.id, stale.source), ('gpt-6.1-luna', ModelSource.cache));
    final pinned = await r.resolve(ModelVendor.google, ModelTier.high, 'k');
    expect(
        (pinned.id, pinned.source), ('gemini-pro-latest', ModelSource.pinned));
  });

  test('a key that may not list models falls back too', () async {
    final (client, _) = provider(
        {'api.anthropic.com': http.Response('{"error":"forbidden"}', 403)});
    final r = ModelResolver(client: client, clock: () => t0);
    final m = await r.resolve(ModelVendor.anthropic, ModelTier.medium, 'k');
    expect((m.id, m.source), ('claude-sonnet-5-5', ModelSource.pinned));
  });

  test('an exact model the person pinned is used as it is', () async {
    final (client, seen) = provider({'api.anthropic.com': anthropicModels});
    final r = ModelResolver(client: client, clock: () => t0);
    final m = await r.resolve(ModelVendor.anthropic, ModelTier.low, 'k',
        exact: 'claude-opus-5');
    expect((m.id, m.source), ('claude-opus-5', ModelSource.exact));
    expect(seen, isEmpty);
  });

  group('self-heal', () {
    test('a gone model drops the cache, resolves again, retries once',
        () async {
      final cache = MemoryTierCache();
      await cache.write(
          ModelResolver.cacheKey(ModelVendor.anthropic, ModelTier.low),
          'claude-haiku-4-5',
          t0);
      final (client, _) = provider({'api.anthropic.com': anthropicModels});
      final r = ModelResolver(client: client, cache: cache, clock: () => t0);
      final tried = <String>[];
      final out =
          await r.run(ModelVendor.anthropic, ModelTier.low, 'k', (model) async {
        tried.add(model);
        if (model == 'claude-haiku-4-5') throw StateError('model_not_found');
        return 'ok:$model';
      }, isModelGone: (e) => '$e'.contains('model_not_found'));
      expect(out, 'ok:claude-haiku-5-5');
      expect(tried, ['claude-haiku-4-5', 'claude-haiku-5-5']);
    });

    test('never loops: a second failure is the caller\'s', () async {
      final (client, _) = provider({'api.anthropic.com': anthropicModels});
      final r = ModelResolver(client: client, clock: () => t0);
      var calls = 0;
      await expectLater(
          r.run(ModelVendor.anthropic, ModelTier.low, 'k', (m) async {
            calls++;
            throw StateError('model_not_found');
          }, isModelGone: (_) => true),
          throwsStateError);
      expect(calls, 1,
          reason: 'resolving again gave the same model, so no retry');
    });

    test('other errors are not healed', () async {
      final (client, _) = provider({'api.anthropic.com': anthropicModels});
      final r = ModelResolver(client: client, clock: () => t0);
      var calls = 0;
      await expectLater(
          r.run(ModelVendor.anthropic, ModelTier.low, 'k', (m) async {
            calls++;
            throw StateError('rate limited');
          }, isModelGone: (e) => '$e'.contains('model_not_found')),
          throwsStateError);
      expect(calls, 1);
    });

    test('modelGone reads each provider\'s "not found"', () {
      expect(
          ModelResolver.modelGone(404,
              '{"type":"error","error":{"type":"not_found_error","message":"model: x"}}'),
          isTrue);
      expect(
          ModelResolver.modelGone(404, '{"error":{"code":"model_not_found"}}'),
          isTrue);
      expect(
          ModelResolver.modelGone(404,
              '{"error":{"status":"NOT_FOUND","message":"models/x is not found"}}'),
          isTrue);
      expect(ModelResolver.modelGone(429, 'rate limited'), isFalse);
      expect(ModelResolver.modelGone(400, 'max_tokens too large'), isFalse);
    });
  });
}

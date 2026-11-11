import 'dart:convert';
import 'dart:io';

import 'package:kahf_connect/kahf_connect.dart';
import 'package:test/test.dart';

/// SPEC §9: every SDK runs every fixture and agrees on every verdict.
void main() {
  final fixtures = Directory('../fixtures');
  Map<String, Object?> load(String path) =>
      (jsonDecode(File('${fixtures.path}/$path').readAsStringSync()) as Map)
          .cast<String, Object?>();

  group('manifests', () {
    final valid = Directory('${fixtures.path}/manifests/valid')
        .listSync()
        .whereType<File>()
        .toList()
      ..sort((a, b) => a.path.compareTo(b.path));
    for (final f in valid) {
      final name = f.uri.pathSegments.last;
      test('valid: $name', () {
        final m = Manifest.parse(
            (jsonDecode(f.readAsStringSync()) as Map).cast<String, Object?>());
        expect(m.capabilities, isNotEmpty);
        expect(m.toJson(), isNotEmpty);
      });
    }
    final index = jsonDecode(
        File('${fixtures.path}/manifests/invalid/index.json')
            .readAsStringSync()) as List;
    for (final c in index.cast<Map>()) {
      test('invalid: ${c['file']} breaks ${c['rule']}', () {
        expect(
            () => Manifest.parse(load('manifests/invalid/${c['file']}')),
            throwsA(
                isA<ManifestError>().having((e) => e.rule, 'rule', c['rule'])));
      });
    }
  });

  group('time.busy@1', () {
    final fx = load('capabilities/time.busy.v1.json');
    final from = DateTime.parse(fx['from'] as String);
    final to = DateTime.parse(fx['to'] as String);
    for (final (i, ok) in (fx['valid'] as List).indexed) {
      test('valid[$i]',
          () => expect(() => parseBusy(ok, from, to), returnsNormally));
    }
    for (final c in (fx['invalid'] as List).cast<Map>()) {
      test('invalid: ${c['rule']}', () {
        expect(
            () => parseBusy(c['output'], from, to),
            throwsA(isA<CapabilityError>()
                .having((e) => e.rule, 'rule', c['rule'])));
      });
    }
  });

  group('context.read@1', () {
    final fx = load('capabilities/context.read.v1.json');
    final limit = fx['limit'] as int;
    for (final (i, ok) in (fx['valid'] as List).indexed) {
      test('valid[$i]',
          () => expect(() => parseContext(ok, limit: limit), returnsNormally));
    }
    for (final c in (fx['invalid'] as List).cast<Map>()) {
      test('invalid: ${c['rule']}', () {
        expect(
            () => parseContext(c['output'], limit: limit),
            throwsA(isA<CapabilityError>()
                .having((e) => e.rule, 'rule', c['rule'])));
      });
    }
  });

  group('catalog', () {
    final testKey = {
      'test-only': '5d0CWh1aA7rAb7lqkKPaJJUpq0GRuI/fNh5D9qxV82g='
    };
    List<int> bytes(String f) =>
        File('${fixtures.path}/catalog/$f').readAsBytesSync();
    String sig(String f) =>
        File('${fixtures.path}/catalog/$f.sig').readAsStringSync();

    test('a signed catalog verifies and lists its entries', () async {
      final c = await Catalog.verify(bytes('catalog.json'), sig('catalog.json'),
          keys: testKey);
      expect(c.entries.map((e) => e.manifest.id),
          ['com.kahflabs.calendar-feed', 'com.kahflabs.mizwala.times']);
      expect(c.entries.every((e) => e.reviewed), isTrue);
    });

    test('one changed byte and it is refused', () async {
      await expectLater(
          Catalog.verify(bytes('tampered.json'), sig('tampered.json'),
              keys: testKey),
          throwsA(
              isA<CatalogError>().having((e) => e.rule, 'rule', 'signature')));
    });

    test('a key the SDK does not carry is refused', () async {
      await expectLater(
          Catalog.verify(bytes('catalog.json'), sig('catalog.json')),
          throwsA(isA<CatalogError>()
              .having((e) => e.rule, 'rule', 'key-unknown')));
    });
  });

  test('capability tools are named as SPEC §2 says', () {
    expect(Capabilities.tool('time.busy', 1), 'kahf_time_busy_v1');
    expect(Capabilities.tool('context.read', 1), 'kahf_context_read_v1');
    expect(Capabilities.tool('actions.propose', 2), 'kahf_actions_propose_v2');
  });
}

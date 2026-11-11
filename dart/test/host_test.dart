import 'dart:async';

import 'package:kahf_connect/kahf_connect.dart';
import 'package:test/test.dart';

/// The host is what makes connectors safe: nothing is called without a
/// grant, labels and agent visibility follow the grant, a broken or slow
/// connector cannot spoil the others, and every call is audited without its
/// data.
class _Fake extends Connector {
  _Fake(String id, {this.answer, this.error, this.delay = Duration.zero})
      : manifest = Manifest.parse({
          'kahf_connect': '1',
          'id': id,
          'name': {'en': id},
          'description': {'en': id},
          'version': '1.0.0',
          'author': {'name': 'Test'},
          'host': {'kind': 'builtin'},
          'auth': {'kind': 'none'},
          'capabilities': [
            {
              'id': 'time.busy',
              'version': 1,
              'reason': {'en': 'x'}
            },
            {
              'id': 'context.read',
              'version': 1,
              'reason': {'en': 'x'}
            },
          ],
        });

  @override
  final Manifest manifest;
  final Object? answer;
  final Object? error;
  final Duration delay;
  int calls = 0;

  @override
  Future<Set<String>> available() async => {'time.busy@1', 'context.read@1'};

  Future<Object?> _go() async {
    calls++;
    await Future<void>.delayed(delay);
    if (error != null) throw error!;
    return answer;
  }

  @override
  Future<Object?> busy(DateTime from, DateTime to) => _go();
  @override
  Future<Object?> read({String query = '', int limit = 20}) => _go();
}

void main() {
  final from = DateTime.utc(2026, 10, 27);
  final to = DateTime.utc(2026, 10, 28);
  final at = DateTime.utc(2026, 10, 26);
  final window = {
    'windows': [
      {
        'start': '2026-10-27T09:00:00Z',
        'end': '2026-10-27T10:00:00Z',
        'label': 'Board meeting'
      }
    ]
  };

  test('nothing is called without a grant', () async {
    final c = _Fake('com.example.one', answer: window);
    final audit = MemoryAuditSink();
    final host = ConnectorHost(connectors: [c], grants: [], audit: audit);
    final r = await host.busy(from, to);
    expect(r.items, isEmpty);
    expect(c.calls, 0);
    expect(audit.events, isEmpty);
  });

  test('labels are dropped unless the grant keeps them', () async {
    final c = _Fake('com.example.one', answer: window);
    var host = ConnectorHost(
        connectors: [c],
        grants: [Grant(c.manifest.id, 'time.busy', at: at)],
        audit: MemoryAuditSink());
    expect((await host.busy(from, to)).items.single.label, isNull);
    host = ConnectorHost(
        connectors: [c],
        grants: [Grant(c.manifest.id, 'time.busy', labels: true, at: at)],
        audit: MemoryAuditSink());
    expect((await host.busy(from, to)).items.single.label, 'Board meeting');
  });

  test('agents see only connectors whose grant lets them', () async {
    final shared = _Fake('com.example.shared', answer: {
      'items': [
        {'id': 'a', 'title': 'Plan', 'text': 'x'}
      ]
    });
    final private = _Fake('com.example.private', answer: {
      'items': [
        {'id': 'b', 'title': 'Diary', 'text': 'y'}
      ]
    });
    final host = ConnectorHost(connectors: [
      shared,
      private
    ], grants: [
      Grant(shared.manifest.id, 'context.read', agents: true, at: at),
      Grant(private.manifest.id, 'context.read', at: at),
    ], audit: MemoryAuditSink());
    final r = await host.read();
    expect(r.items.map((i) => i.id), ['a']);
    expect(private.calls, 0, reason: 'not even asked');
    // For the product itself (not an agent), both.
    expect((await host.read(forAgents: false)).items.length, 2);
  });

  test('one broken, one slow, one fine: the fine one still answers', () async {
    final fine = _Fake('com.example.fine', answer: window);
    final broken = _Fake('com.example.broken', answer: {
      'windows': [
        {'start': '2026-10-27T10:00:00Z', 'end': '2026-10-27T09:00:00Z'}
      ]
    });
    final slow = _Fake('com.example.slow',
        answer: window, delay: const Duration(milliseconds: 300));
    final audit = MemoryAuditSink();
    final host = ConnectorHost(
      connectors: [fine, broken, slow],
      grants: [
        for (final c in [fine, broken, slow])
          Grant(c.manifest.id, 'time.busy', at: at)
      ],
      audit: audit,
      timeout: const Duration(milliseconds: 50),
    );
    final r = await host.busy(from, to);
    expect(r.items.length, 1);
    expect(r.complete, isFalse);
    final by = {for (final s in r.sources) s.connector.id: s};
    expect(by['com.example.fine']!.outcome, Outcome.ok);
    expect(by['com.example.broken']!.outcome, Outcome.error);
    expect(by['com.example.broken']!.detail, 'contract:order');
    expect(by['com.example.slow']!.detail, 'timeout');
    expect(audit.events.length, 3);
  });

  test('the audit keeps counts and timings, never the data', () async {
    final c = _Fake('com.example.one', answer: window);
    final audit = MemoryAuditSink();
    final host = ConnectorHost(
        connectors: [c],
        grants: [Grant(c.manifest.id, 'time.busy', labels: true, at: at)],
        audit: audit);
    await host.busy(from, to);
    final e = audit.events.single;
    expect(e.outcome, Outcome.ok);
    expect(e.items, 1);
    expect(e.toJson().toString(), isNot(contains('Board meeting')));
    expect(AuditEvent.fromJson(e.toJson()).toJson(), e.toJson());
  });

  test('a connector that cannot serve says so, and is not an error', () async {
    final c = _Fake('com.example.one',
        error: ConnectorUnavailable('kahf_time_busy_v1 not served'));
    final host = ConnectorHost(
        connectors: [c],
        grants: [Grant(c.manifest.id, 'time.busy', at: at)],
        audit: MemoryAuditSink());
    final r = await host.busy(from, to);
    expect(r.sources.single.outcome, Outcome.unavailable);
  });

  test('grants round-trip', () {
    final g = Grant('com.example.one', 'time.busy',
        labels: true, agents: true, at: at);
    expect(Grant.fromJson(g.toJson()).toJson(), g.toJson());
  });
}

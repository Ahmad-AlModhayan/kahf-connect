import 'dart:convert';
import 'dart:io' show Platform;

import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:kahf_connect/kahf_connect.dart';
import 'package:test/test.dart';

/// A small MCP server in a function: answers initialize, tools/list and
/// tools/call, as JSON or as an event stream, and records what it was sent.
class _Server {
  _Server({this.stream = false, this.tools = const ['kahf_time_busy_v1']});
  final bool stream;
  final List<String> tools;
  final requests = <http.Request>[];

  MockClient get client => MockClient((r) async {
        requests.add(r);
        final m = jsonDecode(r.body) as Map;
        if (m['id'] == null) return http.Response('', 202);
        final Object result = switch (m['method']) {
          'initialize' => {
              'protocolVersion': '2025-06-18',
              'capabilities': {'tools': {}},
              'serverInfo': {'name': 'test', 'version': '1'},
            },
          'tools/list' => {
              'tools': [
                for (final t in tools)
                  {
                    'name': t,
                    'inputSchema': {'type': 'object'}
                  }
              ]
            },
          'tools/call' => {
              'content': [
                {'type': 'text', 'text': 'ok'}
              ],
              'structuredContent': {
                'windows': [
                  {
                    'start': '2026-10-27T09:00:00Z',
                    'end': '2026-10-27T10:00:00Z'
                  }
                ]
              },
            },
          _ => {},
        };
        final body =
            jsonEncode({'jsonrpc': '2.0', 'id': m['id'], 'result': result});
        return stream
            ? http.Response('event: message\ndata: $body\n\n', 200, headers: {
                'content-type': 'text/event-stream',
                'mcp-session-id': 's-1'
              })
            : http.Response(body, 200, headers: {
                'content-type': 'application/json',
                'mcp-session-id': 's-1'
              });
      });
}

Manifest remote({String url = 'https://connect.example.com/mcp'}) =>
    Manifest.parse({
      'kahf_connect': '1',
      'id': 'com.example.busy',
      'name': {'en': 'Busy'},
      'description': {'en': 'Busy'},
      'version': '1.0.0',
      'author': {'name': 'Example'},
      'host': {'kind': 'remote', 'url': url},
      'auth': {'kind': 'bearer', 'setting': 'token'},
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
      'settings': [
        {
          'key': 'token',
          'kind': 'secret',
          'label': {'en': 'Token'}
        }
      ],
    });

void main() {
  final from = DateTime.utc(2026, 10, 27);
  final to = DateTime.utc(2026, 10, 28);

  for (final stream in [false, true]) {
    test('busy windows over MCP (${stream ? 'event stream' : 'JSON'})',
        () async {
      final s = _Server(stream: stream);
      final c = McpConnector(remote(),
          settings: {'token': 'tok-1'}, client: s.client);
      final host = ConnectorHost(
          connectors: [c],
          grants: [Grant('com.example.busy', 'time.busy', at: from)],
          audit: MemoryAuditSink());
      final r = await host.busy(from, to);
      expect(r.items.single.start, DateTime.utc(2026, 10, 27, 9));
      final methods = [
        for (final q in s.requests) (jsonDecode(q.body) as Map)['method']
      ];
      expect(methods, [
        'initialize',
        'notifications/initialized',
        'tools/list',
        'tools/call'
      ]);
      // The token goes to the connector, the session and version come back.
      expect(
          s.requests.every((q) => q.headers['authorization'] == 'Bearer tok-1'),
          isTrue);
      expect(s.requests.last.headers['mcp-session-id'], 's-1');
      expect(s.requests.last.headers['mcp-protocol-version'], '2025-06-18');
      final call = jsonDecode(s.requests.last.body) as Map;
      expect(call['params']['name'], 'kahf_time_busy_v1');
      expect(call['params']['arguments'], {
        'from': '2026-10-27T00:00:00.000Z',
        'to': '2026-10-28T00:00:00.000Z'
      });
    });
  }

  test('a declared capability the server does not serve is unavailable',
      () async {
    final s = _Server(tools: const ['kahf_time_busy_v1']);
    final c =
        McpConnector(remote(), settings: {'token': 't'}, client: s.client);
    expect(await c.available(), {'time.busy@1'});
    final host = ConnectorHost(connectors: [
      c
    ], grants: [
      Grant('com.example.busy', 'context.read', agents: true, at: from)
    ], audit: MemoryAuditSink());
    final r = await host.read();
    expect(r.sources.single.outcome, Outcome.unavailable);
  });

  test('an HTTP error and a JSON-RPC error are McpErrors', () async {
    final down = McpHttpClient(Uri.parse('https://x.example.com/mcp'),
        client: MockClient((_) async => http.Response('nope', 503)));
    await expectLater(down.initialize(),
        throwsA(isA<McpError>().having((e) => e.status, 'status', 503)));
    final rpc = McpHttpClient(Uri.parse('https://x.example.com/mcp'),
        client: MockClient((r) async => http.Response(
            jsonEncode({
              'jsonrpc': '2.0',
              'id': (jsonDecode(r.body) as Map)['id'],
              'error': {'code': -32602, 'message': 'bad params'}
            }),
            200,
            headers: {'content-type': 'application/json'})));
    await expectLater(rpc.initialize(),
        throwsA(isA<McpError>().having((e) => e.code, 'code', -32602)));
  });

  test('a server speaking a protocol version we do not is refused', () async {
    final old = McpHttpClient(Uri.parse('https://x.example.com/mcp'),
        client: MockClient((r) async => http.Response(
            jsonEncode({
              'jsonrpc': '2.0',
              'id': (jsonDecode(r.body) as Map)['id'],
              'result': {'protocolVersion': '2024-11-05', 'capabilities': {}}
            }),
            200,
            headers: {'content-type': 'application/json'})));
    await expectLater(old.initialize(),
        throwsA(isA<McpError>().having((e) => e.kind, 'kind', 'protocol')));
  });

  // Against the reference connector (reference/python), when it runs:
  //   KAHF_REFERENCE_URL=http://127.0.0.1:8765/mcp dart test
  final ref = Platform.environment['KAHF_REFERENCE_URL'];
  test('the reference connector, end to end', () async {
    final c = McpConnector(remote(url: ref!), settings: {'token': 'reference'});
    final host = ConnectorHost(connectors: [
      c
    ], grants: [
      Grant('com.example.busy', 'time.busy', labels: true, at: from),
      Grant('com.example.busy', 'context.read', agents: true, at: from),
    ], audit: MemoryAuditSink());
    final now = DateTime.now().toUtc();
    final busy = await host.busy(now, now.add(const Duration(days: 2)));
    expect(busy.complete, isTrue, reason: busy.sources.first.detail);
    expect(busy.items, isNotEmpty);
    final read = await host.read(query: 'plan');
    expect(read.complete, isTrue, reason: read.sources.first.detail);
    expect(read.items, isNotEmpty);
    await c.close();
  }, skip: ref == null ? 'set KAHF_REFERENCE_URL to run' : false);
}

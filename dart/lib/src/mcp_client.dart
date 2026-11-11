/// The few MCP calls a product makes of a remote connector (SPEC §2):
/// `initialize`, `notifications/initialized`, `tools/list`, `tools/call`,
/// over streamable HTTP. Answers may come as JSON or as a server-sent event
/// stream; both are read.
///
/// Written here rather than taken from a package so the whole of what a
/// product sends to a connector is in one short file anyone can read.
library;

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

class McpTool {
  McpTool(this.name, {this.description = '', this.inputSchema});
  final String name;
  final String description;
  final Map<String, Object?>? inputSchema;
}

class McpToolResult {
  McpToolResult({this.structured, this.text = '', this.isError = false});

  /// `structuredContent`, which every Kahf capability answers with.
  final Object? structured;

  /// The text content, joined: for errors and for plain MCP tools.
  final String text;
  final bool isError;
}

/// A failure talking to the connector. [kind] is one of `http`, `rpc`,
/// `protocol`, `timeout`, `network`.
class McpError implements Exception {
  McpError(this.kind, this.message, {this.status, this.code});
  final String kind;
  final String message;
  final int? status;
  final int? code;
  @override
  String toString() =>
      'McpError($kind${status == null ? '' : ' $status'}${code == null ? '' : ' $code'}: $message)';
}

class McpHttpClient {
  McpHttpClient(this.url,
      {http.Client? client,
      this.bearer,
      this.timeout = const Duration(seconds: 20),
      this.clientName = 'kahf-connect',
      this.clientVersion = '1.0.0'})
      : _http = client ?? http.Client();

  final Uri url;
  final String? bearer;
  final Duration timeout;
  final String clientName;
  final String clientVersion;
  final http.Client _http;

  /// The protocol versions this client speaks, newest first.
  static const protocolVersions = ['2025-11-25', '2025-06-18', '2025-03-26'];

  String? _session;
  String? _protocol;
  int _next = 1;

  bool get initialized => _protocol != null;
  String? get protocolVersion => _protocol;

  /// The server's `serverInfo` after [initialize].
  Map<String, Object?>? serverInfo;

  Future<void> initialize() async {
    final r = await _request('initialize', {
      'protocolVersion': protocolVersions.first,
      'capabilities': <String, Object?>{},
      'clientInfo': {'name': clientName, 'version': clientVersion},
    });
    if (r is! Map) throw McpError('protocol', 'initialize answered $r');
    final v = r['protocolVersion'];
    if (v is! String || !protocolVersions.contains(v)) {
      throw McpError('protocol', 'unsupported protocol version $v');
    }
    _protocol = v;
    final info = r['serverInfo'];
    serverInfo = info is Map ? info.cast<String, Object?>() : null;
    await _notify('notifications/initialized');
  }

  Future<List<McpTool>> listTools() async {
    await _ready();
    final tools = <McpTool>[];
    String? cursor;
    for (var page = 0; page < 20; page++) {
      final r =
          await _request('tools/list', {if (cursor != null) 'cursor': cursor});
      if (r is! Map || r['tools'] is! List) {
        throw McpError('protocol', 'tools/list answered $r');
      }
      for (final t in r['tools'] as List) {
        if (t is Map && t['name'] is String) {
          tools.add(McpTool(t['name'] as String,
              description:
                  t['description'] is String ? t['description'] as String : '',
              inputSchema: t['inputSchema'] is Map
                  ? (t['inputSchema'] as Map).cast<String, Object?>()
                  : null));
        }
      }
      cursor = r['nextCursor'] as String?;
      if (cursor == null) break;
    }
    return tools;
  }

  Future<McpToolResult> callTool(String name, Map<String, Object?> args) async {
    await _ready();
    final r = await _request('tools/call', {'name': name, 'arguments': args});
    if (r is! Map) throw McpError('protocol', 'tools/call answered $r');
    final text = [
      if (r['content'] is List)
        for (final c in r['content'] as List)
          if (c is Map && c['type'] == 'text' && c['text'] is String)
            c['text'] as String,
    ].join('\n');
    var structured = r['structuredContent'];
    // Older servers put the JSON in the text alone.
    if (structured == null && r['isError'] != true && text.isNotEmpty) {
      try {
        structured = jsonDecode(text);
      } on FormatException {
        structured = null;
      }
    }
    return McpToolResult(
        structured: structured, text: text, isError: r['isError'] == true);
  }

  void close() => _http.close();

  Future<void> _ready() async {
    if (!initialized) await initialize();
  }

  Map<String, String> get _headers => {
        'content-type': 'application/json',
        'accept': 'application/json, text/event-stream',
        if (_protocol != null) 'mcp-protocol-version': _protocol!,
        if (_session != null) 'mcp-session-id': _session!,
        if (bearer != null) 'authorization': 'Bearer $bearer',
      };

  Future<http.Response> _post(Map<String, Object?> message) async {
    try {
      final r = await _http
          .post(url, headers: _headers, body: jsonEncode(message))
          .timeout(timeout);
      final s = r.headers['mcp-session-id'];
      if (s != null && s.isNotEmpty) _session = s;
      return r;
    } on TimeoutException {
      throw McpError('timeout', 'no answer from $url in $timeout');
    } on http.ClientException catch (e) {
      throw McpError('network', e.message);
    }
  }

  Future<void> _notify(String method) async {
    final r = await _post({'jsonrpc': '2.0', 'method': method});
    if (r.statusCode >= 400) {
      throw McpError('http', '$method refused', status: r.statusCode);
    }
  }

  Future<Object?> _request(String method, Map<String, Object?> params) async {
    final id = _next++;
    final r = await _post(
        {'jsonrpc': '2.0', 'id': id, 'method': method, 'params': params});
    if (r.statusCode != 200) {
      throw McpError('http', '$method: ${_short(r)}', status: r.statusCode);
    }
    final type = r.headers['content-type'] ?? '';
    final body = utf8.decode(r.bodyBytes);
    final Map<String, Object?> message;
    if (type.contains('text/event-stream')) {
      message = _fromStream(body, id);
    } else {
      final j = jsonDecode(body);
      if (j is! Map) throw McpError('protocol', '$method: not an object');
      message = j.cast<String, Object?>();
    }
    final error = message['error'];
    if (error is Map) {
      throw McpError('rpc', '${error['message']}',
          code: (error['code'] as num?)?.toInt());
    }
    if (message['id'] != id) throw McpError('protocol', '$method: wrong id');
    return message['result'];
  }

  /// The JSON-RPC answer to [id] in a server-sent event stream.
  static Map<String, Object?> _fromStream(String body, int id) {
    for (final event in body.split(RegExp(r'\r?\n\r?\n'))) {
      final data = [
        for (final line in event.split(RegExp(r'\r?\n')))
          if (line.startsWith('data:')) line.substring(5).trimLeft(),
      ].join('\n');
      if (data.isEmpty) continue;
      try {
        final j = jsonDecode(data);
        if (j is Map && j['id'] == id) return j.cast<String, Object?>();
      } on FormatException {
        continue;
      }
    }
    throw McpError('protocol', 'no answer to request $id in the stream');
  }

  static String _short(http.Response r) {
    final b = r.body;
    return b.length > 200 ? '${b.substring(0, 200)}…' : b;
  }
}

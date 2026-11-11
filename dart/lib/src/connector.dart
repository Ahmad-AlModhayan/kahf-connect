/// A connector as a product holds it: one [Manifest] and the calls behind
/// it. Remote, built-in and on-device connectors all look like this, so the
/// product's code — and the [ConnectorHost] that guards it — never asks where
/// one runs (SPEC §2.1).
library;

import 'package:http/http.dart' as http;

import 'manifest.dart';
import 'mcp_client.dart';

abstract class Connector {
  Manifest get manifest;

  /// The capabilities (`id@version`) it can actually serve now. A remote
  /// connector asks the server; a built-in one knows.
  Future<Set<String>> available();

  /// `time.busy@1` in [from, to). The raw answer: the host checks it.
  Future<Object?> busy(DateTime from, DateTime to);

  /// `context.read@1`.
  Future<Object?> read({String query = '', int limit = 20});

  Future<void> close() async {}
}

/// Thrown by a connector that is reachable but cannot answer (a capability
/// it does not serve, a tool that answered `isError`).
class ConnectorUnavailable implements Exception {
  ConnectorUnavailable(this.reason);
  final String reason;
  @override
  String toString() => 'ConnectorUnavailable($reason)';
}

/// A connector reached over MCP streamable HTTP.
class McpConnector extends Connector {
  McpConnector(this.manifest,
      {Map<String, String> settings = const {},
      http.Client? client,
      Duration timeout = const Duration(seconds: 20)})
      : _mcp = McpHttpClient(manifest.hostUrl!,
            client: client,
            timeout: timeout,
            bearer: manifest.authKind == AuthKind.bearer
                ? settings[manifest.authSetting]
                : null) {
    if (manifest.hostKind != HostKind.remote) {
      throw ArgumentError('${manifest.id} is not a remote connector');
    }
  }

  @override
  final Manifest manifest;
  final McpHttpClient _mcp;
  Set<String>? _tools;

  @override
  Future<Set<String>> available() async {
    final names = _tools ??= {for (final t in await _mcp.listTools()) t.name};
    return {
      for (final c in manifest.capabilities)
        if (names.contains(c.tool)) c.key,
    };
  }

  Future<Object?> _call(String capability, Map<String, Object?> args) async {
    final decl = manifest.capability(capability);
    if (decl == null) throw ConnectorUnavailable('$capability not declared');
    if (!(await available()).contains(decl.key)) {
      throw ConnectorUnavailable('${decl.tool} not served');
    }
    final r = await _mcp.callTool(decl.tool, args);
    if (r.isError) throw ConnectorUnavailable(r.text);
    return r.structured;
  }

  @override
  Future<Object?> busy(DateTime from, DateTime to) =>
      _call(Capabilities.timeBusy, {
        'from': from.toUtc().toIso8601String(),
        'to': to.toUtc().toIso8601String(),
      });

  @override
  Future<Object?> read({String query = '', int limit = 20}) =>
      _call(Capabilities.contextRead, {'query': query, 'limit': limit});

  @override
  Future<void> close() async => _mcp.close();
}

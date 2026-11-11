/// The product side of Kahf Connect: the one place a product calls its
/// connectors through. It holds the person's grants and enforces them,
/// checks every answer against its capability, strips what the grant does
/// not allow, times calls out, keeps one connector's failure from touching
/// another's answer, and writes an audit record for every call (SPEC §5–6).
///
/// A product never calls a [Connector] directly.
library;

import 'dart:async';

import 'capability.dart';
import 'connector.dart';
import 'manifest.dart';

/// The person's yes to one capability of one connector (SPEC §5).
class Grant {
  const Grant(this.connector, this.capability,
      {this.labels = false, this.agents = false, required this.at});

  final String connector;

  /// `id` of the capability, e.g. `time.busy`.
  final String capability;

  /// Keep labels (event titles) — off by default.
  final bool labels;

  /// Agents may see this connector's data — off by default.
  final bool agents;
  final DateTime at;

  Grant copyWith({bool? labels, bool? agents}) => Grant(connector, capability,
      labels: labels ?? this.labels, agents: agents ?? this.agents, at: at);

  Map<String, Object?> toJson() => {
        'connector': connector,
        'capability': capability,
        'labels': labels,
        'agents': agents,
        'at': at.toUtc().toIso8601String(),
      };

  static Grant fromJson(Map<String, Object?> j) => Grant(
        j['connector'] as String,
        j['capability'] as String,
        labels: j['labels'] == true,
        agents: j['agents'] == true,
        at: DateTime.parse(j['at'] as String),
      );
}

enum Outcome { ok, error, denied, unavailable }

/// One call, as the audit log keeps it: never the data, never a secret.
class AuditEvent {
  AuditEvent(
      {required this.at,
      required this.connector,
      required this.capability,
      required this.outcome,
      this.items = 0,
      this.ms = 0,
      this.detail = ''});

  final DateTime at;
  final String connector;
  final String capability;
  final Outcome outcome;
  final int items;
  final int ms;

  /// A short reason for a failure (an error's rule or kind), never data.
  final String detail;

  Map<String, Object?> toJson() => {
        'at': at.toUtc().toIso8601String(),
        'connector': connector,
        'capability': capability,
        'outcome': outcome.name,
        'items': items,
        'ms': ms,
        if (detail.isNotEmpty) 'detail': detail,
      };

  static AuditEvent fromJson(Map<String, Object?> j) => AuditEvent(
        at: DateTime.parse(j['at'] as String),
        connector: j['connector'] as String,
        capability: j['capability'] as String,
        outcome: Outcome.values.byName(j['outcome'] as String),
        items: (j['items'] as num?)?.toInt() ?? 0,
        ms: (j['ms'] as num?)?.toInt() ?? 0,
        detail: (j['detail'] as String?) ?? '',
      );
}

/// Where audit records go: the product's own storage.
abstract class AuditSink {
  Future<void> record(AuditEvent e);
}

class MemoryAuditSink implements AuditSink {
  final events = <AuditEvent>[];
  @override
  Future<void> record(AuditEvent e) async => events.add(e);
}

/// One connector's part of an answer across connectors.
class SourceResult<T> {
  SourceResult(this.connector, this.outcome, this.items, {this.detail = ''});
  final Manifest connector;
  final Outcome outcome;
  final List<T> items;
  final String detail;
}

/// What came back from every granted connector, each on its own.
class Gathered<T> {
  Gathered(this.sources);
  final List<SourceResult<T>> sources;

  List<T> get items => [for (final s in sources) ...s.items];
  bool get complete => sources.every((s) => s.outcome == Outcome.ok);
}

class ConnectorHost {
  ConnectorHost(
      {required List<Connector> connectors,
      required List<Grant> grants,
      required this.audit,
      DateTime Function()? clock,
      this.timeout = const Duration(seconds: 15)})
      : _connectors = List.unmodifiable(connectors),
        _grants = List.unmodifiable(grants),
        _clock = clock ?? DateTime.now;

  final List<Connector> _connectors;
  final List<Grant> _grants;
  final AuditSink audit;
  final DateTime Function() _clock;
  final Duration timeout;

  List<Connector> get connectors => _connectors;

  Grant? grant(String connector, String capability) {
    for (final g in _grants) {
      if (g.connector == connector && g.capability == capability) return g;
    }
    return null;
  }

  /// The connectors granted [capability] that declare it, and — when [forAgents] —
  /// whose grant lets agents see it.
  List<Connector> granted(String capability, {bool forAgents = false}) => [
        for (final c in _connectors)
          if (c.manifest.capability(capability) != null)
            if (grant(c.manifest.id, capability) case final g?)
              if (!forAgents || g.agents) c,
      ];

  /// Busy windows in [from, to) from every connector granted `time.busy`.
  /// One connector failing leaves the others' windows; its part says why.
  Future<Gathered<BusyWindow>> busy(DateTime from, DateTime to,
          {bool forAgents = false}) =>
      _gather(Capabilities.timeBusy, forAgents, (c) async {
        final raw = await c.busy(from, to);
        final windows = parseBusy(raw, from, to);
        final keep = grant(c.manifest.id, Capabilities.timeBusy)!.labels;
        return keep ? windows : [for (final w in windows) w.withoutLabel()];
      });

  /// Items from every connector granted `context.read`. Agents only see
  /// connectors whose grant says so ([forAgents]).
  Future<Gathered<ContextItem>> read(
          {String query = '', int limit = 20, bool forAgents = true}) =>
      _gather(Capabilities.contextRead, forAgents, (c) async {
        final raw = await c.read(query: query, limit: limit);
        return parseContext(raw, limit: limit);
      });

  Future<Gathered<T>> _gather<T>(String capability, bool forAgents,
      Future<List<T>> Function(Connector) call) async {
    final targets = granted(capability, forAgents: forAgents);
    final results = await Future.wait([
      for (final c in targets) _one(c, capability, () => call(c)),
    ]);
    return Gathered(results);
  }

  Future<SourceResult<T>> _one<T>(
      Connector c, String capability, Future<List<T>> Function() call) async {
    final started = _clock();
    final watch = Stopwatch()..start();
    Outcome outcome;
    var items = <T>[];
    var detail = '';
    try {
      items = await call().timeout(timeout);
      outcome = Outcome.ok;
    } on ConnectorUnavailable catch (e) {
      outcome = Outcome.unavailable;
      detail = _clip(e.reason);
    } on CapabilityError catch (e) {
      outcome = Outcome.error;
      detail = 'contract:${e.rule}';
    } on TimeoutException {
      outcome = Outcome.error;
      detail = 'timeout';
    } catch (e) {
      outcome = Outcome.error;
      detail = _clip(e.runtimeType.toString());
    }
    watch.stop();
    await audit.record(AuditEvent(
      at: started,
      connector: c.manifest.id,
      capability: capability,
      outcome: outcome,
      items: items.length,
      ms: watch.elapsedMilliseconds,
      detail: detail,
    ));
    return SourceResult(c.manifest, outcome, items, detail: detail);
  }

  static String _clip(String s) => s.length > 120 ? s.substring(0, 120) : s;
}

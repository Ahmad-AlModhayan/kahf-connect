/// Reading iCalendar (RFC 5545) for busy times: events, their time zones,
/// their recurrence, exceptions and moved instances. Only what `time.busy`
/// needs — when, how long, and (if the grant keeps it) the title.
///
/// Supported: DTSTART/DTEND/DURATION in UTC, with TZID (IANA names, and the
/// calendar's own VTIMEZONE offset when the name is not IANA), floating, and
/// all-day; RRULE with FREQ DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL,
/// BYDAY (with ordinals for monthly and yearly), BYMONTHDAY, BYMONTH,
/// BYSETPOS (e.g. "the last weekday of the month"); EXDATE;
/// RECURRENCE-ID overrides; STATUS:CANCELLED and TRANSP:TRANSPARENT (free
/// time) are left out.
library;

import 'package:timezone/data/latest_all.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;

import 'capability.dart';

bool _tzReady = false;
void _ensureTz() {
  if (_tzReady) return;
  tzdata.initializeTimeZones();
  _tzReady = true;
}

/// One content line: `NAME;PARAM=V:VALUE`.
class _Prop {
  _Prop(this.name, this.params, this.value);
  final String name;
  final Map<String, String> params;
  final String value;
}

class _Event {
  final props = <String, List<_Prop>>{};
  _Prop? one(String n) => props[n]?.first;
  List<_Prop> all(String n) => props[n] ?? const [];
}

/// A wall-clock time in a zone (or UTC, or floating local).
class _When {
  _When(this.y, this.mo, this.d, this.h, this.mi, this.s,
      {required this.zone, this.date = false});
  final int y, mo, d, h, mi, s;

  /// Null: floating (device local). `UTC`: UTC. Else an IANA location, or a
  /// fixed offset from the calendar's VTIMEZONE (`fixed:<minutes>`).
  final String? zone;
  final bool date;

  _When at(int y, int mo, int d) =>
      _When(y, mo, d, h, mi, s, zone: zone, date: date);

  DateTime instant() {
    final z = zone;
    if (z == null) return DateTime(y, mo, d, h, mi, s);
    if (z == 'UTC') return DateTime.utc(y, mo, d, h, mi, s);
    if (z.startsWith('fixed:')) {
      final off = int.parse(z.substring(6));
      return DateTime.utc(y, mo, d, h, mi, s).subtract(Duration(minutes: off));
    }
    final loc = tz.getLocation(z);
    final t = tz.TZDateTime(loc, y, mo, d, h, mi, s);
    return DateTime.fromMillisecondsSinceEpoch(t.millisecondsSinceEpoch,
        isUtc: true);
  }
}

class IcsCalendar {
  IcsCalendar._(this._events, this._fixedZones);

  final List<_Event> _events;

  /// TZID → offset in minutes, from the calendar's own VTIMEZONE blocks
  /// (their STANDARD offset): the fallback for names that are not IANA.
  final Map<String, int> _fixedZones;

  int get eventCount => _events.length;

  static IcsCalendar parse(String text) {
    _ensureTz();
    final lines = <String>[];
    for (final raw in text.split(RegExp(r'\r?\n'))) {
      if ((raw.startsWith(' ') || raw.startsWith('\t')) && lines.isNotEmpty) {
        lines[lines.length - 1] += raw.substring(1);
      } else if (raw.isNotEmpty) {
        lines.add(raw);
      }
    }
    final events = <_Event>[];
    final fixed = <String, int>{};
    _Event? current;
    String? tzid;
    var inStandard = false;
    final stack = <String>[];
    for (final line in lines) {
      final p = _prop(line);
      if (p == null) continue;
      if (p.name == 'BEGIN') {
        stack.add(p.value.toUpperCase());
        if (p.value.toUpperCase() == 'VEVENT') current = _Event();
        if (p.value.toUpperCase() == 'STANDARD') inStandard = true;
        continue;
      }
      if (p.name == 'END') {
        final what = p.value.toUpperCase();
        if (stack.isNotEmpty) stack.removeLast();
        if (what == 'VEVENT' && current != null) {
          events.add(current);
          current = null;
        }
        if (what == 'STANDARD') inStandard = false;
        if (what == 'VTIMEZONE') tzid = null;
        continue;
      }
      if (current != null && stack.isNotEmpty && stack.last == 'VEVENT') {
        current.props.putIfAbsent(p.name, () => []).add(p);
      } else if (stack.contains('VTIMEZONE')) {
        if (p.name == 'TZID') tzid = p.value;
        if (inStandard && p.name == 'TZOFFSETTO' && tzid != null) {
          final m = RegExp(r'^([+-])(\d{2})(\d{2})').firstMatch(p.value);
          if (m != null) {
            final v = int.parse(m[2]!) * 60 + int.parse(m[3]!);
            fixed.putIfAbsent(tzid, () => m[1] == '-' ? -v : v);
          }
        }
      }
    }
    return IcsCalendar._(events, fixed);
  }

  static _Prop? _prop(String line) {
    // The value starts at the first colon outside a quoted parameter.
    var quoted = false;
    var colon = -1;
    for (var i = 0; i < line.length; i++) {
      final ch = line[i];
      if (ch == '"') quoted = !quoted;
      if (ch == ':' && !quoted) {
        colon = i;
        break;
      }
    }
    if (colon < 0) return null;
    final head = line.substring(0, colon).split(';');
    final params = <String, String>{};
    for (final kv in head.skip(1)) {
      final eq = kv.indexOf('=');
      if (eq > 0) {
        params[kv.substring(0, eq).toUpperCase()] =
            kv.substring(eq + 1).replaceAll('"', '');
      }
    }
    return _Prop(head.first.toUpperCase(), params, line.substring(colon + 1));
  }

  String? _zone(String? tzid) {
    if (tzid == null) return null;
    final name = tzid.startsWith('/') ? tzid.substring(1) : tzid;
    try {
      tz.getLocation(name);
      return name;
    } catch (_) {
      final off = _fixedZones[tzid];
      return off == null ? null : 'fixed:$off';
    }
  }

  _When? _when(_Prop? p) {
    if (p == null) return null;
    return _parseWhen(p.value.trim(), p.params);
  }

  _When? _parseWhen(String v, Map<String, String> params) {
    final date = RegExp(r'^(\d{4})(\d{2})(\d{2})$').firstMatch(v);
    if (date != null || params['VALUE'] == 'DATE') {
      final m = date ?? RegExp(r'^(\d{4})(\d{2})(\d{2})').firstMatch(v);
      if (m == null) return null;
      return _When(
          int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!), 0, 0, 0,
          zone: null, date: true);
    }
    final m = RegExp(r'^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z?)$')
        .firstMatch(v);
    if (m == null) return null;
    final zone = m[7] == 'Z' ? 'UTC' : _zone(params['TZID']);
    return _When(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!),
        int.parse(m[4]!), int.parse(m[5]!), int.parse(m[6]!),
        zone: zone);
  }

  static Duration? _duration(String? v) {
    if (v == null) return null;
    final m = RegExp(
            r'^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$')
        .firstMatch(v.trim());
    if (m == null) return null;
    int n(int i) => int.tryParse(m[i] ?? '') ?? 0;
    final d = Duration(
        days: n(2) * 7 + n(3), hours: n(4), minutes: n(5), seconds: n(6));
    return m[1] == '-' ? -d : d;
  }

  /// Busy windows meeting [from, to), sorted. [labels] keeps SUMMARY.
  List<BusyWindow> busy(DateTime from, DateTime to, {bool labels = true}) {
    final out = <BusyWindow>[];
    // Moved or changed instances: UID → original start → the override.
    final overrides = <String, Map<DateTime, _Event>>{};
    for (final e in _events) {
      final rid = _when(e.one('RECURRENCE-ID'));
      final uid = e.one('UID')?.value;
      if (rid != null && uid != null) {
        overrides.putIfAbsent(uid, () => {})[rid.instant()] = e;
      }
    }
    for (final e in _events) {
      if (e.one('RECURRENCE-ID') != null) {
        _single(e, from, to, labels, out);
        continue;
      }
      final rrule = e.one('RRULE');
      if (rrule == null) {
        _single(e, from, to, labels, out);
      } else {
        _recurring(e, rrule.value, from, to, labels,
            overrides[e.one('UID')?.value] ?? const {}, out);
      }
    }
    out.sort((a, b) => a.start.compareTo(b.start));
    return out;
  }

  bool _skipped(_Event e) =>
      e.one('STATUS')?.value.toUpperCase() == 'CANCELLED' ||
      e.one('TRANSP')?.value.toUpperCase() == 'TRANSPARENT';

  /// Start and length of [e] (its first occurrence).
  (_When, Duration)? _span(_Event e) {
    final start = _when(e.one('DTSTART'));
    if (start == null) return null;
    final end = _when(e.one('DTEND'));
    Duration length;
    if (end != null) {
      length = end.instant().difference(start.instant());
    } else {
      length = _duration(e.one('DURATION')?.value) ??
          (start.date ? const Duration(days: 1) : Duration.zero);
    }
    return (start, length);
  }

  void _single(
      _Event e, DateTime from, DateTime to, bool labels, List<BusyWindow> out) {
    if (_skipped(e)) return;
    final span = _span(e);
    if (span == null) return;
    _add(e, span.$1, span.$2, from, to, labels, out);
  }

  void _add(_Event e, _When start, Duration length, DateTime from, DateTime to,
      bool labels, List<BusyWindow> out) {
    if (length <= Duration.zero) return;
    final s = start.instant();
    final end = start.date
        ? start.at(start.y, start.mo, start.d + length.inDays).instant()
        : s.add(length);
    final w = BusyWindow(s, end,
        label: labels ? _text(e.one('SUMMARY')?.value) : null,
        allDay: start.date);
    if (w.overlaps(from, to)) out.add(w);
  }

  static String? _text(String? v) {
    if (v == null || v.isEmpty) return null;
    final t = v
        .replaceAll(r'\n', ' ')
        .replaceAll(r'\N', ' ')
        .replaceAll(r'\,', ',')
        .replaceAll(r'\;', ';')
        .replaceAll(r'\\', r'\');
    return t.length > 200 ? t.substring(0, 200) : t;
  }

  static const _days = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'];

  void _recurring(_Event e, String rule, DateTime from, DateTime to,
      bool labels, Map<DateTime, _Event> moved, List<BusyWindow> out) {
    final span = _span(e);
    if (span == null) return;
    final (start, length) = span;
    final r = <String, String>{};
    for (final part in rule.split(';')) {
      final eq = part.indexOf('=');
      if (eq > 0) {
        r[part.substring(0, eq).toUpperCase()] = part.substring(eq + 1);
      }
    }
    final freq = r['FREQ'];
    final interval = int.tryParse(r['INTERVAL'] ?? '') ?? 1;
    final count = int.tryParse(r['COUNT'] ?? '');
    final until = r['UNTIL'] == null
        ? null
        : _parseWhen(
                r['UNTIL']!,
                start.zone == null || start.zone == 'UTC'
                    ? const {}
                    : {'TZID': start.zone!})
            ?.instant();
    final byDay = r['BYDAY']?.split(',') ?? const <String>[];
    final byMonthDay = [
      for (final s in (r['BYMONTHDAY']?.split(',') ?? const <String>[]))
        if (int.tryParse(s) != null) int.parse(s)
    ];
    final byMonth = [
      for (final s in (r['BYMONTH']?.split(',') ?? const <String>[]))
        if (int.tryParse(s) != null) int.parse(s)
    ];
    // BYSETPOS picks from each period's dates ("-1" with BYDAY=MO..FR is the
    // last weekday of the month). Only rules that carry it are touched.
    final bySetPos = [
      for (final s in (r['BYSETPOS']?.split(',') ?? const <String>[]))
        if (int.tryParse(s) case final n? when n != 0) n
    ];
    final exdates = <DateTime>{
      for (final p in e.all('EXDATE'))
        for (final v in p.value.split(','))
          if (_parseWhen(v.trim(), p.params) case final w?) w.instant(),
    };
    final cancelled = _skipped(e);

    var produced = 0;
    var guard = 0;
    // A ceiling on the dates looked at, as well as the periods walked: a rule
    // built to be expensive (every day of every month of every year since
    // year one) ends instead of freezing whoever asked.
    var looked = 0;
    // Walk period by period (day, week, month or year from DTSTART), taking
    // the dates each period yields, in order.
    // Bounded: a rule that never yields (BYMONTH=2 with BYMONTHDAY=30) or
    // one that started decades ago still ends.
    for (var period = 0; guard < 50000; period += interval) {
      guard++;
      var dates = _period(freq, start, period, byDay, byMonthDay, byMonth);
      if (dates == null) return;
      if (bySetPos.isNotEmpty) dates = _setPos(dates, bySetPos);
      looked += dates.length;
      if (looked > 50000) return;
      for (final d in dates) {
        final occ = start.at(d.year, d.month, d.day);
        final at = occ.instant();
        if (at.isBefore(start.instant())) continue;
        if (until != null && at.isAfter(until)) return;
        if (count != null && produced >= count) return;
        produced++;
        if (!at.isBefore(to)) return;
        if (exdates.contains(at)) continue;
        final override = moved[at];
        if (override != null) continue; // drawn by _single
        if (!cancelled) _add(e, occ, length, from, to, labels, out);
      }
    }
  }

  /// The [positions] (1-based, negative from the end) of one period's
  /// [dates], in order, each once.
  static List<DateTime> _setPos(List<DateTime> dates, List<int> positions) {
    final sorted = dates.toSet().toList()..sort();
    final out = <DateTime>{};
    for (final p in positions) {
      final i = p > 0 ? p - 1 : sorted.length + p;
      if (i >= 0 && i < sorted.length) out.add(sorted[i]);
    }
    return out.toList()..sort();
  }

  /// The dates of the [n]th period from [start], or null for a frequency we
  /// do not read.
  static List<DateTime>? _period(String? freq, _When start, int n,
      List<String> byDay, List<int> byMonthDay, List<int> byMonth) {
    final base = DateTime.utc(start.y, start.mo, start.d);
    switch (freq) {
      case 'DAILY':
        final d = DateTime.utc(start.y, start.mo, start.d + n);
        if (byMonth.isNotEmpty && !byMonth.contains(d.month)) return const [];
        if (byDay.isNotEmpty &&
            !byDay.any((b) =>
                _days.indexOf(b.substring(b.length - 2)) + 1 == d.weekday)) {
          return const [];
        }
        return [d];
      case 'WEEKLY':
        // The week (Monday first) containing start, n weeks on.
        final monday = base.subtract(Duration(days: base.weekday - 1));
        final week =
            DateTime.utc(monday.year, monday.month, monday.day + 7 * n);
        final days = byDay.isEmpty
            ? [base.weekday]
            : [
                for (final b in byDay)
                  if (_days.contains(b.substring(b.length - 2)))
                    _days.indexOf(b.substring(b.length - 2)) + 1
              ]
          ..sort();
        return [
          for (final wd in days)
            DateTime.utc(week.year, week.month, week.day + wd - 1)
        ];
      case 'MONTHLY':
        final y = start.y + (start.mo - 1 + n) ~/ 12;
        final m = (start.mo - 1 + n) % 12 + 1;
        return _inMonth(y, m, start.d, byDay, byMonthDay);
      case 'YEARLY':
        final y = start.y + n;
        final months = byMonth.isEmpty ? [start.mo] : (byMonth..sort());
        return [
          for (final m in months) ..._inMonth(y, m, start.d, byDay, byMonthDay)
        ];
      default:
        return null;
    }
  }

  static List<DateTime> _inMonth(
      int y, int m, int day, List<String> byDay, List<int> byMonthDay) {
    final last = DateTime.utc(y, m + 1, 0).day;
    final out = <DateTime>[];
    if (byDay.isNotEmpty) {
      for (final b in byDay) {
        final wd = _days.indexOf(b.substring(b.length - 2)) + 1;
        if (wd == 0) continue;
        final ord =
            b.length > 2 ? int.tryParse(b.substring(0, b.length - 2)) : null;
        final all = [
          for (var d = 1; d <= last; d++)
            if (DateTime.utc(y, m, d).weekday == wd) d
        ];
        if (ord == null) {
          out.addAll([for (final d in all) DateTime.utc(y, m, d)]);
        } else {
          final i = ord > 0 ? ord - 1 : all.length + ord;
          if (i >= 0 && i < all.length) out.add(DateTime.utc(y, m, all[i]));
        }
      }
    } else if (byMonthDay.isNotEmpty) {
      for (final md in byMonthDay) {
        final d = md > 0 ? md : last + md + 1;
        if (d >= 1 && d <= last) out.add(DateTime.utc(y, m, d));
      }
    } else if (day <= last) {
      out.add(DateTime.utc(y, m, day));
    }
    out.sort();
    return out;
  }
}

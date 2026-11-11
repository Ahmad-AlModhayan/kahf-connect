/// Calendar feed: busy times from any calendar's private iCalendar address
/// (Google Calendar "Secret address in iCal format", Outlook "Publish a
/// calendar → ICS", iCloud "Public calendar"), with no account sign-in.
/// Built in to every product that uses this SDK.
library;

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../connector.dart';
import '../ics.dart';
import '../manifest.dart';
import '../uri_check.dart';

class CalendarFeedConnector extends Connector {
  CalendarFeedConnector(Map<String, String> settings,
      {http.Client? client,
      DateTime Function()? clock,
      this.cacheFor = const Duration(minutes: 15)})
      : _url = _normalise(settings['url'] ?? ''),
        _http = client ?? http.Client(),
        _clock = clock ?? DateTime.now;

  static const id = 'com.kahflabs.calendar-feed';

  static final Manifest definition = Manifest.parse({
    'kahf_connect': '1',
    'id': id,
    'name': {'en': 'Calendar feed', 'ar': 'رابط التقويم'},
    'description': {
      'en': 'Busy times from your calendar\'s private address — Google, '
          'Outlook, iCloud or any other. No sign-in.',
      'ar': 'أوقات انشغالك من الرابط الخاص لتقويمك — Google أو Outlook أو '
          'iCloud أو غيرها. دون تسجيل دخول.',
    },
    'version': '1.0.0',
    'author': {'name': 'Kahf Labs', 'url': 'https://kahflabs.com'},
    'host': {'kind': 'builtin'},
    'auth': {'kind': 'none'},
    'capabilities': [
      {
        'id': 'time.busy',
        'version': 1,
        'reason': {
          'en': 'To keep planned focus off the times your calendar says you '
              'are busy.',
          'ar': 'لكي لا يُخطَّط تركيز في أوقات يقول تقويمك إنك مشغول فيها.',
        },
      }
    ],
    'settings': [
      {
        'key': 'url',
        'kind': 'secret-url',
        'required': true,
        'label': {
          'en': 'Private calendar address',
          'ar': 'الرابط الخاص للتقويم'
        },
        'help': {
          'en': 'Google Calendar: Settings → your calendar → "Secret address '
              'in iCal format". It starts with https:// and ends with .ics.',
          'ar':
              'في Google Calendar: الإعدادات ← تقويمك ← «العنوان السري بتنسيق '
                  'iCal». يبدأ بـ https:// وينتهي بـ ‎.ics.',
        },
      }
    ],
  });

  @override
  Manifest get manifest => definition;

  final Uri? _url;
  final http.Client _http;
  final DateTime Function() _clock;
  final Duration cacheFor;

  IcsCalendar? _calendar;
  DateTime? _fetchedAt;

  /// `webcal://` is `https://`; anything else that is not https is refused,
  /// since the address is a secret.
  static Uri? _normalise(String raw) {
    var s = raw.trim();
    if (s.startsWith('webcal://')) s = 'https://${s.substring(9)}';
    final u = checkedUri(s);
    if (u == null || u.scheme != 'https') return null;
    return u;
  }

  /// Whether [raw] is an address this connector can read.
  static bool validUrl(String raw) => _normalise(raw) != null;

  @override
  Future<Set<String>> available() async =>
      _url == null ? const {} : {'time.busy@1'};

  Future<IcsCalendar> _load() async {
    final url = _url;
    if (url == null) throw ConnectorUnavailable('no valid https address');
    final now = _clock();
    if (_calendar != null &&
        _fetchedAt != null &&
        now.difference(_fetchedAt!) < cacheFor) {
      return _calendar!;
    }
    final r = await _http.get(url, headers: {'accept': 'text/calendar'});
    if (r.statusCode != 200) {
      throw ConnectorUnavailable('the calendar answered ${r.statusCode}');
    }
    final text = utf8.decode(r.bodyBytes, allowMalformed: true);
    if (!text.contains('BEGIN:VCALENDAR')) {
      throw ConnectorUnavailable('that address is not a calendar feed');
    }
    _calendar = IcsCalendar.parse(text);
    _fetchedAt = now;
    return _calendar!;
  }

  @override
  Future<Object?> busy(DateTime from, DateTime to) async {
    final cal = await _load();
    return {
      'windows': [for (final w in cal.busy(from, to)) w.toJson()],
    };
  }

  @override
  Future<Object?> read({String query = '', int limit = 20}) async =>
      throw ConnectorUnavailable('context.read not served');

  @override
  Future<void> close() async => _http.close();
}

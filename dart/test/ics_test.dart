import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:kahf_connect/kahf_connect.dart';
import 'package:test/test.dart';

String cal(String events, {String extra = ''}) => [
      'BEGIN:VCALENDAR',
      'VERSION:2.0',
      'PRODID:-//Test//EN',
      extra,
      events,
      'END:VCALENDAR',
    ].where((l) => l.isNotEmpty).join('\r\n');

String ev(List<String> lines) =>
    ['BEGIN:VEVENT', ...lines, 'END:VEVENT'].join('\r\n');

void main() {
  final from = DateTime.utc(2026, 10, 26);
  final to = DateTime.utc(2026, 11, 2);

  List<BusyWindow> busy(String text, {DateTime? f, DateTime? t}) =>
      IcsCalendar.parse(text).busy(f ?? from, t ?? to);

  test('a UTC event, with its title', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20261027T090000Z',
      'DTEND:20261027T100000Z',
      'SUMMARY:Board\\, quarterly',
    ])));
    expect(w.single.start, DateTime.utc(2026, 10, 27, 9));
    expect(w.single.end, DateTime.utc(2026, 10, 27, 10));
    expect(w.single.label, 'Board, quarterly');
  });

  test('a time zone by IANA name (Riyadh is +03:00)', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART;TZID=Asia/Riyadh:20261027T120000',
      'DTEND;TZID=Asia/Riyadh:20261027T130000',
    ])));
    expect(w.single.start, DateTime.utc(2026, 10, 27, 9));
  });

  test('a time zone named the Outlook way falls back to its VTIMEZONE', () {
    final w = busy(cal(
        ev([
          'UID:1',
          'DTSTART;TZID=Arab Standard Time:20261027T120000',
          'DTEND;TZID=Arab Standard Time:20261027T130000',
        ]),
        extra: [
          'BEGIN:VTIMEZONE',
          'TZID:Arab Standard Time',
          'BEGIN:STANDARD',
          'DTSTART:16010101T000000',
          'TZOFFSETFROM:+0300',
          'TZOFFSETTO:+0300',
          'END:STANDARD',
          'END:VTIMEZONE',
        ].join('\r\n')));
    expect(w.single.start, DateTime.utc(2026, 10, 27, 9));
  });

  test('an all-day event covers its whole day', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART;VALUE=DATE:20261028',
      'DTEND;VALUE=DATE:20261029',
    ])));
    expect(w.single.allDay, isTrue);
    expect(w.single.end.difference(w.single.start), const Duration(days: 1));
  });

  test('DURATION instead of DTEND', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20261027T090000Z',
      'DURATION:PT1H30M',
    ])));
    expect(w.single.end, DateTime.utc(2026, 10, 27, 10, 30));
  });

  test('cancelled and free events are not busy', () {
    final w = busy(cal([
      ev([
        'UID:1',
        'DTSTART:20261027T090000Z',
        'DTEND:20261027T100000Z',
        'STATUS:CANCELLED'
      ]),
      ev([
        'UID:2',
        'DTSTART:20261027T110000Z',
        'DTEND:20261027T120000Z',
        'TRANSP:TRANSPARENT'
      ]),
      ev(['UID:3', 'DTSTART:20261027T130000Z', 'DTEND:20261027T140000Z']),
    ].join('\r\n')));
    expect(w.map((x) => x.start.hour), [13]);
  });

  test('folded lines are unfolded', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20261027T090000Z',
      'DTEND:20261027T100000Z',
      'SUMMARY:A very long',
      '  title',
    ])));
    expect(w.single.label, 'A very long title');
  });

  test('weekly on two days, every week, since last year', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20250106T070000Z', // a Monday
      'DTEND:20250106T080000Z',
      'RRULE:FREQ=WEEKLY;BYDAY=MO,WE',
    ])));
    // 26 Oct 2026 is a Monday: Mon 26, Wed 28 in the window.
    expect(w.map((x) => x.start.day), [26, 28]);
  });

  test('daily with COUNT stops after COUNT', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20261026T060000Z',
      'DTEND:20261026T063000Z',
      'RRULE:FREQ=DAILY;COUNT=3',
    ])));
    expect(w.length, 3);
  });

  test('UNTIL ends the rule', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20261026T060000Z',
      'DTEND:20261026T063000Z',
      'RRULE:FREQ=DAILY;UNTIL=20261028T235959Z',
    ])));
    expect(w.map((x) => x.start.day), [26, 27, 28]);
  });

  test('EXDATE takes one occurrence out', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20261026T060000Z',
      'DTEND:20261026T063000Z',
      'RRULE:FREQ=DAILY;COUNT=4',
      'EXDATE:20261027T060000Z',
    ])));
    expect(w.map((x) => x.start.day), [26, 28, 29]);
  });

  test('a moved instance replaces its occurrence', () {
    final w = busy(cal([
      ev([
        'UID:standup',
        'DTSTART:20261026T060000Z',
        'DTEND:20261026T063000Z',
        'RRULE:FREQ=DAILY;COUNT=3',
      ]),
      ev([
        'UID:standup',
        'RECURRENCE-ID:20261027T060000Z',
        'DTSTART:20261027T150000Z',
        'DTEND:20261027T153000Z',
      ]),
    ].join('\r\n')));
    expect(w.map((x) => '${x.start.day}:${x.start.hour}'),
        ['26:6', '27:15', '28:6']);
  });

  test('monthly on the last Friday', () {
    final w = busy(
        cal(ev([
          'UID:1',
          'DTSTART:20260130T100000Z',
          'DTEND:20260130T110000Z',
          'RRULE:FREQ=MONTHLY;BYDAY=-1FR',
        ])),
        f: DateTime.utc(2026, 10, 1),
        t: DateTime.utc(2026, 12, 1));
    expect(w.map((x) => x.start.day), [30, 27]); // Oct 30, Nov 27
  });

  test('a recurring event in a zone keeps its wall time across DST', () {
    // 09:00 New York every Monday: 13:00Z in summer time, 14:00Z after
    // the change on 1 Nov 2026 (19 and 26 Oct, 2 and 9 Nov).
    final w = busy(
        cal(ev([
          'UID:1',
          'DTSTART;TZID=America/New_York:20261019T090000',
          'DTEND;TZID=America/New_York:20261019T100000',
          'RRULE:FREQ=WEEKLY',
        ])),
        f: DateTime.utc(2026, 10, 19),
        t: DateTime.utc(2026, 11, 10));
    expect(w.map((x) => x.start.hour), [13, 13, 14, 14]);
  });

  test('BYSETPOS: the last weekday of each month, as Google writes it', () {
    final w = busy(
        cal(ev([
          'UID:1',
          'DTSTART:20260930T150000Z',
          'DTEND:20260930T160000Z',
          'RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1',
        ])),
        f: DateTime.utc(2026, 10, 1),
        t: DateTime.utc(2027, 1, 1));
    expect(w.map((x) => x.start), [
      DateTime.utc(2026, 10, 30, 15), // Friday
      DateTime.utc(2026, 11, 30, 15), // Monday
      DateTime.utc(2026, 12, 31, 15), // Thursday
    ]);
    final first = busy(
        cal(ev([
          'UID:2',
          'DTSTART:20261001T090000Z',
          'DTEND:20261001T100000Z',
          'RRULE:FREQ=MONTHLY;BYDAY=SA,SU;BYSETPOS=1,2;COUNT=4',
        ])),
        f: DateTime.utc(2026, 10, 1),
        t: DateTime.utc(2027, 1, 1));
    expect(first.map((x) => x.start.day), [3, 4, 1, 7],
        reason: 'the first two weekend days of October and November');
  });

  test('a rule built to be expensive ends, and quickly', () {
    // Every day of every month of every year since year one: the cap on
    // dates looked at stops it long before the window.
    final sw = Stopwatch()..start();
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:00010101T090000Z',
      'DTEND:00010101T100000Z',
      'RRULE:FREQ=YEARLY;BYMONTH=1,2,3,4,5,6,7,8,9,10,11,12;'
          'BYDAY=MO,TU,WE,TH,FR,SA,SU',
    ])));
    expect(w, isEmpty);
    expect(sw.elapsed, lessThan(const Duration(seconds: 5)));
  });

  test('a rule that never yields still ends', () {
    final w = busy(cal(ev([
      'UID:1',
      'DTSTART:20260101T060000Z',
      'DTEND:20260101T063000Z',
      'RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30',
    ])));
    expect(w, isEmpty);
  });

  group('the Calendar feed connector', () {
    final feed = cal(ev([
      'UID:1',
      'DTSTART:20261027T090000Z',
      'DTEND:20261027T100000Z',
      'SUMMARY:Dentist',
    ]));

    test('webcal is https; http is refused', () {
      expect(
          CalendarFeedConnector.validUrl('webcal://example.com/a.ics'), isTrue);
      expect(
          CalendarFeedConnector.validUrl('http://example.com/a.ics'), isFalse);
      expect(CalendarFeedConnector.validUrl('not a url'), isFalse);
    });

    // kahf-connect#16: the Kotlin SDK refuses these too; one shared rule keeps
    // the two SDKs' verdicts identical.
    test(
        'an address with a backslash, two @, or characters outside RFC 3986 '
        'is refused', () {
      for (final bad in [
        r'https://evil.example\@good.example/a.ics',
        r'https://evil.example\good.example/a.ics',
        'https://a@b@good.example/a.ics',
        'https://good.example /a.ics',
        'https://good.example\t.evil.example/a.ics',
        'https://good.example\n.evil.example/a.ics',
        'https://g\u00f6\u00f6d.example/a.ics',
        'https://%65vil.example/a.ics',
        'https:/good.example/a.ics',
        'https:good.example/a.ics',
        '//good.example/a.ics',
        'good.example/a.ics',
        r'webcal://evil.example\@good.example/a.ics',
      ]) {
        expect(CalendarFeedConnector.validUrl(bad), isFalse, reason: bad);
      }
      expect(CalendarFeedConnector.validUrl('https://xn--gd-sna.example/a.ics'),
          isTrue);
      expect(
          CalendarFeedConnector.validUrl(
              'https://user:pa%40ss@cal.example.com/a.ics'),
          isTrue);
    });

    test('answers time.busy through the host, labels per grant', () async {
      var fetched = 0;
      final c =
          CalendarFeedConnector({'url': 'webcal://cal.example.com/me.ics'},
              client: MockClient((r) async {
        fetched++;
        expect(r.url.scheme, 'https');
        return http.Response(feed, 200);
      }));
      final host = ConnectorHost(
          connectors: [c],
          grants: [Grant(CalendarFeedConnector.id, 'time.busy', at: from)],
          audit: MemoryAuditSink());
      final r = await host.busy(from, to);
      expect(r.items.single.start, DateTime.utc(2026, 10, 27, 9));
      expect(r.items.single.label, isNull);
      await host.busy(from, to);
      expect(fetched, 1, reason: 'cached for a while');
    });

    test('an address that is not a calendar is unavailable, not a crash',
        () async {
      final c = CalendarFeedConnector({'url': 'https://example.com/x'},
          client: MockClient((_) async => http.Response('<html>', 200)));
      final host = ConnectorHost(
          connectors: [c],
          grants: [Grant(CalendarFeedConnector.id, 'time.busy', at: from)],
          audit: MemoryAuditSink());
      final r = await host.busy(from, to);
      expect(r.sources.single.outcome, Outcome.unavailable);
    });
  });
}

package com.kahflabs.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * A 1:1 port of the parser cases in `dart/test/ics_test.dart`, case for case
 * (the Calendar feed connector cases there belong to kahf-connect#6), plus
 * clock-change days whose expected values were read off the Dart parser.
 * Gradle runs this class again with the device in UTC, `America/New_York` and `Europe/Berlin`.
 */
class IcsTest {
    private val from = utc(2026, 10, 26)
    private val to = utc(2026, 11, 2)

    private fun busy(text: String, f: Instant = from, t: Instant = to) = IcsCalendar.parse(text).busy(f, t)

    private val BusyWindow.utcStart get() = start.toLocalDateTime(TimeZone.UTC)

    @Test
    fun aUtcEventWithItsTitle() {
        val w = busy(cal(ev("UID:1", "DTSTART:20261027T090000Z", "DTEND:20261027T100000Z", "SUMMARY:Board\\, quarterly")))
        assertEquals(1, w.size)
        assertEquals(utc(2026, 10, 27, 9), w.single().start)
        assertEquals(utc(2026, 10, 27, 10), w.single().end)
        assertEquals("Board, quarterly", w.single().label)
    }

    @Test
    fun aTimeZoneByIanaName() {
        // Riyadh is +03:00.
        val w = busy(cal(ev("UID:1", "DTSTART;TZID=Asia/Riyadh:20261027T120000", "DTEND;TZID=Asia/Riyadh:20261027T130000")))
        assertEquals(utc(2026, 10, 27, 9), w.single().start)
    }

    @Test
    fun aTimeZoneNamedTheOutlookWayFallsBackToItsVtimezone() {
        val w = busy(
            cal(
                ev("UID:1", "DTSTART;TZID=Arab Standard Time:20261027T120000", "DTEND;TZID=Arab Standard Time:20261027T130000"),
                extra = listOf(
                    "BEGIN:VTIMEZONE", "TZID:Arab Standard Time", "BEGIN:STANDARD", "DTSTART:16010101T000000",
                    "TZOFFSETFROM:+0300", "TZOFFSETTO:+0300", "END:STANDARD", "END:VTIMEZONE",
                ).joinToString("\r\n"),
            ),
        )
        assertEquals(utc(2026, 10, 27, 9), w.single().start)
    }

    @Test
    fun anAllDayEventCoversItsWholeDay() {
        val w = busy(cal(ev("UID:1", "DTSTART;VALUE=DATE:20261028", "DTEND;VALUE=DATE:20261029")))
        assertTrue(w.single().allDay)
        assertEquals(1.days, w.single().end - w.single().start)
    }

    @Test
    fun durationInsteadOfDtend() {
        val w = busy(cal(ev("UID:1", "DTSTART:20261027T090000Z", "DURATION:PT1H30M")))
        assertEquals(utc(2026, 10, 27, 10, 30), w.single().end)
    }

    @Test
    fun cancelledAndFreeEventsAreNotBusy() {
        val w = busy(
            cal(
                listOf(
                    ev("UID:1", "DTSTART:20261027T090000Z", "DTEND:20261027T100000Z", "STATUS:CANCELLED"),
                    ev("UID:2", "DTSTART:20261027T110000Z", "DTEND:20261027T120000Z", "TRANSP:TRANSPARENT"),
                    ev("UID:3", "DTSTART:20261027T130000Z", "DTEND:20261027T140000Z"),
                ).joinToString("\r\n"),
            ),
        )
        assertEquals(listOf(13), w.map { it.utcStart.hour })
    }

    @Test
    fun foldedLinesAreUnfolded() {
        val w = busy(cal(ev("UID:1", "DTSTART:20261027T090000Z", "DTEND:20261027T100000Z", "SUMMARY:A very long", "  title")))
        assertEquals("A very long title", w.single().label)
    }

    @Test
    fun weeklyOnTwoDaysEveryWeekSinceLastYear() {
        val w = busy(
            cal(ev("UID:1", "DTSTART:20250106T070000Z", "DTEND:20250106T080000Z", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE")),
        )
        // 26 Oct 2026 is a Monday: Mon 26, Wed 28 in the window.
        assertEquals(listOf(26, 28), w.map { it.utcStart.day })
    }

    @Test
    fun dailyWithCountStopsAfterCount() {
        val w = busy(cal(ev("UID:1", "DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=DAILY;COUNT=3")))
        assertEquals(3, w.size)
    }

    @Test
    fun untilEndsTheRule() {
        val w = busy(
            cal(ev("UID:1", "DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=DAILY;UNTIL=20261028T235959Z")),
        )
        assertEquals(listOf(26, 27, 28), w.map { it.utcStart.day })
    }

    @Test
    fun exdateTakesOneOccurrenceOut() {
        val w = busy(
            cal(
                ev(
                    "UID:1", "DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=DAILY;COUNT=4",
                    "EXDATE:20261027T060000Z",
                ),
            ),
        )
        assertEquals(listOf(26, 28, 29), w.map { it.utcStart.day })
    }

    @Test
    fun aMovedInstanceReplacesItsOccurrence() {
        val w = busy(
            cal(
                listOf(
                    ev("UID:standup", "DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=DAILY;COUNT=3"),
                    ev("UID:standup", "RECURRENCE-ID:20261027T060000Z", "DTSTART:20261027T150000Z", "DTEND:20261027T153000Z"),
                ).joinToString("\r\n"),
            ),
        )
        assertEquals(listOf("26:6", "27:15", "28:6"), w.map { "${it.utcStart.day}:${it.utcStart.hour}" })
    }

    @Test
    fun monthlyOnTheLastFriday() {
        val w = busy(
            cal(ev("UID:1", "DTSTART:20260130T100000Z", "DTEND:20260130T110000Z", "RRULE:FREQ=MONTHLY;BYDAY=-1FR")),
            f = utc(2026, 10, 1),
            t = utc(2026, 12, 1),
        )
        assertEquals(listOf(30, 27), w.map { it.utcStart.day }) // Oct 30, Nov 27
    }

    @Test
    fun aRecurringEventInAZoneKeepsItsWallTimeAcrossDst() {
        // 09:00 New York every Monday: 13:00Z in summer time, 14:00Z after
        // the change on 1 Nov 2026 (19 and 26 Oct, 2 and 9 Nov).
        val w = busy(
            cal(
                ev(
                    "UID:1", "DTSTART;TZID=America/New_York:20261019T090000", "DTEND;TZID=America/New_York:20261019T100000",
                    "RRULE:FREQ=WEEKLY",
                ),
            ),
            f = utc(2026, 10, 19),
            t = utc(2026, 11, 10),
        )
        assertEquals(listOf(13, 13, 14, 14), w.map { it.utcStart.hour })
    }

    @Test
    fun bySetPosTheLastWeekdayOfEachMonthAsGoogleWritesIt() {
        val w = busy(
            cal(
                ev(
                    "UID:1", "DTSTART:20260930T150000Z", "DTEND:20260930T160000Z",
                    "RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
                ),
            ),
            f = utc(2026, 10, 1),
            t = utc(2027, 1, 1),
        )
        assertEquals(
            listOf(
                utc(2026, 10, 30, 15), // Friday
                utc(2026, 11, 30, 15), // Monday
                utc(2026, 12, 31, 15), // Thursday
            ),
            w.map { it.start },
        )
        val first = busy(
            cal(
                ev(
                    "UID:2", "DTSTART:20261001T090000Z", "DTEND:20261001T100000Z",
                    "RRULE:FREQ=MONTHLY;BYDAY=SA,SU;BYSETPOS=1,2;COUNT=4",
                ),
            ),
            f = utc(2026, 10, 1),
            t = utc(2027, 1, 1),
        )
        assertEquals(listOf(3, 4, 1, 7), first.map { it.utcStart.day }, "the first two weekend days of October and November")
    }

    @Test
    fun aRuleBuiltToBeExpensiveEndsAndQuickly() {
        // Every day of every month of every year since year one: the cap on
        // dates looked at stops it long before the window.
        val mark = TimeSource.Monotonic.markNow()
        val w = busy(
            cal(
                ev(
                    "UID:1", "DTSTART:00010101T090000Z", "DTEND:00010101T100000Z",
                    "RRULE:FREQ=YEARLY;BYMONTH=1,2,3,4,5,6,7,8,9,10,11,12;BYDAY=MO,TU,WE,TH,FR,SA,SU",
                ),
            ),
        )
        assertTrue(w.isEmpty())
        assertTrue(mark.elapsedNow() < 5.seconds)
    }

    @Test
    fun aRuleThatNeverYieldsStillEnds() {
        val w = busy(
            cal(ev("UID:1", "DTSTART:20260101T060000Z", "DTEND:20260101T063000Z", "RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30")),
        )
        assertTrue(w.isEmpty())
    }

    // Beyond the Dart file: clock-change days. Every expected value below was
    // printed by the Dart parser (UTC, America/New_York and Europe/Berlin as
    // the device zone) and copied here, so the two SDKs agree on them.

    private fun span(start: String, end: String): String {
        val w = busy(cal(ev("UID:1", "DTSTART$start", "DTEND$end")), utc(2000, 1, 1), utc(2030, 1, 1))
        return w.joinToString(" ") { "${it.start}..${it.end}" }
    }

    @Test
    fun aNamedZoneOnClockChangeDaysReadsAsDartReadsIt() {
        // A wall time in the spring gap moves on by the gap; in the autumn
        // overlap New York takes the first instant and Berlin and London the
        // second, as Dart's timezone package does. The device zone plays no part.
        assertEquals(
            "2026-03-08T07:30:00Z..2026-03-08T08:00:00Z",
            span(";TZID=America/New_York:20260308T023000", ";TZID=America/New_York:20260308T040000"),
        )
        assertEquals(
            "2026-11-01T05:30:00Z..2026-11-01T09:00:00Z",
            span(";TZID=America/New_York:20261101T013000", ";TZID=America/New_York:20261101T040000"),
        )
        assertEquals(
            "2026-03-29T01:30:00Z..2026-03-29T02:00:00Z",
            span(";TZID=Europe/Berlin:20260329T023000", ";TZID=Europe/Berlin:20260329T040000"),
        )
        assertEquals(
            "2026-10-25T01:30:00Z..2026-10-25T03:00:00Z",
            span(";TZID=Europe/Berlin:20261025T023000", ";TZID=Europe/Berlin:20261025T040000"),
        )
        assertEquals(
            "2026-10-25T01:30:00Z..2026-10-25T04:00:00Z",
            span(";TZID=Europe/London:20261025T013000", ";TZID=Europe/London:20261025T040000"),
        )
    }

    /** Recurrence edges, each with the windows the Dart parser drew for it (any device zone). */
    private val recurrence = listOf(
        // After a clock change, west of UTC: the new offset.
        listOf("DTSTART;TZID=America/New_York:20260308T033000", "DTEND;TZID=America/New_York:20260308T050000") to
            "2026-03-08T07:30:00Z..2026-03-08T09:00:00Z",
        listOf("DTSTART;TZID=America/New_York:20261101T050000", "DTEND;TZID=America/New_York:20261101T060000") to
            "2026-11-01T10:00:00Z..2026-11-01T11:00:00Z",
        // UNTIL in the start's zone, an EXDATE with TZID, an instance in the overlap.
        listOf(
            "DTSTART;TZID=Europe/Berlin:20261011T023000", "DTEND;TZID=Europe/Berlin:20261011T033000",
            "RRULE:FREQ=WEEKLY;UNTIL=20261101T023000", "EXDATE;TZID=Europe/Berlin:20261018T023000",
        ) to "2026-10-11T00:30:00Z..2026-10-11T01:30:00Z 2026-10-25T01:30:00Z..2026-10-25T02:30:00Z " +
            "2026-11-01T01:30:00Z..2026-11-01T02:30:00Z",
        // A floating EXDATE does not take out a UTC occurrence (Dart's DateTime ==).
        listOf("DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=DAILY;COUNT=3", "EXDATE:20261027T060000") to
            "2026-10-26T06:00:00Z..2026-10-26T06:30:00Z 2026-10-27T06:00:00Z..2026-10-27T06:30:00Z " +
            "2026-10-28T06:00:00Z..2026-10-28T06:30:00Z",
        // RDATE is not read, as in Dart.
        listOf("DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=DAILY;COUNT=2", "RDATE:20261030T060000Z") to
            "2026-10-26T06:00:00Z..2026-10-26T06:30:00Z 2026-10-27T06:00:00Z..2026-10-27T06:30:00Z",
        // BYMONTH=13 rolls over into January, as Dart's DateTime does.
        listOf("DTSTART:20260115T060000Z", "DTEND:20260115T063000Z", "RRULE:FREQ=YEARLY;BYMONTH=13;COUNT=2") to
            "2027-01-15T06:00:00Z..2027-01-15T06:30:00Z",
        listOf("DTSTART:20260131T060000Z", "DTEND:20260131T063000Z", "RRULE:FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=-1;COUNT=4") to
            "2026-01-31T06:00:00Z..2026-01-31T06:30:00Z 2026-03-31T06:00:00Z..2026-03-31T06:30:00Z " +
            "2026-05-31T06:00:00Z..2026-05-31T06:30:00Z 2026-07-31T06:00:00Z..2026-07-31T06:30:00Z",
        // The 31st: months without one are skipped.
        listOf("DTSTART:20260131T060000Z", "DTEND:20260131T063000Z", "RRULE:FREQ=MONTHLY;COUNT=4") to
            "2026-01-31T06:00:00Z..2026-01-31T06:30:00Z 2026-03-31T06:00:00Z..2026-03-31T06:30:00Z " +
            "2026-05-31T06:00:00Z..2026-05-31T06:30:00Z 2026-07-31T06:00:00Z..2026-07-31T06:30:00Z",
        // A repeated BYDAY entry is drawn twice.
        listOf("DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=WEEKLY;BYDAY=MO,MO,FR;COUNT=3") to
            "2026-10-26T06:00:00Z..2026-10-26T06:30:00Z 2026-10-26T06:00:00Z..2026-10-26T06:30:00Z " +
            "2026-10-30T06:00:00Z..2026-10-30T06:30:00Z",
        listOf("DTSTART:20260310T060000Z", "DTEND:20260310T063000Z", "RRULE:FREQ=YEARLY;BYMONTH=3,6;BYDAY=2TU;COUNT=3") to
            "2026-03-10T06:00:00Z..2026-03-10T06:30:00Z 2026-06-09T06:00:00Z..2026-06-09T06:30:00Z " +
            "2027-03-09T06:00:00Z..2027-03-09T06:30:00Z",
        listOf("DTSTART:20261026T060000Z", "DTEND:20261026T063000Z", "RRULE:FREQ=DAILY;BYDAY=TU,TH;BYMONTH=10") to
            listOf(
                "2026-10-27", "2026-10-29", "2027-10-05", "2027-10-07", "2027-10-12", "2027-10-14", "2027-10-19",
                "2027-10-21", "2027-10-26", "2027-10-28",
            ).joinToString(" ") { "${it}T06:00:00Z..${it}T06:30:00Z" },
    )

    @Test
    fun malformedRulesReadAsDartReadsThem() {
        fun daily(rule: String) = busy(
            cal(ev("UID:1", "DTSTART:20260101T060000Z", "DTEND:20260101T063000Z", "RRULE:FREQ=DAILY;$rule")),
            utc(2026, 1, 1), utc(2028, 1, 1),
        ).map { it.utcStart.day }
        // Dart's int.tryParse reads hex, signs and surrounding spaces.
        assertEquals(listOf(1, 3, 5), daily("INTERVAL=0x2;COUNT= +3 "))
        assertEquals(emptyList(), daily("INTERVAL=0x2;COUNT=-0X3"))
        // "UT" is not in Dart's IANA database, so the time is floating.
        val ut = busy(cal(ev("UID:1", "DTSTART;TZID=UT:20260101T100000", "DTEND;TZID=UT:20260101T110000")), utc(2025, 1, 1), utc(2027, 1, 1))
        val floating = busy(cal(ev("UID:1", "DTSTART:20260101T100000", "DTEND:20260101T110000")), utc(2025, 1, 1), utc(2027, 1, 1))
        assertEquals(floating, ut)
        // A rule that walks past Dart's last year throws, as Dart's ArgumentError.
        assertFailsWith<IllegalArgumentException> {
            busy(cal(ev("UID:1", "DTSTART:20260101T060000Z", "DTEND:20260101T063000Z", "RRULE:FREQ=YEARLY;INTERVAL=10;BYMONTH=2;BYMONTHDAY=30")))
        }
    }

    @Test
    fun recurrenceEdgesDrawWhatDartDraws() {
        for ((lines, expected) in recurrence) {
            val w = busy(cal(ev("UID:1", *lines.toTypedArray())), utc(2026, 1, 1), utc(2028, 1, 1))
            assertEquals(expected, w.joinToString(" ") { "${it.start}..${it.end}" }, lines.joinToString(" | "))
        }
    }

    /** Floating and all-day spans, by device zone, as the Dart parser printed them. */
    private val floating = mapOf(
        "UTC" to listOf(
            "2026-03-08T02:30:00Z..2026-03-08T04:00:00Z",
            "2026-11-01T01:30:00Z..2026-11-01T04:00:00Z",
            "2026-03-29T02:30:00Z..2026-03-29T04:00:00Z",
            "2026-10-25T02:30:00Z..2026-10-25T04:00:00Z",
            "2026-03-28T00:00:00Z..2026-03-30T00:00:00Z",
            "2026-03-07T00:00:00Z..2026-03-09T00:00:00Z",
        ),
        "America/New_York" to listOf(
            "2026-03-08T07:30:00Z..2026-03-08T08:00:00Z",
            "2026-11-01T05:30:00Z..2026-11-01T09:00:00Z",
            "2026-03-29T06:30:00Z..2026-03-29T08:00:00Z",
            "2026-10-25T06:30:00Z..2026-10-25T08:00:00Z",
            "2026-03-28T04:00:00Z..2026-03-30T04:00:00Z",
            // Dart's whole-day count of a 47-hour span: one day, not two.
            "2026-03-07T05:00:00Z..2026-03-08T05:00:00Z",
        ),
        "Europe/Berlin" to listOf(
            "2026-03-08T01:30:00Z..2026-03-08T03:00:00Z",
            "2026-11-01T00:30:00Z..2026-11-01T03:00:00Z",
            "2026-03-29T01:30:00Z..2026-03-29T02:00:00Z",
            "2026-10-25T00:30:00Z..2026-10-25T03:00:00Z",
            "2026-03-27T23:00:00Z..2026-03-28T23:00:00Z",
            "2026-03-06T23:00:00Z..2026-03-08T23:00:00Z",
        ),
    )

    @Test
    fun floatingAndAllDayTimesOnClockChangeDaysReadAsDartReadsThem() {
        val zone = TimeZone.currentSystemDefault().id
        // Machines in other zones have no Dart reading to compare with.
        val expected = floating[if (zone == "Etc/UTC" || zone == "Z") "UTC" else zone] ?: return
        assertEquals(
            expected,
            listOf(
                span(":20260308T023000", ":20260308T040000"),
                span(":20261101T013000", ":20261101T040000"),
                span(":20260329T023000", ":20260329T040000"),
                span(":20261025T023000", ":20261025T040000"),
                span(";VALUE=DATE:20260328", ";VALUE=DATE:20260330"),
                span(";VALUE=DATE:20260307", ";VALUE=DATE:20260309"),
            ),
        )
    }
}

private fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Instant =
    LocalDateTime(y, mo, d, h, mi).toInstant(TimeZone.UTC)

private fun cal(events: String, extra: String = ""): String =
    listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//EN", extra, events, "END:VCALENDAR")
        .filter { it.isNotEmpty() }
        .joinToString("\r\n")

private fun ev(vararg lines: String): String = (listOf("BEGIN:VEVENT") + lines + "END:VEVENT").joinToString("\r\n")

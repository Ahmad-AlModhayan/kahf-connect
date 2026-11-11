package com.kahflabs.connect

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

// Reading iCalendar (RFC 5545) for busy times: events, their time zones,
// their recurrence, exceptions and moved instances. Only what `time.busy`
// needs: when, how long, and (if the grant keeps it) the title.
// Port of `dart/lib/src/ics.dart`, which is the reference: every quirk below
// is Dart's, kept so both SDKs draw the same busy windows from the same feed.
//
// Supported: DTSTART/DTEND/DURATION in UTC, with TZID (IANA names, and the
// calendar's own VTIMEZONE offset when the name is not IANA), floating, and
// all-day; RRULE with FREQ DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL,
// BYDAY (with ordinals for monthly and yearly), BYMONTHDAY, BYMONTH,
// BYSETPOS; EXDATE; RECURRENCE-ID overrides; STATUS:CANCELLED and
// TRANSP:TRANSPARENT (free time) are left out.

/** One content line: `NAME;PARAM=V:VALUE`. */
private class Prop(val name: String, val params: Map<String, String>, val value: String)

private class Event {
    val props = mutableMapOf<String, MutableList<Prop>>()
    fun one(n: String): Prop? = props[n]?.first()
    fun all(n: String): List<Prop> = props[n].orEmpty()
}

/**
 * A moment as Dart's `DateTime` holds it. Dart's `==` (and so its sets and
 * maps) tells a local `DateTime` from a UTC one at the same instant, so an
 * EXDATE or RECURRENCE-ID matches an occurrence only when both agree.
 */
private data class Stamp(val at: Instant, val utc: Boolean)

/** A wall-clock time in a zone (or UTC, or floating local). Fields may overflow, as in Dart. */
private class When(
    val y: Int, val mo: Int, val d: Int, val h: Int, val mi: Int, val s: Int,
    /** Null: floating (device local). `UTC`: UTC. Else an IANA name, or `fixed:<minutes>` from a VTIMEZONE. */
    val zone: String?,
    val date: Boolean = false,
) {
    fun at(y: Int, mo: Int, d: Int) = When(y, mo, d, h, mi, s, zone, date)

    fun stamp(): Stamp {
        val wall = dartWall(y, mo, d, h, mi, s)
        return when {
            // Dart's local DateTime takes the offset in force before a clock
            // change: on in a gap, the first instant in an overlap.
            zone == null -> Stamp(wall.toInstant(TimeZone.currentSystemDefault()), utc = false)
            zone == "UTC" -> Stamp(wall.toInstant(TimeZone.UTC), utc = true)
            zone.startsWith("fixed:") ->
                Stamp(wall.toInstant(TimeZone.UTC) - zone.substring(6).toInt().minutes, utc = true)
            else -> Stamp(tzInstant(wall, ianaZone(zone)!!), utc = true)
        }
    }

    fun instant(): Instant = stamp().at
}

/**
 * A parsed iCalendar feed. `IcsCalendar` itself (its companion) is a
 * [FeedParser], and the default parser of [CalendarFeedConnector].
 */
class IcsCalendar private constructor(
    private val events: List<Event>,
    /** TZID → offset in minutes, from the calendar's own VTIMEZONE blocks (their STANDARD offset). */
    private val fixedZones: Map<String, Int>,
) : FeedCalendar {
    val eventCount: Int get() = events.size

    /** Busy windows meeting [from, to), sorted by start, with their titles. */
    override fun busy(from: Instant, to: Instant): List<BusyWindow> = busy(from, to, labels = true)

    /** Busy windows meeting [from, to), sorted by start. [labels] keeps SUMMARY. */
    fun busy(from: Instant, to: Instant, labels: Boolean): List<BusyWindow> {
        val out = mutableListOf<BusyWindow>()
        // Moved or changed instances: UID → original start → the override.
        val overrides = mutableMapOf<String, MutableMap<Stamp, Event>>()
        for (e in events) {
            val rid = whenOf(e.one("RECURRENCE-ID"))
            val uid = e.one("UID")?.value
            if (rid != null && uid != null) overrides.getOrPut(uid) { mutableMapOf() }[rid.stamp()] = e
        }
        for (e in events) {
            val rrule = e.one("RRULE")
            if (e.one("RECURRENCE-ID") != null || rrule == null) {
                single(e, from, to, labels, out)
            } else {
                recurring(e, rrule.value, from, to, labels, overrides[e.one("UID")?.value].orEmpty(), out)
            }
        }
        out.sortBy { it.start }
        return out
    }

    private fun zone(tzid: String?): String? {
        if (tzid == null) return null
        val name = tzid.removePrefix("/")
        if (ianaZone(name) != null) return name
        return fixedZones[tzid]?.let { "fixed:$it" }
    }

    private fun whenOf(p: Prop?): When? = p?.let { parseWhen(it.value.trim(), it.params) }

    private fun parseWhen(v: String, params: Map<String, String>): When? {
        val date = DATE.matchEntire(v)
        if (date != null || params["VALUE"] == "DATE") {
            val g = (date ?: DATE_PREFIX.find(v) ?: return null).groupValues
            return When(g[1].toInt(), g[2].toInt(), g[3].toInt(), 0, 0, 0, zone = null, date = true)
        }
        val g = DATE_TIME.matchEntire(v)?.groupValues ?: return null
        val zone = if (g[7] == "Z") "UTC" else zone(params["TZID"])
        return When(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), zone)
    }

    private fun skipped(e: Event) =
        e.one("STATUS")?.value?.uppercase() == "CANCELLED" || e.one("TRANSP")?.value?.uppercase() == "TRANSPARENT"

    /** Start and length of [e] (its first occurrence). */
    private fun span(e: Event): Pair<When, Duration>? {
        val start = whenOf(e.one("DTSTART")) ?: return null
        val end = whenOf(e.one("DTEND"))
        val length = if (end != null) {
            end.instant() - start.instant()
        } else {
            duration(e.one("DURATION")?.value) ?: if (start.date) 1.days else Duration.ZERO
        }
        return start to length
    }

    private fun single(e: Event, from: Instant, to: Instant, labels: Boolean, out: MutableList<BusyWindow>) {
        if (skipped(e)) return
        val (start, length) = span(e) ?: return
        add(e, start, length, from, to, labels, out)
    }

    private fun add(
        e: Event, start: When, length: Duration, from: Instant, to: Instant, labels: Boolean,
        out: MutableList<BusyWindow>,
    ) {
        if (length <= Duration.ZERO) return
        val s = start.instant()
        // Whole days, counted Dart's way (truncated), so a 47-hour span
        // across a spring change in the device zone is one day.
        val end = if (start.date) start.at(start.y, start.mo, start.d + length.inWholeDays.toInt()).instant() else s + length
        val w = BusyWindow(s, end, label = if (labels) text(e.one("SUMMARY")?.value) else null, allDay = start.date)
        if (w.overlaps(from, to)) out.add(w)
    }

    private fun recurring(
        e: Event, rule: String, from: Instant, to: Instant, labels: Boolean, moved: Map<Stamp, Event>,
        out: MutableList<BusyWindow>,
    ) {
        val (start, length) = span(e) ?: return
        val r = mutableMapOf<String, String>()
        for (part in rule.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) r[part.substring(0, eq).uppercase()] = part.substring(eq + 1)
        }
        val freq = r["FREQ"]
        val interval = dartInt(r["INTERVAL"]) ?: 1L
        val count = dartInt(r["COUNT"])
        val until = r["UNTIL"]?.let { u ->
            val params = if (start.zone == null || start.zone == "UTC") emptyMap() else mapOf("TZID" to start.zone)
            parseWhen(u, params)?.instant()
        }
        val byDay = r["BYDAY"]?.split(',').orEmpty()
        val byMonthDay = r["BYMONTHDAY"]?.split(',').orEmpty().mapNotNull(::dartInt)
        val byMonth = r["BYMONTH"]?.split(',').orEmpty().mapNotNull(::dartInt).sorted()
        // BYSETPOS picks from each period's dates ("-1" with BYDAY=MO..FR is
        // the last weekday of the month). Only rules that carry it are touched.
        val bySetPos = r["BYSETPOS"]?.split(',').orEmpty().mapNotNull(::dartInt).filter { it != 0L }
        val exdates = buildSet {
            for (p in e.all("EXDATE")) for (v in p.value.split(',')) parseWhen(v.trim(), p.params)?.let { add(it.stamp()) }
        }
        val cancelled = skipped(e)
        val first = start.instant()

        var produced = 0L
        var guard = 0
        // A ceiling on the dates looked at, as well as the periods walked: a
        // rule built to be expensive (every day of every month of every year
        // since year one) ends instead of freezing whoever asked. Bounded too:
        // a rule that never yields (BYMONTH=2 with BYMONTHDAY=30) still ends.
        var looked = 0
        var period = 0L
        while (guard < 50_000) {
            guard++
            var dates = periodDates(freq, start, period, byDay, byMonthDay, byMonth) ?: return
            if (bySetPos.isNotEmpty()) dates = setPos(dates, bySetPos)
            looked += dates.size
            if (looked > 50_000) return
            for (d in dates) {
                val occ = start.at(d.year, d.month.number, d.day)
                val at = occ.stamp()
                if (at.at < first) continue
                if (until != null && at.at > until) return
                if (count != null && produced >= count) return
                produced++
                if (at.at >= to) return
                if (at in exdates) continue
                if (at in moved) continue // drawn by single()
                if (!cancelled) add(e, occ, length, from, to, labels, out)
            }
            period += interval
        }
    }

    companion object : FeedParser {
        private val DATE = Regex("(\\d{4})(\\d{2})(\\d{2})")
        private val DATE_PREFIX = Regex("^(\\d{4})(\\d{2})(\\d{2})")
        private val DATE_TIME = Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(Z?)")
        private val OFFSET = Regex("^([+-])(\\d{2})(\\d{2})")
        private val DURATION = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
        private val LINE_BREAK = Regex("\r?\n")
        private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

        /**
         * Reads [text]; a line it cannot read is skipped. [busy] may still throw
         * on a malformed RRULE (a BYDAY entry shorter than two letters, a date
         * past Dart's range), as Dart's does.
         */
        override fun parse(text: String): IcsCalendar {
            val lines = mutableListOf<String>()
            for (raw in text.split(LINE_BREAK)) {
                if ((raw.startsWith(' ') || raw.startsWith('\t')) && lines.isNotEmpty()) {
                    lines[lines.lastIndex] += raw.substring(1)
                } else if (raw.isNotEmpty()) {
                    lines.add(raw)
                }
            }
            val events = mutableListOf<Event>()
            val fixed = mutableMapOf<String, Int>()
            var current: Event? = null
            var tzid: String? = null
            var inStandard = false
            val stack = mutableListOf<String>()
            for (line in lines) {
                val p = prop(line) ?: continue
                if (p.name == "BEGIN") {
                    val what = p.value.uppercase()
                    stack.add(what)
                    if (what == "VEVENT") current = Event()
                    if (what == "STANDARD") inStandard = true
                    continue
                }
                if (p.name == "END") {
                    val what = p.value.uppercase()
                    stack.removeLastOrNull()
                    if (what == "VEVENT" && current != null) {
                        events.add(current)
                        current = null
                    }
                    if (what == "STANDARD") inStandard = false
                    if (what == "VTIMEZONE") tzid = null
                    continue
                }
                if (current != null && stack.lastOrNull() == "VEVENT") {
                    current.props.getOrPut(p.name) { mutableListOf() }.add(p)
                } else if ("VTIMEZONE" in stack) {
                    if (p.name == "TZID") tzid = p.value
                    val id = tzid
                    if (inStandard && p.name == "TZOFFSETTO" && id != null) {
                        OFFSET.find(p.value)?.groupValues?.let { g ->
                            val v = g[2].toInt() * 60 + g[3].toInt()
                            fixed.getOrPut(id) { if (g[1] == "-") -v else v }
                        }
                    }
                }
            }
            return IcsCalendar(events, fixed)
        }

        private fun prop(line: String): Prop? {
            // The value starts at the first colon outside a quoted parameter.
            var quoted = false
            var colon = -1
            for (i in line.indices) {
                val ch = line[i]
                if (ch == '"') quoted = !quoted
                if (ch == ':' && !quoted) {
                    colon = i
                    break
                }
            }
            if (colon < 0) return null
            val head = line.substring(0, colon).split(';')
            val params = mutableMapOf<String, String>()
            for (kv in head.drop(1)) {
                val eq = kv.indexOf('=')
                if (eq > 0) params[kv.substring(0, eq).uppercase()] = kv.substring(eq + 1).replace("\"", "")
            }
            return Prop(head.first().uppercase(), params, line.substring(colon + 1))
        }

        private fun duration(v: String?): Duration? {
            val g = v?.trim()?.let(DURATION::matchEntire)?.groupValues ?: return null
            fun n(i: Int) = g[i].toLongOrNull() ?: 0L
            val d = (n(2) * 7 + n(3)).days + n(4).hours + n(5).minutes + n(6).seconds
            return if (g[1] == "-") -d else d
        }

        private fun text(v: String?): String? {
            if (v.isNullOrEmpty()) return null
            val t = v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
            return if (t.length > 200) t.substring(0, 200) else t
        }

        /** 1 for `MO` … 7 for `SU`, from a BYDAY entry's last two letters; 0 when they name no day. */
        private fun weekday(b: String): Int = DAYS.indexOf(b.substring(b.length - 2)) + 1

        /** The [positions] (1-based, negative from the end) of one period's [dates], in order, each once. */
        private fun setPos(dates: List<LocalDate>, positions: List<Long>): List<LocalDate> {
            val sorted = dates.distinct().sorted()
            val out = mutableSetOf<LocalDate>()
            for (p in positions) {
                val i = if (p > 0) p - 1 else sorted.size + p
                if (i >= 0 && i < sorted.size) out.add(sorted[i.toInt()])
            }
            return out.sorted()
        }

        /** The dates of the [n]th period from [start], or null for a frequency this does not read. */
        private fun periodDates(
            freq: String?, start: When, n: Long, byDay: List<String>, byMonthDay: List<Long>, byMonth: List<Long>,
        ): List<LocalDate>? {
            val base = dartDate(start.y, start.mo, start.d)
            return when (freq) {
                "DAILY" -> {
                    val d = base.plus(n, DateTimeUnit.DAY)
                    when {
                        byMonth.isNotEmpty() && d.month.number.toLong() !in byMonth -> emptyList()
                        byDay.isNotEmpty() && byDay.none { weekday(it) == d.dayOfWeek.isoDayNumber } -> emptyList()
                        else -> listOf(d)
                    }
                }
                "WEEKLY" -> {
                    // The week (Monday first) containing start, n weeks on.
                    val week = base.minus(base.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY).plus(7 * n, DateTimeUnit.DAY)
                    val days = if (byDay.isEmpty()) {
                        listOf(base.dayOfWeek.isoDayNumber)
                    } else {
                        byDay.filter { it.substring(it.length - 2) in DAYS }.map(::weekday)
                    }
                    days.sorted().map { week.plus(it - 1, DateTimeUnit.DAY) }
                }
                "MONTHLY" -> {
                    // Dart's `~/` truncates and its `%` is never negative.
                    val months = start.mo - 1 + n
                    inMonth(start.y + months / 12, months.mod(12L) + 1, start.d, byDay, byMonthDay)
                }
                "YEARLY" -> {
                    val y = start.y + n
                    val months = byMonth.ifEmpty { listOf(start.mo.toLong()) }
                    months.flatMap { inMonth(y, it, start.d, byDay, byMonthDay) }
                }
                else -> null
            }
        }

        private fun inMonth(y: Long, m: Long, day: Int, byDay: List<String>, byMonthDay: List<Long>): List<LocalDate> {
            fun at(d: Long) = dartDate(y, m, d)
            val last = dartDate(y, m + 1, 0).day
            val out = mutableListOf<LocalDate>()
            if (byDay.isNotEmpty()) {
                for (b in byDay) {
                    val wd = weekday(b)
                    if (wd == 0) continue
                    val ord = if (b.length > 2) dartInt(b.substring(0, b.length - 2)) else null
                    val all = (1..last).filter { at(it.toLong()).dayOfWeek.isoDayNumber == wd }
                    if (ord == null) {
                        all.mapTo(out) { at(it.toLong()) }
                    } else {
                        val i = if (ord > 0) ord - 1 else all.size + ord
                        if (i >= 0 && i < all.size) out.add(at(all[i.toInt()].toLong()))
                    }
                }
            } else if (byMonthDay.isNotEmpty()) {
                for (md in byMonthDay) {
                    val d = if (md > 0) md else last + md + 1
                    if (d in 1L..last) out.add(at(d))
                }
            } else if (day <= last) {
                out.add(at(day.toLong()))
            }
            out.sort()
            return out
        }
    }
}

/** Dart's `int.tryParse`: surrounding whitespace, a leading `+` or `-`, and `0x` hex allowed. */
private fun dartInt(s: String?): Long? {
    val t = s?.trim() ?: return null
    val sign = t.take(1).takeIf { it == "+" || it == "-" }.orEmpty()
    val digits = t.substring(sign.length)
    if (!digits.startsWith("0x", ignoreCase = true)) return t.toLongOrNull()
    val hex = digits.substring(2)
    if (hex.isEmpty() || hex[0] == '+' || hex[0] == '-') return null
    return (sign + hex).toLongOrNull(16)
}

/**
 * Dart's `DateTime.utc(y, m, d)` as a date: a month or day out of range rolls
 * over, and a date beyond Dart's range (100,000,000 days either side of 1970)
 * throws, as Dart's `ArgumentError` does.
 */
private fun dartDate(y: Long, m: Long, d: Long): LocalDate {
    val m0 = m - 1
    val year = y + m0.floorDiv(12L)
    require(year in -300_000L..300_000L) { "year $year is out of range" }
    val date = LocalDate(year.toInt(), (m0.mod(12L) + 1).toInt(), 1).plus(d - 1, DateTimeUnit.DAY)
    require(date.toEpochDays() in -DART_DAYS..DART_DAYS) { "$date is out of range" }
    return date
}

private const val DART_DAYS = 100_000_000L

private fun dartDate(y: Int, m: Int, d: Int) = dartDate(y.toLong(), m.toLong(), d.toLong())

/** Dart's `DateTime.utc(y, mo, d, h, mi, s)` as a wall time, with the same roll-over. */
private fun dartWall(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): LocalDateTime {
    val seconds = h * 3_600L + mi * 60L + s
    val date = dartDate(y, mo, d).plus(seconds.floorDiv(86_400L), DateTimeUnit.DAY)
    return LocalDateTime(date, LocalTime.fromSecondOfDay(seconds.mod(86_400L).toInt()))
}

/** Offsets written as names (`UTC+3`, `+03:00`): java.time reads them, Dart's IANA database does not. */
private val OFFSET_NAME = Regex("Z|UT|[+-].*|(?:UTC|GMT|UT)[+-].*")

/** The IANA zone called [name], or null where Dart's `tz.getLocation` would throw. */
private fun ianaZone(name: String): TimeZone? {
    if (name.isEmpty() || OFFSET_NAME.matches(name)) return null
    return try {
        TimeZone.of(name)
    } catch (_: IllegalTimeZoneException) {
        null
    }
}

/**
 * `TZDateTime(zone, wall)` from Dart's timezone package, which the Dart SDK
 * uses for TZID times: take the offset in force at the wall time read as UTC;
 * if the instant that gives lies outside that offset's period, try the
 * neighbouring period. In a spring gap this moves on by the gap; in an autumn
 * overlap it can take either instant (New York the first, Berlin the second),
 * where kotlinx-datetime would always take the first.
 */
private fun tzInstant(wall: LocalDateTime, zone: TimeZone): Instant {
    val asUtc = wall.toInstant(UtcOffset.ZERO)
    val off1 = zone.offsetAt(asUtc).totalSeconds
    if (off1 == 0) return asUtc
    val guess = asUtc - off1.seconds
    val off2 = zone.offsetAt(guess).totalSeconds
    if (off2 == off1) return guess
    // Before the period (east of UTC): the period before's offset.
    if (off1 > 0) return asUtc - off2.seconds
    // After it (west of UTC): the next period's offset, if its instant lies in it.
    val next = asUtc - off2.seconds
    return if (zone.offsetAt(next).totalSeconds == off2) next else guess
}

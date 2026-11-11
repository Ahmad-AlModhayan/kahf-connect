package com.kahflabs.connect

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The built-in calendar-feed connector, through the host. Port of the
 * "Calendar feed connector" group in `dart/test/ics_test.dart`. The ICS
 * parsing itself is the parser's own tests; here a stand-in parser answers.
 */
class CalendarFeedTest {
    private val from = Instant.parse("2026-10-26T00:00:00Z")
    private val to = Instant.parse("2026-11-02T00:00:00Z")
    private val feed = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:1\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    /** Answers one 09:00–10:00 "Dentist" window for any feed, and counts what it parsed. */
    private class StandIn : FeedParser {
        val parsed = mutableListOf<String>()

        override fun parse(text: String): FeedCalendar {
            parsed += text
            return FeedCalendar { from, to ->
                listOf(BusyWindow(Instant.parse("2026-10-27T09:00:00Z"), Instant.parse("2026-10-27T10:00:00Z"), "Dentist"))
                    .filter { it.overlaps(from, to) }
            }
        }
    }

    private fun host(c: Connector, labels: Boolean = false) = ConnectorHost(
        listOf(c),
        listOf(Grant(CalendarFeedConnector.ID, "time.busy", labels = labels, at = from)),
        MemoryAuditSink(),
    )

    @Test
    fun webcalIsHttpsHttpIsRefused() {
        assertTrue(CalendarFeedConnector.validUrl("webcal://example.com/a.ics"))
        assertTrue(CalendarFeedConnector.validUrl("  https://calendar.google.com/calendar/ical/x/basic.ics "))
        assertFalse(CalendarFeedConnector.validUrl("http://example.com/a.ics"))
        assertFalse(CalendarFeedConnector.validUrl("not a url"))
        assertFalse(CalendarFeedConnector.validUrl("https:///no-host.ics"))
        assertFalse(CalendarFeedConnector.validUrl(""))
        assertTrue(CalendarFeedConnector.validUrl("HTTPS://Cal.Example.com/a.ics"), "the scheme is case-blind, as Uri's")
        assertTrue(CalendarFeedConnector.validUrl("https://me@cal.example.com:8443/a.ics"))
        assertFalse(CalendarFeedConnector.validUrl("https://cal.example.com:84x3/a.ics"), "a bad port is no address")
        assertFalse(CalendarFeedConnector.validUrl("WEBCAL://cal.example.com/a.ics"), "webcal:// is matched as written, as in Dart")
        assertFalse(CalendarFeedConnector.validUrl("ftp://cal.example.com/a.ics"))
    }

    // kahf-connect#16: the address is judged on the host the HTTP client will connect to.
    @Test
    fun anAddressTheParsersReadDifferentlyIsRefused() {
        for (bad in listOf(
            "https://evil.example\\@good.example/a.ics", // Ktor: evil.example; RFC 3986: good.example
            "https://evil.example\\good.example/a.ics",
            "https://a@b@good.example/a.ics",
            "https://good.example /a.ics",
            "https://good.example\t.evil.example/a.ics",
            "https://good.example\n.evil.example/a.ics",
            "https://g\u00f6\u00f6d.example/a.ics", // an IDN must arrive as punycode
            "https://%65vil.example/a.ics",
            "https:/good.example/a.ics",
            "https:good.example/a.ics",
            "//good.example/a.ics",
            "good.example/a.ics",
            "webcal://evil.example\\@good.example/a.ics",
        )) assertFalse(CalendarFeedConnector.validUrl(bad), bad)
        assertTrue(CalendarFeedConnector.validUrl("https://xn--gd-sna.example/a.ics"), "punycode is a plain host")
        assertTrue(CalendarFeedConnector.validUrl("https://user:pa%40ss@cal.example.com/a.ics"), "an @ in userinfo is %40")
    }

    @Test
    fun answersTimeBusyThroughTheHostLabelsPerGrant() = realTest {
        val requested = mutableListOf<String>()
        val parser = StandIn()
        var now = from
        val c = CalendarFeedConnector(
            mapOf("url" to "webcal://cal.example.com/me.ics"),
            parser,
            client = mockClient { r ->
                requested += "${r.url} ${r.headers[HttpHeaders.Accept]}"
                respond(feed, HttpStatusCode.OK)
            },
            clock = { now },
        )
        assertEquals(setOf("time.busy@1"), c.available())
        val r = host(c).busy(from, to)
        assertTrue(r.complete, r.sources.single().detail)
        assertEquals(Instant.parse("2026-10-27T09:00:00Z"), r.items.single().start)
        assertNull(r.items.single().label)
        assertEquals("Dentist", host(c, labels = true).busy(from, to).items.single().label)
        assertEquals(listOf("https://cal.example.com/me.ics text/calendar"), requested, "cached for a while")
        assertEquals(listOf(feed), parser.parsed)
        now += 16.minutes
        host(c).busy(from, to)
        assertEquals(2, requested.size, "fetched again once the cache is old")
    }

    @Test
    fun theSdksIcsParserIsTheDefault() = realTest {
        // The Dart group's feed, read by IcsCalendar with no parser passed.
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//EN", "BEGIN:VEVENT", "UID:1",
            "DTSTART:20261027T090000Z", "DTEND:20261027T100000Z", "SUMMARY:Dentist", "END:VEVENT", "END:VCALENDAR",
        ).joinToString("\r\n")
        val c = CalendarFeedConnector(
            mapOf("url" to "webcal://cal.example.com/me.ics"),
            client = mockClient { respond(ics, HttpStatusCode.OK) },
        )
        val r = host(c, labels = true).busy(from, to)
        assertTrue(r.complete, r.sources.single().detail)
        assertEquals(
            BusyWindow(Instant.parse("2026-10-27T09:00:00Z"), Instant.parse("2026-10-27T10:00:00Z"), "Dentist"),
            r.items.single(),
        )
    }

    @Test
    fun anAddressThatIsNotACalendarIsUnavailableNotACrash() = realTest {
        val c = CalendarFeedConnector(
            mapOf("url" to "https://example.com/x"),
            StandIn(),
            client = mockClient { respond("<html>", HttpStatusCode.OK) },
        )
        val r = host(c).busy(from, to)
        assertEquals(Outcome.Unavailable, r.sources.single().outcome)
        assertEquals("that address is not a calendar feed", r.sources.single().detail)
    }

    @Test
    fun aFeedThatAnswersAnErrorIsUnavailable() = realTest {
        val c = CalendarFeedConnector(
            mapOf("url" to "https://example.com/x.ics"),
            StandIn(),
            client = mockClient { respondError(HttpStatusCode.NotFound) },
        )
        assertEquals("the calendar answered 404", host(c).busy(from, to).sources.single().detail)
    }

    @Test
    fun noValidAddressServesNothing() = realTest {
        var asked = false
        val c = CalendarFeedConnector(
            mapOf("url" to "http://example.com/x.ics"),
            StandIn(),
            client = mockClient {
                asked = true
                respond(feed)
            },
        )
        assertEquals(emptySet(), c.available())
        val r = host(c).busy(from, to)
        assertEquals(Outcome.Unavailable, r.sources.single().outcome)
        assertFalse(asked, "an http address is never fetched")
    }

    @Test
    fun theManifestIsTheBuiltinOne() = realTest {
        val m = CalendarFeedConnector.definition
        assertEquals(CalendarFeedConnector.ID, m.id)
        assertEquals(HostKind.Builtin, m.hostKind)
        assertEquals("رابط التقويم", m.name.of("ar"))
        assertEquals(SettingKind.SecretUrl, m.settings.single().kind)
        assertTrue(m.settings.single().required)
        assertEquals(listOf("time.busy@1"), m.capabilities.map { it.key })
        val c = CalendarFeedConnector(emptyMap(), StandIn(), client = mockClient { respond("") })
        assertEquals(
            "context.read not served",
            runCatching { c.read() }.exceptionOrNull().let { (it as ConnectorUnavailable).reason },
        )
    }
}

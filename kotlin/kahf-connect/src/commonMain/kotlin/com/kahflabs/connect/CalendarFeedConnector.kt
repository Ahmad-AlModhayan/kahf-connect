package com.kahflabs.connect

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Calendar feed: busy times from any calendar's private iCalendar address
// (Google Calendar "Secret address in iCal format", Outlook "Publish a
// calendar → ICS", iCloud "Public calendar"), with no account sign-in.
// Built in to every product that uses this SDK.
// Port of `dart/lib/src/builtins/calendar_feed.dart`.

/** What the feed connector needs of a parsed iCalendar feed: its busy windows in [from, to), labels kept. */
fun interface FeedCalendar {
    fun busy(from: Instant, to: Instant): List<BusyWindow>
}

/** Reads a feed's text into a [FeedCalendar]. The SDK's ICS parser, [IcsCalendar], is the default; a test can pass its own. */
fun interface FeedParser {
    fun parse(text: String): FeedCalendar
}

/**
 * The built-in calendar-feed connector. [client] defaults to Ktor's platform
 * engine; [close] closes it either way, so give each connector its own.
 */
class CalendarFeedConnector(
    settings: Map<String, String>,
    private val parser: FeedParser = IcsCalendar,
    client: HttpClient? = null,
    private val clock: () -> Instant = { Clock.System.now() },
    val cacheFor: Duration = 15.minutes,
) : Connector() {
    override val manifest: Manifest get() = definition

    private val url: Url? = normalise(settings["url"] ?: "")
    private val http: HttpClient = client ?: HttpClient()
    private val lock = Mutex()
    private var calendar: FeedCalendar? = null
    private var fetchedAt: Instant? = null

    override suspend fun available(): Set<String> =
        if (url == null) emptySet() else definition.capabilities.map { it.key }.toSet()

    private suspend fun load(): FeedCalendar = lock.withLock {
        val url = url ?: throw ConnectorUnavailable("no valid https address")
        val now = clock()
        val cached = calendar
        val at = fetchedAt
        if (cached != null && at != null && now - at < cacheFor) return@withLock cached
        val r = http.get(url) { header(HttpHeaders.Accept, "text/calendar") }
        if (r.status.value != 200) throw ConnectorUnavailable("the calendar answered ${r.status.value}")
        val text = r.bodyAsBytes().decodeToString() // malformed bytes become U+FFFD, as Dart's allowMalformed
        if (!text.contains("BEGIN:VCALENDAR")) throw ConnectorUnavailable("that address is not a calendar feed")
        parser.parse(text).also {
            calendar = it
            fetchedAt = now
        }
    }

    override suspend fun busy(from: Instant, to: Instant): JsonElement {
        val cal = load()
        return buildJsonObject {
            putJsonArray("windows") { for (w in cal.busy(from, to)) add(w.toJson()) }
        }
    }

    override suspend fun read(query: String, limit: Int): JsonElement? =
        throw ConnectorUnavailable("context.read not served")

    override suspend fun close() = http.close()

    companion object {
        const val ID = "com.kahflabs.calendar-feed"

        val definition: Manifest = Manifest.parse(
            Json.parseToJsonElement(
                """
                {
                  "kahf_connect": "1",
                  "id": "$ID",
                  "name": {"en": "Calendar feed", "ar": "رابط التقويم"},
                  "description": {
                    "en": "Busy times from your calendar's private address — Google, Outlook, iCloud or any other. No sign-in.",
                    "ar": "أوقات انشغالك من الرابط الخاص لتقويمك — Google أو Outlook أو iCloud أو غيرها. دون تسجيل دخول."
                  },
                  "version": "1.0.0",
                  "author": {"name": "Kahf Labs", "url": "https://kahflabs.com"},
                  "host": {"kind": "builtin"},
                  "auth": {"kind": "none"},
                  "capabilities": [{
                    "id": "time.busy",
                    "version": 1,
                    "reason": {
                      "en": "To keep planned focus off the times your calendar says you are busy.",
                      "ar": "لكي لا يُخطَّط تركيز في أوقات يقول تقويمك إنك مشغول فيها."
                    }
                  }],
                  "settings": [{
                    "key": "url",
                    "kind": "secret-url",
                    "required": true,
                    "label": {"en": "Private calendar address", "ar": "الرابط الخاص للتقويم"},
                    "help": {
                      "en": "Google Calendar: Settings → your calendar → \"Secret address in iCal format\". It starts with https:// and ends with .ics.",
                      "ar": "في Google Calendar: الإعدادات ← تقويمك ← «العنوان السري بتنسيق iCal». يبدأ بـ https:// وينتهي بـ ‎.ics."
                    }
                  }]
                }
                """,
            ).jsonObject,
        )

        /** `webcal://` is `https://`; anything else that is not https is refused, since the address is a secret. */
        private fun normalise(raw: String): Url? {
            var s = raw.trim()
            if (s.startsWith("webcal://")) s = "https://${s.substring(9)}"
            val u = checkedUrl(s) ?: return null
            // The request is made with this very Url, so the host checked is the host fetched (kahf-connect#16).
            return if (u.protocol.name.lowercase() == "https") u else null
        }

        /** Whether [raw] is an address this connector can read. */
        fun validUrl(raw: String): Boolean = normalise(raw) != null
    }
}

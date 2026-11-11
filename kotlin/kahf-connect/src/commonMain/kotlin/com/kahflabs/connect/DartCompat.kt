package com.kahflabs.connect

import io.ktor.http.Url
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// The Dart SDK is the reference: these helpers give JSON values, URIs and
// times the meaning Dart's `jsonDecode`, `Uri.tryParse` and `DateTime.tryParse`
// give them, so both SDKs reach the same verdict on the same bytes.

/** Absent or JSON `null` (Dart: `== null`). */
internal fun JsonElement?.isNull(): Boolean = this == null || this is JsonNull

/** A JSON string (Dart: `is String`). */
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** JSON `true` (Dart: `== true`). */
internal fun JsonElement?.isTrue(): Boolean = this is JsonPrimitive && !isString && content == "true"

/** The value when it is a JSON integer (Dart: `is int`, 64-bit; `1.0` and `1e2` decode to `double`), else null. */
internal fun JsonElement?.asInt(): Long? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && INT.matches(it.content) }?.content?.toLongOrNull()

private val INT = Regex("^-?[0-9]+$")

/** The value when it is a JSON number of any kind (Dart: `is num`), else null. */
internal fun JsonElement?.asNum(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

/** Dart's `'$value'` for a decoded JSON value, for error details only. */
internal fun JsonElement?.show(): String = when {
    isNull() -> "null"
    this is JsonPrimitive -> content
    else -> toString()
}

/** The parts of a URI the SDK checks, read the way Dart's `Uri.tryParse` reads them. */
internal class ParsedUri(val scheme: String, val host: String) {
    val hasScheme get() = scheme.isNotEmpty()

    companion object {
        // RFC 3986 appendix B, with the scheme's own grammar.
        private val URI = Regex("^(?:([a-zA-Z][a-zA-Z0-9+.-]*):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$")
        private val REG_NAME = Regex("^(?:[A-Za-z0-9\\-._~!$&'()*+,;=]|%[0-9A-Fa-f]{2})*$")
        private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+]$")

        /** Null where Dart's `Uri.tryParse` returns null. */
        fun parse(raw: String): ParsedUri? {
            val m = URI.matchEntire(raw) ?: return null
            val scheme = m.groupValues[1].lowercase()
            val authority = m.groups[2]?.value ?: return ParsedUri(scheme, "")
            val hostPort = authority.substringAfterLast('@')
            val (host, port) = if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                hostPort.substring(0, close + 1) to hostPort.substring(close + 1).removePrefix(":")
            } else {
                hostPort.substringBefore(':') to hostPort.substringAfter(':', "")
            }
            if (port.any { it !in '0'..'9' }) return null
            return when {
                IPV6.matches(host) -> ParsedUri(scheme, host.substring(1, host.length - 1).lowercase())
                REG_NAME.matches(host) -> ParsedUri(scheme, host.lowercase())
                else -> null
            }
        }
    }
}

// The scheme and `//` that open an authority, and the authority itself (up to `/`, `?`, `#`).
private val AUTHORITY_OF = Regex("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]*)")

// A strict RFC 3986 authority: at most one `@`, userinfo of unreserved, sub-delim, `:` or
// %XX characters, a host of letters, digits and `-._~` (an IDN arrives as punycode; a
// percent-escaped host is refused) or a bracketed IPv6 literal, and a numeric port.
// A backslash, a space or a non-ASCII character is in none of these.
private val STRICT_AUTHORITY = Regex(
    "^(?:(?:[A-Za-z0-9\\-._~!$&'()*+,;=:]|%[0-9A-Fa-f]{2})*@)?" +
        "(?:[A-Za-z0-9\\-._~]+|\\[[0-9A-Fa-f:.]+])(?::[0-9]*)?$",
)

/**
 * The Ktor [Url] the HTTP client will request for [raw], or null when [raw] is not
 * an absolute URL with a host that both parsers read the same way.
 *
 * [ParsedUri] gives Dart's verdict, but Ktor is what sends the request, and the two
 * disagree on a backslash in the authority (`https://evil.example\@good.example/`:
 * Ktor connects to evil.example) and on `https:/good.example` (Ktor: localhost). So
 * the authority must be strict RFC 3986 first, then Ktor and [ParsedUri] must agree
 * on the scheme and host, and the caller decides on the scheme and host of the
 * returned [Url], never of a second parse (kahf-connect#16). The Dart SDK applies
 * the same authority rule in `dart/lib/src/uri_check.dart`.
 */
internal fun checkedUrl(raw: String): Url? {
    val parsed = ParsedUri.parse(raw) ?: return null
    if (!parsed.hasScheme || parsed.host.isEmpty()) return null
    val authority = AUTHORITY_OF.find(raw)?.groupValues?.get(1) ?: return null
    if (!STRICT_AUTHORITY.matches(authority)) return null
    val url = try {
        Url(raw)
    } catch (_: Exception) {
        return null
    }
    val host = url.host.removeSurrounding("[", "]").lowercase()
    if (url.protocol.name.lowercase() != parsed.scheme || host != parsed.host) return null
    return url
}

// Dart's `DateTime.parse` grammar (sdk/lib/core/date_time.dart).
private val DART_TIME = Regex(
    "^([+-]?\\d{4,6})-?(\\d\\d)-?(\\d\\d)" +
        "(?:[ T](\\d\\d)(?::?(\\d\\d)(?::?(\\d\\d)(?:[.,](\\d+))?)?)?" +
        "( ?[zZ]| ?([-+])(\\d\\d)(?::?(\\d\\d))?)?)?$",
)

/**
 * Dart's `DateTime.tryParse` as an instant. Like Dart, out-of-range fields
 * roll over (`02-30` is `03-02`), and a time with no offset is local time.
 */
internal fun parseDartTime(raw: String): Instant? {
    val m = DART_TIME.matchEntire(raw) ?: return null
    val g = m.groupValues
    fun num(i: Int) = if (g[i].isEmpty()) 0L else g[i].toLong()
    val year = g[1].removePrefix("+").toLongOrNull() ?: return null
    val month0 = num(2) - 1
    val y = year + month0.floorDiv(12L)
    val mo = month0.mod(12L) + 1
    val epochDay = LocalDate(y.toInt(), mo.toInt(), 1).toEpochDays() + num(3) - 1
    val seconds = epochDay * 86_400 + num(4) * 3_600 + num(5) * 60 + num(6)
    // Dart keeps microseconds and drops further digits.
    val nanos = g[7].take(6).padEnd(6, '0').toInt() * 1_000
    val wall = Instant.fromEpochSeconds(seconds, nanos)
    return when {
        // No offset: the fields are wall-clock time where this runs.
        g[8].isEmpty() -> wall.toLocalDateTime(TimeZone.UTC).toInstant(TimeZone.currentSystemDefault())
        g[9].isEmpty() -> wall // Z
        else -> {
            val offset = num(10) * 3_600 + num(11) * 60
            Instant.fromEpochSeconds(seconds + if (g[9] == "-") offset else -offset, nanos)
        }
    }
}

/** Dart's `toUtc().toIso8601String()`: `2026-10-27T09:00:00.000Z`. */
internal fun Instant.toDartIso(): String {
    val t = toLocalDateTime(TimeZone.UTC)
    fun p(v: Int, n: Int = 2) = v.toString().padStart(n, '0')
    val y = t.year
    val year = if (y in 0..9999) p(y, 4) else (if (y < 0) "-" else "+") + p(kotlin.math.abs(y), 6)
    val micros = nanosecondsOfSecond / 1_000
    val frac = p(micros / 1_000, 3) + if (micros % 1_000 != 0) p(micros % 1_000, 3) else ""
    return "$year-${p(t.month.ordinal + 1)}-${p(t.day)}T${p(t.hour)}:${p(t.minute)}:${p(t.second)}.${frac}Z"
}

package com.kahflabs.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * kahf-connect#16: the https-or-localhost check judges the scheme and host of the
 * Ktor `Url` the client will request, so a URL the two parsers read differently is
 * refused instead of passing on one reading and connecting on the other.
 */
class UrlCheckTest {
    private val bs = Char(92) // a backslash

    private fun checked(raw: String) = checkedUrl(raw)?.let { it.protocol.name to it.host }

    @Test
    fun theCheckedHostIsTheOneKtorWillConnectTo() {
        // Userinfo tricks: whatever precedes the last @ is credentials, not the host.
        assertEquals("https" to "evil.example", checked("https://good.example@evil.example/"))
        assertEquals("https" to "evil.example", checked("https://good.example:443@evil.example/"))
        assertEquals("https" to "evil.example", checked("https://good.example%2f@evil.example/"))
        assertEquals("http" to "evil.example", checked("http://localhost@evil.example/"))
        assertEquals("https" to "good.example", checked("https://good.example#@evil.example/"))
        assertEquals("https" to "good.example", checked("https://good.example?@evil.example/"))
        assertEquals("https" to "good.example", checked("https://good.example/@evil.example/"))
        assertEquals("https" to "good.example", checked("https://user:pa%40ss@good.example:8443/p"))
    }

    @Test
    fun aLegitimateUrlPasses() {
        assertEquals("https" to "connect.example.com", checked("https://connect.example.com/mcp"))
        assertEquals("https" to "connect.example.com", checked("https://connect.example.com"))
        assertEquals("https" to "connect.example.com", checked("https://connect.example.com:8443/mcp?a=b#c"))
        assertEquals("https" to "a-b_c.d.example", checked("https://a-b_c.d.example/"))
        assertEquals("https" to "203.0.113.7", checked("https://203.0.113.7/"))
    }

    @Test
    fun punycodeIsAPlainHostAndAUnicodeHostIsRefused() {
        assertNotNull(checkedUrl("https://xn--gd-sna.example/"))
        assertNotNull(checkedUrl("https://XN--GD-SNA.example/"), "case-blind")
        assertNull(checkedUrl("https://g\u00f6\u00f6d.example/"))
        assertNull(checkedUrl("https://\u0645\u062b\u0627\u0644.example/"))
        assertNull(checkedUrl("https://good.example\u202e/"))
    }

    @Test
    fun aBackslashInTheAuthorityIsRefused() {
        assertNull(checkedUrl("https://evil.example$bs@good.example/")) // Ktor: evil.example; RFC 3986: good.example
        assertNull(checkedUrl("https://evil.example${bs}good.example/"))
        assertNull(checkedUrl("http://localhost$bs@evil.example/"))
        assertNull(checkedUrl("https://good.example$bs"))
        assertNull(checkedUrl("https://$bs$bs good.example/"))
    }

    @Test
    fun moreThanOneAtSignIsRefused() {
        assertNull(checkedUrl("https://a@b@good.example/"))
        assertNull(checkedUrl("https://a:b@c@good.example/p"))
        assertNull(checkedUrl("https://@@good.example/"))
        assertNull(checkedUrl("https://a@@good.example/"))
    }

    @Test
    fun aCharacterOutsideRfc3986InTheAuthorityIsRefused() {
        for (c in listOf(" ", "\t", "\n", "\r", "\u0000", "\u00a0", "\u00f6", "\u3002", "\u2044", "<", ">", "\"", "{", "|", "^", "`", "%", "[")) {
            assertNull(checkedUrl("https://good${c}example/"), "host $c")
            assertNull(checkedUrl("https://user${c}x@good.example/"), "userinfo $c")
            assertNull(checkedUrl("https://good.example:44${c}3/"), "port $c")
        }
        assertNull(checkedUrl("https://%65vil.example/"), "a percent-escaped host")
        assertNull(checkedUrl("https://good.example:84x3/"))
        assertNull(checkedUrl("https://good.example:99999/"), "Ktor reads no such port")
        assertNull(checkedUrl("https://[::1/"))
        assertNull(checkedUrl("https://[zz::1]/"))
    }

    @Test
    fun aMissingOrBrokenSchemeOrHostIsRefused() {
        assertNull(checkedUrl(""))
        assertNull(checkedUrl("good.example/mcp"))
        assertNull(checkedUrl("//good.example/mcp"))
        assertNull(checkedUrl("/mcp"))
        assertNull(checkedUrl(" https://good.example/"), "leading space")
        assertNull(checkedUrl("https://"))
        assertNull(checkedUrl("https:///mcp"))
        assertNull(checkedUrl("https:/good.example/")) // Ktor: localhost
        assertNull(checkedUrl("https:good.example/")) // Ktor: localhost
        assertNull(checkedUrl("mailto:a@good.example"))
        assertNull(checkedUrl("1http://good.example/"))
    }

    @Test
    fun theSchemeIsCaseBlind() {
        assertEquals("http" to "localhost", checked("HTTP://localhost/")?.let { it.first to it.second.lowercase() })
        assertEquals("https" to "good.example", checked("HTTPS://good.example/"))
    }

    // The https-or-localhost rule, through the manifest that uses it.

    private fun manifestWithHost(url: String): JsonObject {
        val base = Fixtures.obj("fixtures/manifests/valid/localhost-http-for-development.json")
        return JsonObject(base + ("host" to buildJsonObject { put("kind", "remote"); put("url", url) }))
    }

    private fun hostUrlAccepted(url: String): Boolean = try {
        Manifest.parse(manifestWithHost(url)).hostUrl == url
    } catch (e: ManifestError) {
        false
    }

    private fun refusedWith(url: String): String = try {
        Manifest.parse(manifestWithHost(url))
        fail("accepted $url")
    } catch (e: ManifestError) {
        e.rule
    }

    @Test
    fun httpIsAllowedOnlyOnThisMachine() {
        assertEquals(true, hostUrlAccepted("https://connect.example.com/mcp"))
        assertEquals(true, hostUrlAccepted("http://localhost/mcp"))
        assertEquals(true, hostUrlAccepted("http://localhost:8765/mcp"))
        assertEquals(true, hostUrlAccepted("http://127.0.0.1:8765/mcp"))
        assertEquals(true, hostUrlAccepted("HTTP://LOCALHOST:8765/mcp"), "scheme and host are case-blind")
        assertEquals("host-https", refusedWith("http://connect.example.com/mcp"))
        assertEquals("host-https", refusedWith("http://[::1]:8765/mcp"), "only localhost and 127.0.0.1, as in Dart")
        assertEquals("host-https", refusedWith("http://localhost./mcp"))
        assertEquals("host-https", refusedWith("http://localhost.evil.example/mcp"))
        assertEquals("host-https", refusedWith("http://127.0.0.1.evil.example/mcp"))
        assertEquals("host-https", refusedWith("http://localhost@evil.example/mcp"))
        assertEquals("host-https", refusedWith("http://localhost:80@evil.example/mcp"))
        assertEquals("host-https", refusedWith("http://127.0.0.1@evil.example/mcp"))
    }

    @Test
    fun aUrlTheParsersReadDifferentlyIsNotAManifestUri() {
        assertEquals("uri", refusedWith("https://evil.example$bs@good.example/mcp"))
        assertEquals("uri", refusedWith("http://localhost$bs@evil.example/mcp"))
        assertEquals("uri", refusedWith("http://127.0.0.1$bs@evil.example/mcp"))
        assertEquals("uri", refusedWith("https://a@b@good.example/mcp"))
        assertEquals("uri", refusedWith("https://good.example /mcp"))
        assertEquals("uri", refusedWith("https://g\u00f6\u00f6d.example/mcp"))
        assertEquals("uri", refusedWith("https:/good.example/mcp"))
        assertEquals("uri", refusedWith("connect.example.com/mcp"))
    }
}

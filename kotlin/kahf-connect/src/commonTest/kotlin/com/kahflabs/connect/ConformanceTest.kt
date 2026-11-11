package com.kahflabs.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SPEC §9: every SDK runs every fixture and agrees on every verdict.
 * A 1:1 port of `dart/test/conformance_test.dart`, case for case.
 */
class ConformanceTest {
    @Test
    fun manifests() {
        val cases = Cases("manifests")
        val valid = Fixtures.list("fixtures/manifests/valid")
        for (path in valid) {
            cases.case("valid: ${path.substringAfterLast('/')}") {
                val m = Manifest.parse(Fixtures.obj(path))
                assertTrue(m.capabilities.isNotEmpty())
                assertTrue(m.toJson().isNotEmpty())
            }
        }
        val index = Fixtures.json("fixtures/manifests/invalid/index.json").jsonArray
        for (c in index) {
            val file = c.jsonObject["file"]!!.jsonPrimitive.content
            val rule = c.jsonObject["rule"]!!.jsonPrimitive.content
            cases.case("invalid: $file breaks $rule") {
                assertRule<ManifestError>(rule, { it.rule }) {
                    Manifest.parse(Fixtures.obj("fixtures/manifests/invalid/$file"))
                }
            }
        }
        assertTrue(valid.isNotEmpty(), "no valid manifest fixtures")
        cases.done()
    }

    @Test
    fun timeBusyV1() {
        val cases = Cases("time.busy@1")
        val fx = Fixtures.obj("fixtures/capabilities/time.busy.v1.json")
        val from = Instant.parse(fx["from"]!!.jsonPrimitive.content)
        val to = Instant.parse(fx["to"]!!.jsonPrimitive.content)
        for ((i, ok) in fx["valid"]!!.jsonArray.withIndex()) {
            cases.case("valid[$i]") { parseBusy(ok, from, to) }
        }
        for (c in fx["invalid"]!!.jsonArray) {
            val rule = c.jsonObject["rule"]!!.jsonPrimitive.content
            cases.case("invalid: $rule") {
                assertRule<CapabilityError>(rule, { it.rule }) { parseBusy(c.jsonObject["output"], from, to) }
            }
        }
        cases.done()
    }

    @Test
    fun contextReadV1() {
        val cases = Cases("context.read@1")
        val fx = Fixtures.obj("fixtures/capabilities/context.read.v1.json")
        val limit = fx["limit"]!!.jsonPrimitive.int
        for ((i, ok) in fx["valid"]!!.jsonArray.withIndex()) {
            cases.case("valid[$i]") { parseContext(ok, limit = limit) }
        }
        for (c in fx["invalid"]!!.jsonArray) {
            val rule = c.jsonObject["rule"]!!.jsonPrimitive.content
            cases.case("invalid: $rule") {
                assertRule<CapabilityError>(rule, { it.rule }) { parseContext(c.jsonObject["output"], limit = limit) }
            }
        }
        cases.done()
    }

    private val testKey = mapOf("test-only" to "5d0CWh1aA7rAb7lqkKPaJJUpq0GRuI/fNh5D9qxV82g=")

    private fun bytes(f: String) = Fixtures.bytes("fixtures/catalog/$f")

    private fun sig(f: String) = Fixtures.text("fixtures/catalog/$f.sig")

    @Test
    fun aSignedCatalogVerifiesAndListsItsEntries() = runTest {
        val c = Catalog.verify(bytes("catalog.json"), sig("catalog.json"), keys = testKey)
        assertEquals(
            listOf("com.kahflabs.calendar-feed", "com.kahflabs.mizwala.times"),
            c.entries.map { it.manifest.id },
        )
        assertTrue(c.entries.all { it.reviewed })
    }

    @Test
    fun oneChangedByteAndItIsRefused() = runTest {
        assertCatalogRule("signature") { Catalog.verify(bytes("tampered.json"), sig("tampered.json"), keys = testKey) }
    }

    /** Not in the Dart tests: a signature that is not 64 bytes, or not base64, is refused, never a crash. */
    @Test
    fun aMalformedSignatureIsRefused() = runTest {
        assertCatalogRule("signature") { Catalog.verify(bytes("catalog.json"), "AAAA", keys = testKey) }
        assertCatalogRule("signature") { Catalog.verify(bytes("catalog.json"), "not base64!", keys = testKey) }
        // Dart's base64.decode requires the padding.
        val unpadded = sig("catalog.json").trim().trimEnd('=')
        assertCatalogRule("signature") { Catalog.verify(bytes("catalog.json"), unpadded, keys = testKey) }
    }

    @Test
    fun aKeyTheSdkDoesNotCarryIsRefused() = runTest {
        assertCatalogRule("key-unknown") { Catalog.verify(bytes("catalog.json"), sig("catalog.json")) }
    }

    /** `dart/test/catalog_live_test.dart`: the shipped catalog verifies with the key the SDK carries. */
    @Test
    fun theShippedCatalogIsSignedByKahf20261() = runTest {
        val c = Catalog.verify(Fixtures.bytes("catalog/catalog.json"), Fixtures.text("catalog/catalog.json.sig"))
        assertEquals("kahf-2026-1", c.keyId)
        assertTrue(c.entry("com.kahflabs.calendar-feed") != null)
    }

    /** Not in the Dart tests: Dart's `int` is 64-bit, so a capability version past 32 bits still parses. */
    @Test
    fun aCapabilityVersionPast32BitsParses() {
        val raw = Fixtures.text("fixtures/manifests/valid/remote-bearer.json")
            .replace("\"version\": 1,", "\"version\": 3000000000,")
        val m = Manifest.parse(Json.parseToJsonElement(raw).jsonObject)
        assertEquals("kahf_time_busy_v3000000000", m.capabilities.single { it.id == "time.busy" }.tool)
    }

    @Test
    fun capabilityToolsAreNamedAsSpec2Says() {
        assertEquals("kahf_time_busy_v1", Capabilities.tool("time.busy", 1))
        assertEquals("kahf_context_read_v1", Capabilities.tool("context.read", 1))
        assertEquals("kahf_actions_propose_v2", Capabilities.tool("actions.propose", 2))
    }

    private suspend fun assertCatalogRule(rule: String, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CatalogError) {
            assertEquals(rule, e.rule, "$e")
            return
        }
        fail("expected CatalogError($rule)")
    }
}

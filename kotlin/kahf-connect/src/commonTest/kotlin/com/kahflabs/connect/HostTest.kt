package com.kahflabs.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A connector that answers [answer] (or throws [error]) after [delay]. */
internal class FakeConnector(
    id: String,
    private val answer: JsonElement? = null,
    private val error: Throwable? = null,
    private val delay: Duration = Duration.ZERO,
) : Connector() {
    override val manifest: Manifest = Manifest.parse(
        json(
            """
            {"kahf_connect": "1", "id": "$id", "name": {"en": "$id"}, "description": {"en": "$id"},
             "version": "1.0.0", "author": {"name": "Test"}, "host": {"kind": "builtin"}, "auth": {"kind": "none"},
             "capabilities": [
               {"id": "time.busy", "version": 1, "reason": {"en": "x"}},
               {"id": "context.read", "version": 1, "reason": {"en": "x"}}]}
            """,
        ),
    )
    var calls = 0

    override suspend fun available(): Set<String> = setOf("time.busy@1", "context.read@1")

    private suspend fun go(): JsonElement? {
        calls++
        delay(delay)
        error?.let { throw it }
        return answer
    }

    override suspend fun busy(from: Instant, to: Instant) = go()

    override suspend fun read(query: String, limit: Int) = go()
}

internal fun json(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject

/**
 * The host is what makes connectors safe: nothing is called without a
 * grant, labels and agent visibility follow the grant, a broken or slow
 * connector cannot spoil the others, and every call is audited without its
 * data. Port of `dart/test/host_test.dart`.
 */
class HostTest {
    private val from = Instant.parse("2026-10-27T00:00:00Z")
    private val to = Instant.parse("2026-10-28T00:00:00Z")
    private val at = Instant.parse("2026-10-26T00:00:00Z")
    private val window = json(
        """{"windows": [{"start": "2026-10-27T09:00:00Z", "end": "2026-10-27T10:00:00Z", "label": "Board meeting"}]}""",
    )

    @Test
    fun nothingIsCalledWithoutAGrant() = runTest {
        val c = FakeConnector("com.example.one", answer = window)
        val audit = MemoryAuditSink()
        val host = ConnectorHost(connectors = listOf(c), grants = emptyList(), audit = audit)
        val r = host.busy(from, to)
        assertTrue(r.items.isEmpty())
        assertEquals(0, c.calls)
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun labelsAreDroppedUnlessTheGrantKeepsThem() = runTest {
        val c = FakeConnector("com.example.one", answer = window)
        var host = ConnectorHost(listOf(c), listOf(Grant(c.manifest.id, "time.busy", at = at)), MemoryAuditSink())
        assertNull(host.busy(from, to).items.single().label)
        host = ConnectorHost(
            listOf(c),
            listOf(Grant(c.manifest.id, "time.busy", labels = true, at = at)),
            MemoryAuditSink(),
        )
        assertEquals("Board meeting", host.busy(from, to).items.single().label)
    }

    @Test
    fun agentsSeeOnlyConnectorsWhoseGrantLetsThem() = runTest {
        val shared = FakeConnector("com.example.shared", answer = json("""{"items": [{"id": "a", "title": "Plan", "text": "x"}]}"""))
        val private = FakeConnector("com.example.private", answer = json("""{"items": [{"id": "b", "title": "Diary", "text": "y"}]}"""))
        val host = ConnectorHost(
            listOf(shared, private),
            listOf(
                Grant(shared.manifest.id, "context.read", agents = true, at = at),
                Grant(private.manifest.id, "context.read", at = at),
            ),
            MemoryAuditSink(),
        )
        assertEquals(listOf("a"), host.read().items.map { it.id })
        assertEquals(0, private.calls, "not even asked")
        // For the product itself (not an agent), both.
        assertEquals(2, host.read(forAgents = false).items.size)
    }

    @Test
    fun oneBrokenOneSlowOneFineTheFineOneStillAnswers() = runTest {
        val fine = FakeConnector("com.example.fine", answer = window)
        val broken = FakeConnector(
            "com.example.broken",
            answer = json("""{"windows": [{"start": "2026-10-27T10:00:00Z", "end": "2026-10-27T09:00:00Z"}]}"""),
        )
        val slow = FakeConnector("com.example.slow", answer = window, delay = 300.milliseconds)
        val audit = MemoryAuditSink()
        val host = ConnectorHost(
            connectors = listOf(fine, broken, slow),
            grants = listOf(fine, broken, slow).map { Grant(it.manifest.id, "time.busy", at = at) },
            audit = audit,
            timeout = 50.milliseconds,
        )
        val r = host.busy(from, to)
        assertEquals(1, r.items.size)
        assertFalse(r.complete)
        val by = r.sources.associateBy { it.connector.id }
        assertEquals(Outcome.Ok, by.getValue("com.example.fine").outcome)
        assertEquals(Outcome.Error, by.getValue("com.example.broken").outcome)
        assertEquals("contract:order", by.getValue("com.example.broken").detail)
        assertEquals("timeout", by.getValue("com.example.slow").detail)
        assertEquals(3, audit.events.size)
    }

    @Test
    fun theAuditKeepsCountsAndTimingsNeverTheData() = runTest {
        val c = FakeConnector("com.example.one", answer = window)
        val audit = MemoryAuditSink()
        val host = ConnectorHost(listOf(c), listOf(Grant(c.manifest.id, "time.busy", labels = true, at = at)), audit)
        host.busy(from, to)
        val e = audit.events.single()
        assertEquals(Outcome.Ok, e.outcome)
        assertEquals(1, e.items)
        assertFalse("Board meeting" in e.toJson().toString())
        assertEquals(e.toJson(), AuditEvent.fromJson(e.toJson()).toJson())
        assertEquals("ok", e.toJson()["outcome"].str())
    }

    @Test
    fun aConnectorThatCannotServeSaysSoAndIsNotAnError() = runTest {
        val c = FakeConnector("com.example.one", error = ConnectorUnavailable("kahf_time_busy_v1 not served"))
        val host = ConnectorHost(listOf(c), listOf(Grant(c.manifest.id, "time.busy", at = at)), MemoryAuditSink())
        val r = host.busy(from, to)
        assertEquals(Outcome.Unavailable, r.sources.single().outcome)
        assertEquals("kahf_time_busy_v1 not served", r.sources.single().detail)
    }

    @Test
    fun anUnexpectedFailureIsAnErrorNamedByItsType() = runTest {
        val c = FakeConnector("com.example.one", error = IllegalStateException("secret data"))
        val audit = MemoryAuditSink()
        val host = ConnectorHost(listOf(c), listOf(Grant(c.manifest.id, "time.busy", at = at)), audit)
        val r = host.busy(from, to)
        assertEquals(Outcome.Error, r.sources.single().outcome)
        assertEquals("IllegalStateException", audit.events.single().detail)
    }

    @Test
    fun grantsRoundTrip() {
        val g = Grant("com.example.one", "time.busy", labels = true, agents = true, at = at)
        assertEquals(g.toJson(), Grant.fromJson(g.toJson()).toJson())
        assertEquals("2026-10-26T00:00:00.000Z", g.toJson()["at"].str())
    }
}

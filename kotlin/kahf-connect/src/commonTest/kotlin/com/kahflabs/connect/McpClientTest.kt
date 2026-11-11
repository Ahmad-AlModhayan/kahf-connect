package com.kahflabs.connect

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun HttpRequestData.text(): String = (body as TextContent).text

internal fun HttpRequestData.message(): JsonObject = Json.parseToJsonElement(text()).jsonObject

/**
 * `runTest` on real time: Ktor answers on its own dispatcher, and virtual
 * time would fire the client's timeout while it waits.
 */
internal fun realTest(body: suspend () -> Unit): TestResult = runTest { withContext(Dispatchers.Default) { body() } }

internal fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    HttpClient(MockEngine(handler))

/** A small MCP server in a function: answers initialize, tools/list and tools/call, as JSON or as an event stream, and records what it was sent. */
private class Server(val stream: Boolean = false, val tools: List<String> = listOf("kahf_time_busy_v1")) {
    val requests = mutableListOf<HttpRequestData>()

    val client = mockClient { r ->
        requests += r
        val m = r.message()
        val id = m["id"] ?: return@mockClient respond("", HttpStatusCode.Accepted)
        val result = when (m["method"].str()) {
            "initialize" -> """{"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "test", "version": "1"}}"""
            "tools/list" -> """{"tools": [${tools.joinToString { """{"name": "$it", "inputSchema": {"type": "object"}}""" }}]}"""
            "tools/call" -> """{"content": [{"type": "text", "text": "ok"}],
                "structuredContent": {"windows": [{"start": "2026-10-27T09:00:00Z", "end": "2026-10-27T10:00:00Z"}]}}"""
            else -> "{}"
        }
        val body = """{"jsonrpc": "2.0", "id": $id, "result": $result}""".replace("\n", " ")
        if (stream) {
            respond(
                "event: message\ndata: $body\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("text/event-stream"), "mcp-session-id" to listOf("s-1")),
            )
        } else {
            respond(
                body,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "mcp-session-id" to listOf("s-1")),
            )
        }
    }
}

internal fun remote(url: String = "https://connect.example.com/mcp"): Manifest = Manifest.parse(
    json(
        """
        {"kahf_connect": "1", "id": "com.example.busy", "name": {"en": "Busy"}, "description": {"en": "Busy"},
         "version": "1.0.0", "author": {"name": "Example"}, "host": {"kind": "remote", "url": "$url"},
         "auth": {"kind": "bearer", "setting": "token"},
         "capabilities": [
           {"id": "time.busy", "version": 1, "reason": {"en": "x"}},
           {"id": "context.read", "version": 1, "reason": {"en": "x"}}],
         "settings": [{"key": "token", "kind": "secret", "label": {"en": "Token"}}]}
        """,
    ),
)

private fun rpcAnswer(r: HttpRequestData, inner: String) =
    """{"jsonrpc": "2.0", "id": ${r.message()["id"]}, $inner}"""

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** Port of `dart/test/mcp_client_test.dart`. */
class McpClientTest {
    private val from = Instant.parse("2026-10-27T00:00:00Z")
    private val to = Instant.parse("2026-10-28T00:00:00Z")

    @Test
    fun busyWindowsOverMcpJson() = busyOverMcp(stream = false)

    @Test
    fun busyWindowsOverMcpEventStream() = busyOverMcp(stream = true)

    private fun busyOverMcp(stream: Boolean) = realTest {
        val s = Server(stream = stream)
        val c = McpConnector(remote(), settings = mapOf("token" to "tok-1"), client = s.client)
        val host = ConnectorHost(listOf(c), listOf(Grant("com.example.busy", "time.busy", at = from)), MemoryAuditSink())
        val r = host.busy(from, to)
        assertTrue(r.complete, r.sources.single().detail)
        assertEquals(Instant.parse("2026-10-27T09:00:00Z"), r.items.single().start)
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", "tools/call"),
            s.requests.map { it.message()["method"].str() },
        )
        // The token goes to the connector, the session and version come back.
        assertTrue(s.requests.all { it.headers[HttpHeaders.Authorization] == "Bearer tok-1" })
        assertEquals("application/json, text/event-stream", s.requests.first().headers[HttpHeaders.Accept])
        assertEquals("s-1", s.requests.last().headers["mcp-session-id"])
        assertEquals("2025-06-18", s.requests.last().headers["mcp-protocol-version"])
        val params = s.requests.last().message()["params"]!!.jsonObject
        assertEquals("kahf_time_busy_v1", params["name"].str())
        assertEquals(
            json("""{"from": "2026-10-27T00:00:00.000Z", "to": "2026-10-28T00:00:00.000Z"}"""),
            params["arguments"],
        )
    }

    @Test
    fun aDeclaredCapabilityTheServerDoesNotServeIsUnavailable() = realTest {
        val s = Server(tools = listOf("kahf_time_busy_v1"))
        val c = McpConnector(remote(), settings = mapOf("token" to "t"), client = s.client)
        assertEquals(setOf("time.busy@1"), c.available())
        val host = ConnectorHost(
            listOf(c),
            listOf(Grant("com.example.busy", "context.read", agents = true, at = from)),
            MemoryAuditSink(),
        )
        assertEquals(Outcome.Unavailable, host.read().sources.single().outcome)
    }

    @Test
    fun anHttpErrorAndAJsonRpcErrorAreMcpErrors() = realTest {
        val down = McpHttpClient("https://x.example.com/mcp", client = mockClient { respond("nope", HttpStatusCode.ServiceUnavailable) })
        val e1 = assertFailsWith<McpError> { down.initialize() }
        assertEquals("http", e1.kind)
        assertEquals(503, e1.status)
        val rpc = McpHttpClient(
            "https://x.example.com/mcp",
            client = mockClient { r ->
                respond(rpcAnswer(r, """"error": {"code": -32602, "message": "bad params"}"""), HttpStatusCode.OK, jsonHeaders)
            },
        )
        val e2 = assertFailsWith<McpError> { rpc.initialize() }
        assertEquals("rpc", e2.kind)
        assertEquals(-32602, e2.code)
        assertEquals("bad params", e2.message)
    }

    @Test
    fun aServerSpeakingAProtocolVersionWeDoNotIsRefused() = realTest {
        val old = McpHttpClient(
            "https://x.example.com/mcp",
            client = mockClient { r ->
                respond(rpcAnswer(r, """"result": {"protocolVersion": "2024-11-05", "capabilities": {}}"""), HttpStatusCode.OK, jsonHeaders)
            },
        )
        assertEquals("protocol", assertFailsWith<McpError> { old.initialize() }.kind)
    }

    @Test
    fun serverInfoAndProtocolVersionAfterInitialize() = realTest {
        val c = McpHttpClient("https://x.example.com/mcp", client = Server().client)
        c.initialize()
        assertTrue(c.initialized)
        assertEquals("2025-06-18", c.protocolVersion)
        assertEquals("test", c.serverInfo!!["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("kahf_time_busy_v1"), c.listTools().map { it.name })
    }

    @Test
    fun aStreamWithoutTheAnswerIsAProtocolError() = realTest {
        val c = McpHttpClient(
            "https://x.example.com/mcp",
            client = mockClient {
                respond(": ping\n\ndata: not json\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            },
        )
        assertEquals("protocol", assertFailsWith<McpError> { c.initialize() }.kind)
    }

    @Test
    fun aToolErrorIsUnavailableAndTextOnlyJsonIsRead() = realTest {
        var isError = true
        val s = mockClient { r ->
            val m = r.message()
            if (m["id"] == null) return@mockClient respond("", HttpStatusCode.Accepted)
            val result = when (m["method"].str()) {
                "initialize" -> """{"protocolVersion": "2025-11-25", "capabilities": {}}"""
                "tools/list" -> """{"tools": [{"name": "kahf_context_read_v1"}]}"""
                else -> if (isError) {
                    """{"isError": true, "content": [{"type": "text", "text": "quota"}]}"""
                } else {
                    """{"content": [{"type": "text", "text": "{\"items\": [{\"id\": \"a\", \"title\": \"t\", \"text\": \"x\"}]}"}]}"""
                }
            }
            respond(rpcAnswer(r, """"result": $result"""), HttpStatusCode.OK, jsonHeaders)
        }
        val c = McpConnector(remote(), settings = mapOf("token" to "t"), client = s)
        val host = ConnectorHost(listOf(c), listOf(Grant("com.example.busy", "context.read", agents = true, at = from)), MemoryAuditSink())
        val failed = host.read().sources.single()
        assertEquals(Outcome.Unavailable, failed.outcome)
        assertEquals("quota", failed.detail)
        isError = false
        assertEquals(listOf("a"), host.read().items.map { it.id })
    }

    @Test
    fun aBodyThatIsNotJsonIsAProtocolError() = realTest {
        val c = McpHttpClient("https://x.example.com/mcp", client = mockClient { respond("<html>", HttpStatusCode.OK, jsonHeaders) })
        assertEquals("protocol", assertFailsWith<McpError> { c.initialize() }.kind)
    }

    @Test
    fun aSlowServerIsATimeout() = realTest {
        val c = McpHttpClient(
            "https://x.example.com/mcp",
            client = mockClient {
                delay(1_000)
                respond("{}", HttpStatusCode.OK, jsonHeaders)
            },
            timeout = 50.milliseconds,
        )
        assertEquals("timeout", assertFailsWith<McpError> { c.initialize() }.kind)
    }

    @Test
    fun aBuiltinManifestIsNotAnMcpConnector() {
        assertFailsWith<IllegalArgumentException> { McpConnector(FakeConnector("com.example.one").manifest) }
    }
}

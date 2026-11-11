package com.kahflabs.connect

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The few MCP calls a product makes of a remote connector (SPEC §2):
// `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, over
// streamable HTTP. Answers may come as JSON or as a server-sent event stream;
// both are read.
//
// Written here rather than taken from a package so the whole of what a
// product sends to a connector is in one short file anyone can read.
// Port of `dart/lib/src/mcp_client.dart`, on Ktor.

class McpTool(val name: String, val description: String = "", val inputSchema: JsonObject? = null)

class McpToolResult(
    /** `structuredContent`, which every Kahf capability answers with. */
    val structured: JsonElement? = null,
    /** The text content, joined: for errors and for plain MCP tools. */
    val text: String = "",
    val isError: Boolean = false,
)

/** A failure talking to the connector. [kind] is one of `http`, `rpc`, `protocol`, `timeout`, `network`. */
class McpError(val kind: String, override val message: String, val status: Int? = null, val code: Int? = null) :
    Exception(message) {
    override fun toString(): String =
        "McpError($kind${status?.let { " $it" } ?: ""}${code?.let { " $it" } ?: ""}: $message)"
}

/**
 * An MCP streamable-HTTP client. [client] defaults to Ktor's platform engine
 * (OkHttp on Android and the JVM, Darwin on iOS); [close] closes it either way.
 */
class McpHttpClient(
    val url: String,
    client: HttpClient? = null,
    val bearer: String? = null,
    val timeout: Duration = 20.seconds,
    val clientName: String = "kahf-connect",
    val clientVersion: String = "1.0.0",
) {
    private val http: HttpClient = client ?: HttpClient()
    private val lock = Mutex() // held while initializing
    private val ids = Mutex()

    @Volatile private var session: String? = null

    @Volatile private var protocol: String? = null
    private var next = 1

    val initialized: Boolean get() = protocol != null
    val protocolVersion: String? get() = protocol

    /** The server's `serverInfo` after [initialize]. */
    @Volatile var serverInfo: JsonObject? = null
        private set

    suspend fun initialize() {
        val r = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSIONS.first())
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject {
                    put("name", clientName)
                    put("version", clientVersion)
                })
            },
        )
        if (r !is JsonObject) throw McpError("protocol", "initialize answered ${r.show()}")
        val v = r["protocolVersion"].str()
        if (v == null || v !in PROTOCOL_VERSIONS) {
            throw McpError("protocol", "unsupported protocol version ${r["protocolVersion"].show()}")
        }
        protocol = v
        serverInfo = r["serverInfo"] as? JsonObject
        notify("notifications/initialized")
    }

    suspend fun listTools(): List<McpTool> {
        ready()
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        for (page in 0 until 20) {
            val r = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            val list = (r as? JsonObject)?.get("tools") as? JsonArray
                ?: throw McpError("protocol", "tools/list answered ${r.show()}")
            for (t in list) {
                val tool = t as? JsonObject ?: continue
                val name = tool["name"].str() ?: continue
                tools += McpTool(name, description = tool["description"].str() ?: "", inputSchema = tool["inputSchema"] as? JsonObject)
            }
            cursor = r["nextCursor"].str() ?: break
        }
        return tools
    }

    suspend fun callTool(name: String, args: JsonObject): McpToolResult {
        ready()
        val r = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        })
        if (r !is JsonObject) throw McpError("protocol", "tools/call answered ${r.show()}")
        val text = ((r["content"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { c -> (c as? JsonObject)?.takeIf { it["type"].str() == "text" }?.get("text").str() }
            .joinToString("\n")
        val isError = r["isError"].isTrue()
        var structured = r["structuredContent"]?.takeUnless { it.isNull() }
        // Older servers put the JSON in the text alone.
        if (structured == null && !isError && text.isNotEmpty()) structured = decodeOrNull(text)
        return McpToolResult(structured = structured, text = text, isError = isError)
    }

    fun close() = http.close()

    private suspend fun ready() = lock.withLock { if (!initialized) initialize() }

    private class Reply(val status: Int, val type: String, val body: String)

    private suspend fun post(message: JsonObject): Reply {
        val protocol = protocol
        val session = session
        try {
            return withTimeout(timeout) {
                val r = http.post(url) {
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    protocol?.let { header("mcp-protocol-version", it) }
                    session?.let { header("mcp-session-id", it) }
                    bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    setBody(TextContent(message.toString(), ContentType.Application.Json))
                }
                r.headers["mcp-session-id"]?.takeIf { it.isNotEmpty() }?.let { this@McpHttpClient.session = it }
                Reply(r.status.value, r.headers[HttpHeaders.ContentType] ?: "", r.bodyAsBytes().decodeToString())
            }
        } catch (e: TimeoutCancellationException) {
            throw McpError("timeout", "no answer from $url in $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever the engine throws (refused, reset, DNS, TLS) is the network's.
            throw McpError("network", e.message ?: (e::class.simpleName ?: "network"))
        }
    }

    private suspend fun notify(method: String) {
        val r = post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        })
        if (r.status >= 400) throw McpError("http", "$method refused", status = r.status)
    }

    private suspend fun request(method: String, params: JsonObject): JsonElement? {
        val id = ids.withLock { next++ }
        val r = post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        })
        if (r.status != 200) throw McpError("http", "$method: ${short(r.body)}", status = r.status)
        val message = if ("text/event-stream" in r.type) {
            fromStream(r.body, id)
        } else {
            // Dart lets jsonDecode's FormatException through here; a typed kind is kinder to the caller.
            decodeOrNull(r.body) as? JsonObject ?: throw McpError("protocol", "$method: not an object")
        }
        val error = message["error"]
        if (error is JsonObject) {
            throw McpError("rpc", error["message"].show(), code = error["code"].asNum()?.toInt())
        }
        if (message["id"].asNum() != id.toDouble()) throw McpError("protocol", "$method: wrong id")
        return message["result"]
    }

    companion object {
        /** The protocol versions this client speaks, newest first. */
        val PROTOCOL_VERSIONS: List<String> = listOf("2025-11-25", "2025-06-18", "2025-03-26")

        private val EVENT_BREAK = Regex("\\r?\\n\\r?\\n")
        private val LINE_BREAK = Regex("\\r?\\n")

        /** The JSON-RPC answer to [id] in a server-sent event stream. */
        private fun fromStream(body: String, id: Int): JsonObject {
            for (event in body.split(EVENT_BREAK)) {
                val data = event.split(LINE_BREAK)
                    .filter { it.startsWith("data:") }
                    .joinToString("\n") { it.substring(5).trimStart() }
                if (data.isEmpty()) continue
                val j = decodeOrNull(data) as? JsonObject ?: continue
                if (j["id"].asNum() == id.toDouble()) return j
            }
            throw McpError("protocol", "no answer to request $id in the stream")
        }

        private fun decodeOrNull(text: String): JsonElement? = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            null
        }

        private fun short(body: String) = if (body.length > 200) "${body.substring(0, 200)}…" else body
    }
}

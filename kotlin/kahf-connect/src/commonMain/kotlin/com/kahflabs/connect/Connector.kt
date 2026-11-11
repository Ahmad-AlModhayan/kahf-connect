package com.kahflabs.connect

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// A connector as a product holds it: one [Manifest] and the calls behind it.
// Remote, built-in and on-device connectors all look like this, so the
// product's code — and the [ConnectorHost] that guards it — never asks where
// one runs (SPEC §2.1). Port of `dart/lib/src/connector.dart`.

abstract class Connector {
    abstract val manifest: Manifest

    /** The capabilities (`id@version`) it can actually serve now. A remote connector asks the server; a built-in one knows. */
    abstract suspend fun available(): Set<String>

    /** `time.busy@1` in [from, to). The raw answer: the host checks it. */
    abstract suspend fun busy(from: Instant, to: Instant): JsonElement?

    /** `context.read@1`. */
    abstract suspend fun read(query: String = "", limit: Int = 20): JsonElement?

    open suspend fun close() {}
}

/**
 * Thrown by a connector that is reachable but cannot answer (a capability it
 * does not serve, a tool that answered `isError`).
 */
class ConnectorUnavailable(val reason: String) : Exception("ConnectorUnavailable($reason)")

/** A connector reached over MCP streamable HTTP. */
class McpConnector(
    override val manifest: Manifest,
    settings: Map<String, String> = emptyMap(),
    client: HttpClient? = null,
    timeout: Duration = 20.seconds,
) : Connector() {
    init {
        require(manifest.hostKind == HostKind.Remote) { "${manifest.id} is not a remote connector" }
    }

    private val mcp = McpHttpClient(
        manifest.hostUrl!!,
        client = client,
        timeout = timeout,
        bearer = if (manifest.authKind == AuthKind.Bearer) settings[manifest.authSetting] else null,
    )

    @Volatile private var tools: Set<String>? = null

    override suspend fun available(): Set<String> {
        val names = tools ?: mcp.listTools().map { it.name }.toSet().also { tools = it }
        return manifest.capabilities.filter { it.tool in names }.map { it.key }.toSet()
    }

    private suspend fun call(capability: String, args: JsonObject): JsonElement? {
        val decl = manifest.capability(capability) ?: throw ConnectorUnavailable("$capability not declared")
        if (decl.key !in available()) throw ConnectorUnavailable("${decl.tool} not served")
        val r = mcp.callTool(decl.tool, args)
        if (r.isError) throw ConnectorUnavailable(r.text)
        return r.structured
    }

    override suspend fun busy(from: Instant, to: Instant): JsonElement? = call(
        Capabilities.TIME_BUSY,
        buildJsonObject {
            put("from", from.toDartIso())
            put("to", to.toDartIso())
        },
    )

    override suspend fun read(query: String, limit: Int): JsonElement? = call(
        Capabilities.CONTEXT_READ,
        buildJsonObject {
            put("query", query)
            put("limit", limit)
        },
    )

    override suspend fun close() = mcp.close()
}

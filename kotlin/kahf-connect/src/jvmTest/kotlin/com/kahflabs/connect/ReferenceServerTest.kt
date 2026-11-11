package com.kahflabs.connect

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.junit.Assume.assumeTrue

/**
 * End to end against the reference connector (`reference/python/server.py`)
 * as the live MCP peer: the same check as the Dart SDK's.
 *
 * Runs against `KAHF_REFERENCE_URL` when set; otherwise starts the server
 * itself when `python3` has its packages (`pip install "mcp>=2.3,<3" uvicorn`),
 * and is skipped when it has not (Linux CI).
 */
class ReferenceServerTest {
    private var server: Process? = null

    @AfterTest
    fun stop() {
        server?.destroy()
    }

    private fun canRunServer(): Boolean = try {
        ProcessBuilder("python3", "-c", "from mcp.server.mcpserver import MCPServer; import uvicorn").redirectErrorStream(true).start().waitFor() == 0
    } catch (e: java.io.IOException) {
        false
    }

    private fun start(): String? {
        System.getenv("KAHF_REFERENCE_URL")?.let { return it }
        val script = File(System.getProperty("kahf.repoRoot") ?: "../..", "reference/python/server.py").canonicalFile
        if (!script.exists() || !canRunServer()) return null
        val port = ServerSocket(0).use { it.localPort }
        val p = ProcessBuilder("python3", script.path)
            .apply { environment()["PORT"] = "$port" }
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        server = p
        repeat(100) {
            if (!p.isAlive) error("the reference server exited with ${p.exitValue()}")
            try {
                Socket("127.0.0.1", port).close()
                return "http://127.0.0.1:$port/mcp"
            } catch (e: java.io.IOException) {
                Thread.sleep(100)
            }
        }
        error("the reference server did not listen on $port")
    }

    @Test
    fun theReferenceConnectorEndToEnd() {
        val url = start()
        assumeTrue("set KAHF_REFERENCE_URL, or install the reference server's packages, to run", url != null)
        realTest {
            val now = Clock.System.now()
            val c = McpConnector(remote(url = url!!), settings = mapOf("token" to "reference"))
            val host = ConnectorHost(
                listOf(c),
                listOf(
                    Grant("com.example.busy", "time.busy", labels = true, at = now),
                    Grant("com.example.busy", "context.read", agents = true, at = now),
                ),
                MemoryAuditSink(),
            )
            val busy = host.busy(now, now + 2.days)
            assertTrue(busy.complete, busy.sources.first().detail)
            assertTrue(busy.items.isNotEmpty())
            val read = host.read(query = "plan")
            assertTrue(read.complete, read.sources.first().detail)
            assertTrue(read.items.isNotEmpty())
            c.close()
        }
    }
}

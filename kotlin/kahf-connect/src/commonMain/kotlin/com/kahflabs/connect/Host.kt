package com.kahflabs.connect

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The product side of Kahf Connect: the one place a product calls its
// connectors through. It holds the person's grants and enforces them, checks
// every answer against its capability, strips what the grant does not allow,
// times calls out, keeps one connector's failure from touching another's
// answer, and writes an audit record for every call (SPEC §5–6).
// A product never calls a [Connector] directly. Port of `dart/lib/src/host.dart`.

/** The person's yes to one capability of one connector (SPEC §5). */
data class Grant(
    val connector: String,
    /** `id` of the capability, e.g. `time.busy`. */
    val capability: String,
    /** Keep labels (event titles) — off by default. */
    val labels: Boolean = false,
    /** Agents may see this connector's data — off by default. */
    val agents: Boolean = false,
    val at: Instant,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("connector", connector)
        put("capability", capability)
        put("labels", labels)
        put("agents", agents)
        put("at", at.toDartIso())
    }

    companion object {
        /** Reads what [toJson] wrote. Throws [IllegalArgumentException] on anything else. */
        fun fromJson(j: JsonObject): Grant = Grant(
            requireNotNull(j["connector"].str()) { "connector" },
            requireNotNull(j["capability"].str()) { "capability" },
            labels = j["labels"].isTrue(),
            agents = j["agents"].isTrue(),
            at = requireNotNull(j["at"].str()?.let(::parseDartTime)) { "at" },
        )
    }
}

enum class Outcome(val wire: String) {
    Ok("ok"),
    Error("error"),
    Denied("denied"),
    Unavailable("unavailable"),
    ;

    companion object {
        fun byWire(wire: String): Outcome =
            entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("outcome: $wire")
    }
}

/** One call, as the audit log keeps it: never the data, never a secret. */
data class AuditEvent(
    val at: Instant,
    val connector: String,
    val capability: String,
    val outcome: Outcome,
    val items: Int = 0,
    val ms: Long = 0,
    /** A short reason for a failure (an error's rule or kind), never data. */
    val detail: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("at", at.toDartIso())
        put("connector", connector)
        put("capability", capability)
        put("outcome", outcome.wire)
        put("items", items)
        put("ms", ms)
        if (detail.isNotEmpty()) put("detail", detail)
    }

    companion object {
        fun fromJson(j: JsonObject): AuditEvent = AuditEvent(
            at = requireNotNull(j["at"].str()?.let(::parseDartTime)) { "at" },
            connector = requireNotNull(j["connector"].str()) { "connector" },
            capability = requireNotNull(j["capability"].str()) { "capability" },
            outcome = Outcome.byWire(requireNotNull(j["outcome"].str()) { "outcome" }),
            items = j["items"].asNum()?.toInt() ?: 0,
            ms = j["ms"].asNum()?.toLong() ?: 0,
            detail = j["detail"].str() ?: "",
        )
    }
}

/** Where audit records go: the product's own storage. */
fun interface AuditSink {
    suspend fun record(e: AuditEvent)
}

class MemoryAuditSink : AuditSink {
    private val lock = Mutex()
    private val recorded = mutableListOf<AuditEvent>()

    /** What was recorded so far, oldest first. */
    val events: List<AuditEvent> get() = recorded.toList()

    override suspend fun record(e: AuditEvent) = lock.withLock { recorded += e }
}

/** One connector's part of an answer across connectors. */
class SourceResult<T>(
    val connector: Manifest,
    val outcome: Outcome,
    val items: List<T>,
    val detail: String = "",
)

/** What came back from every granted connector, each on its own. */
class Gathered<T>(val sources: List<SourceResult<T>>) {
    val items: List<T> get() = sources.flatMap { it.items }
    val complete: Boolean get() = sources.all { it.outcome == Outcome.Ok }
}

class ConnectorHost(
    connectors: List<Connector>,
    grants: List<Grant>,
    val audit: AuditSink,
    private val clock: () -> Instant = { Clock.System.now() },
    val timeout: Duration = 15.seconds,
) {
    val connectors: List<Connector> = connectors.toList()
    private val grants: List<Grant> = grants.toList()

    fun grant(connector: String, capability: String): Grant? =
        grants.firstOrNull { it.connector == connector && it.capability == capability }

    /** The connectors granted [capability] that declare it, and — when [forAgents] — whose grant lets agents see it. */
    fun granted(capability: String, forAgents: Boolean = false): List<Connector> = connectors.filter { c ->
        if (c.manifest.capability(capability) == null) return@filter false
        val g = grant(c.manifest.id, capability) ?: return@filter false
        !forAgents || g.agents
    }

    /**
     * Busy windows in [from, to) from every connector granted `time.busy`.
     * One connector failing leaves the others' windows; its part says why.
     */
    suspend fun busy(from: Instant, to: Instant, forAgents: Boolean = false): Gathered<BusyWindow> =
        gather(Capabilities.TIME_BUSY, forAgents) { c ->
            val windows = parseBusy(c.busy(from, to), from, to)
            val keep = grant(c.manifest.id, Capabilities.TIME_BUSY)!!.labels
            if (keep) windows else windows.map { it.withoutLabel() }
        }

    /** Items from every connector granted `context.read`. Agents only see connectors whose grant says so ([forAgents]). */
    suspend fun read(query: String = "", limit: Int = 20, forAgents: Boolean = true): Gathered<ContextItem> =
        gather(Capabilities.CONTEXT_READ, forAgents) { c -> parseContext(c.read(query, limit), limit = limit) }

    private suspend fun <T> gather(
        capability: String,
        forAgents: Boolean,
        call: suspend (Connector) -> List<T>,
    ): Gathered<T> = coroutineScope {
        val targets = granted(capability, forAgents)
        Gathered(targets.map { c -> async { one(c, capability) { call(c) } } }.awaitAll())
    }

    private suspend fun <T> one(c: Connector, capability: String, call: suspend () -> List<T>): SourceResult<T> {
        val started = clock()
        val mark = TimeSource.Monotonic.markNow()
        var outcome: Outcome
        var items = emptyList<T>()
        var detail = ""
        try {
            items = withTimeout(timeout) { call() }
            outcome = Outcome.Ok
        } catch (e: ConnectorUnavailable) {
            outcome = Outcome.Unavailable
            detail = clip(e.reason)
        } catch (e: CapabilityError) {
            outcome = Outcome.Error
            detail = "contract:${e.rule}"
        } catch (e: TimeoutCancellationException) {
            outcome = Outcome.Error
            detail = "timeout"
        } catch (e: CancellationException) {
            throw e // the caller gave up: not this connector's failure
        } catch (e: Exception) {
            outcome = Outcome.Error
            detail = clip(e::class.simpleName ?: "Exception")
        }
        audit.record(
            AuditEvent(
                at = started,
                connector = c.manifest.id,
                capability = capability,
                outcome = outcome,
                items = items.size,
                ms = mark.elapsedNow().inWholeMilliseconds,
                detail = detail,
            ),
        )
        return SourceResult(c.manifest, outcome, items, detail = detail)
    }

    private fun clip(s: String) = if (s.length > 120) s.substring(0, 120) else s
}

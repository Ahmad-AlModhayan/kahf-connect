package com.kahflabs.connect

import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The data each capability carries (SPEC §4), and the checks every answer
// passes before a product sees it. A connector's answer is outside data: it
// is parsed strictly, and one bad record fails the call rather than slipping
// through. Port of `dart/lib/src/capability.dart`.

/** A time the person is not free (`time.busy@1`). */
data class BusyWindow(
    val start: Instant,
    val end: Instant,
    /** Personal; dropped unless the grant keeps labels (SPEC §4). */
    val label: String? = null,
    val allDay: Boolean = false,
) {
    fun overlaps(from: Instant, to: Instant): Boolean = start < to && end > from

    fun withoutLabel(): BusyWindow = copy(label = null)

    fun toJson(): JsonObject = buildJsonObject {
        put("start", start.toDartIso())
        put("end", end.toDartIso())
        label?.let { put("label", it) }
        if (allDay) put("all_day", true)
    }

    companion object {
        /** Reads what [toJson] wrote. Throws [IllegalArgumentException] on anything else. */
        fun fromJson(j: JsonObject): BusyWindow = BusyWindow(
            requireNotNull(j["start"].str()?.let(::parseDartTime)) { "start" },
            requireNotNull(j["end"].str()?.let(::parseDartTime)) { "end" },
            label = j["label"].str(),
            allDay = j["all_day"].isTrue(),
        )
    }
}

/** Something an agent may read (`context.read@1`). */
data class ContextItem(
    val id: String,
    val title: String,
    val text: String,
    val url: String? = null,
    val updatedAt: Instant? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("text", text)
        url?.let { put("url", it) }
        updatedAt?.let { put("updated_at", it.toDartIso()) }
    }
}

/** An answer that breaks its capability's contract. [rule] is stable (the conformance fixtures name it). */
class CapabilityError(val rule: String, val detail: String = "") : Exception("CapabilityError($rule: $detail)")

private val OFFSET = Regex("(Z|[+-]\\d{2}:?\\d{2})$")

private fun time(raw: JsonElement?, field: String): Instant {
    val s = raw.str()
    if (s == null || !OFFSET.containsMatchIn(s)) throw CapabilityError("time-offset", "$field: ${raw.show()}")
    return parseDartTime(s) ?: throw CapabilityError("time", "$field: $s")
}

/** `time.busy@1` output, checked against [from, to). */
fun parseBusy(output: JsonElement?, from: Instant, to: Instant): List<BusyWindow> {
    val list = (output as? JsonObject)?.get("windows") as? JsonArray
        ?: throw CapabilityError("shape", "no windows")
    if (list.size > 2000) throw CapabilityError("too-many", "${list.size}")
    return list.mapIndexed { i, w ->
        if (w !is JsonObject) throw CapabilityError("shape", "windows[$i]")
        val start = time(w["start"], "windows[$i].start")
        val end = time(w["end"], "windows[$i].end")
        if (end <= start) throw CapabilityError("order", "windows[$i]")
        val label = w["label"]
        if (!label.isNull() && (label.str() == null || label.str()!!.length > 200)) {
            throw CapabilityError("label", "windows[$i]")
        }
        val window = BusyWindow(start, end, label = label.str(), allDay = w["all_day"].isTrue())
        if (!window.overlaps(from, to)) throw CapabilityError("out-of-range", "windows[$i]")
        window
    }
}

/** `context.read@1` output. */
fun parseContext(output: JsonElement?, limit: Int = 50): List<ContextItem> {
    val list = (output as? JsonObject)?.get("items") as? JsonArray
        ?: throw CapabilityError("shape", "no items")
    if (list.size > 50 || list.size > limit) throw CapabilityError("too-many", "${list.size}")
    val seen = mutableSetOf<String>()
    return list.mapIndexed { i, it ->
        if (it !is JsonObject) throw CapabilityError("shape", "items[$i]")
        val id = it["id"].str()
        val title = it["title"].str()
        val text = it["text"].str()
        if (id == null || id.isEmpty() || id.length > 200) throw CapabilityError("id", "items[$i]")
        if (!seen.add(id)) throw CapabilityError("id-duplicate", id)
        if (title == null || title.length > 300) throw CapabilityError("title", "items[$i]")
        if (text == null || text.length > 20000) throw CapabilityError("text", "items[$i]")
        val url = it["url"]
        if (!url.isNull() && url.str()?.let(ParsedUri::parse)?.hasScheme != true) {
            throw CapabilityError("url", "items[$i]")
        }
        val updated = it["updated_at"]
        ContextItem(
            id = id,
            title = title,
            text = text,
            url = url.str(),
            updatedAt = if (updated.isNull()) null else time(updated, "items[$i].updated_at"),
        )
    }
}

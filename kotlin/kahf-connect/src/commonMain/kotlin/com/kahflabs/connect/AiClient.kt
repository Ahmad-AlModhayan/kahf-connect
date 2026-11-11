package com.kahflabs.connect

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The BYO provider client: every AI request a product makes on the person's
// own key, to Claude, ChatGPT or Gemini, at the tier they chose, on that
// tier's newest model (docs/MODEL_TIERS.md). Port of Qasd's Dart
// `apps/mobile/lib/services/ai_client.dart`, on plain Ktor with no vendor SDK.
//
// Requests use only what every current model of the family accepts, because
// the model changes underneath them without a code change:
//  * Claude: `POST /v1/messages`; no `temperature`, `top_p` or `top_k` (400
//    since 2026-10-21), no prefill, no forced `tool_choice`, no `thinking`
//    (adaptive is the default); `output_config.effort` per tier and
//    `output_config.format` for JSON; `stop_reason` checked for `refusal`
//    before the content is read. No server-side fallback.
//  * ChatGPT: the Responses API, JSON through `text.format`.
//  * Gemini: `generateContent`, JSON through `responseSchema`, the key in a
//    header.
// A request whose model is gone resolves the tier again and retries once
// (ModelResolver.run); nothing else is retried. Nothing here logs: keys and
// prompts never leave the request.

/** One turn of the conversation. [role] is `user` or `assistant`; anything else is sent as `user`. */
data class AiMessage(val role: String, val text: String)

/** One answer from the person's own model. */
data class AiAnswer(
    /** The provider's HTTP status (200 when it answered). */
    val status: Int,
    /** The answer's text (JSON when a schema was asked for), or null. */
    val text: String?,
    /** The provider declined this request (Claude `stop_reason: refusal`, OpenAI a `refusal` part, Gemini a safety block). */
    val refused: Boolean = false,
    /** The model that answered, for the settings line that says what runs. */
    val model: String = "",
)

/** The model behind a request is gone ("model not found", "deprecated"). */
private class ModelGone(val status: Int) : Exception("model gone ($status)")

/**
 * Asks the person's own model. [client] defaults to Ktor's platform engine;
 * [models] resolves tiers, by default over the same client.
 */
class AiClient(client: HttpClient? = null, models: ModelResolver? = null) {
    private val http: HttpClient = client ?: HttpClient()
    val models: ModelResolver = models ?: ModelResolver(http)

    /**
     * Asks [messages] (oldest first) under [system]. With [schema], the
     * answer is JSON matching it. An HTTP error comes back as an [AiAnswer]
     * with the status and no text; network failures and a 200 whose body is
     * not JSON are thrown, as in the Dart client. No timeout of its own: set
     * one on [client] (Ktor `HttpTimeout`) if the product wants one.
     */
    suspend fun ask(
        vendor: ModelVendor,
        tier: ModelTier,
        key: String,
        system: String,
        messages: List<AiMessage>,
        schema: JsonObject? = null,
        schemaName: String = "answer",
        maxTokens: Int = 8000,
    ): AiAnswer = try {
        models.run(vendor, tier, key, isModelGone = { it is ModelGone }) { model ->
            when (vendor) {
                ModelVendor.anthropic -> claude(key, model, tier, system, messages, schema, maxTokens)
                ModelVendor.openai -> openai(key, model, system, messages, schema, schemaName, maxTokens)
                ModelVendor.google -> gemini(key, model, system, messages, schema, maxTokens)
            }
        }
    } catch (e: ModelGone) {
        AiAnswer(e.status, null)
    }

    private suspend fun post(url: String, headers: Map<String, String>, body: JsonObject): Pair<Int, String> {
        val r: HttpResponse = http.post(url) {
            for ((k, v) in headers) header(k, v)
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val text = r.bodyAsBytes().decodeToString()
        val status = r.status.value
        if (ModelResolver.modelGone(status, text)) throw ModelGone(status)
        return status to text
    }

    private fun role(m: AiMessage, assistant: String) = if (m.role == "assistant") assistant else "user"

    private suspend fun claude(
        key: String,
        model: String,
        tier: ModelTier,
        system: String,
        messages: List<AiMessage>,
        schema: JsonObject?,
        maxTokens: Int,
    ): AiAnswer {
        val (status, body) = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            buildJsonObject {
                put("model", model)
                put("max_tokens", maxTokens)
                putJsonObject("output_config") {
                    put("effort", tier.effort)
                    if (schema != null) {
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("schema", schema)
                        }
                    }
                }
                put("system", system)
                putJsonArray("messages") {
                    for (m in messages) addJsonObject {
                        put("role", role(m, "assistant"))
                        put("content", m.text)
                    }
                }
            },
        )
        if (status != 200) return AiAnswer(status, null, model = model)
        val j = Json.parseToJsonElement(body).jsonObject
        if (j["stop_reason"].str() == "refusal") return AiAnswer(200, null, refused = true, model = model)
        // Thinking blocks may come first; the answer is the text.
        val text = jsonObjects(j["content"]).filter { it["type"].str() == "text" }.mapNotNull { it["text"].str() }.joinToString("")
        return AiAnswer(200, text.ifEmpty { null }, model = j["model"].str() ?: model)
    }

    private suspend fun openai(
        key: String,
        model: String,
        system: String,
        messages: List<AiMessage>,
        schema: JsonObject?,
        schemaName: String,
        maxTokens: Int,
    ): AiAnswer {
        val (status, body) = post(
            "https://api.openai.com/v1/responses",
            mapOf("authorization" to "Bearer $key"),
            buildJsonObject {
                put("model", model)
                put("instructions", system)
                putJsonArray("input") {
                    for (m in messages) addJsonObject {
                        put("role", role(m, "assistant"))
                        put("content", m.text)
                    }
                }
                put("max_output_tokens", maxTokens)
                if (schema != null) {
                    putJsonObject("text") {
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("name", schemaName)
                            put("schema", schema)
                            put("strict", true)
                        }
                    }
                }
            },
        )
        if (status != 200) return AiAnswer(status, null, model = model)
        val j = Json.parseToJsonElement(body).jsonObject
        val parts = jsonObjects(j["output"]).filter { it["type"].str() == "message" }.flatMap { jsonObjects(it["content"]) }
        if (parts.any { it["type"].str() == "refusal" }) return AiAnswer(200, null, refused = true, model = model)
        val text = parts.filter { it["type"].str() == "output_text" }.mapNotNull { it["text"].str() }.joinToString("")
        return AiAnswer(200, text.ifEmpty { null }, model = j["model"].str() ?: model)
    }

    private suspend fun gemini(
        key: String,
        model: String,
        system: String,
        messages: List<AiMessage>,
        schema: JsonObject?,
        maxTokens: Int,
    ): AiAnswer {
        val (status, body) = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${model.encodeURLPathPart()}:generateContent",
            // The key goes in a header, never the URL.
            mapOf("x-goog-api-key" to key),
            buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", system) } }
                }
                putJsonArray("contents") {
                    for (m in messages) addJsonObject {
                        put("role", role(m, "model"))
                        putJsonArray("parts") { addJsonObject { put("text", m.text) } }
                    }
                }
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", maxTokens)
                    if (schema != null) {
                        put("responseMimeType", "application/json")
                        put("responseSchema", geminiSchema(schema))
                    }
                }
            },
        )
        if (status != 200) return AiAnswer(status, null, model = model)
        val j = Json.parseToJsonElement(body).jsonObject
        if (!(j["promptFeedback"] as? JsonObject)?.get("blockReason").isNull()) {
            return AiAnswer(200, null, refused = true, model = model)
        }
        val first = jsonObjects(j["candidates"]).firstOrNull() ?: return AiAnswer(200, null, model = model)
        if (first["finishReason"].str() in BLOCKED) return AiAnswer(200, null, refused = true, model = model)
        val text = jsonObjects((first["content"] as? JsonObject)?.get("parts"))
            .filter { !it["thought"].isTrue() }
            .mapNotNull { it["text"].str() }
            .joinToString("")
        return AiAnswer(200, text.ifEmpty { null }, model = j["modelVersion"].str() ?: model)
    }

    fun close() = http.close()

    companion object {
        private val BLOCKED = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")

        /** A JSON Schema as Gemini's `responseSchema` reads it: no `additionalProperties`, and `["string", "null"]` as `nullable`. */
        fun geminiSchema(s: JsonObject): JsonObject = buildJsonObject {
            for ((k, v) in s) {
                when {
                    k == "additionalProperties" || k == "\$schema" -> {}
                    k == "type" && v is JsonArray -> {
                        val types = v.filter { it.str() != "null" }
                        put("type", types.firstOrNull() ?: JsonPrimitive("string"))
                        if (v.any { it.str() == "null" }) put("nullable", true)
                    }
                    v is JsonObject && k == "properties" -> put(k, buildJsonObject {
                        for ((name, p) in v) put(name, if (p is JsonObject) geminiSchema(p) else p)
                    })
                    v is JsonObject -> put(k, geminiSchema(v))
                    else -> put(k, v)
                }
            }
        }
    }
}

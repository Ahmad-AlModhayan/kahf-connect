package com.kahflabs.connect

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Golden requests and answers per vendor, from Qasd's Dart
 * `apps/mobile/lib/services/ai_client.dart`. The key is a placeholder.
 */
class AiClientTest {
    private val key = "test-key-not-real"
    private val system = "You plan days."
    private val messages = listOf(AiMessage("user", "hi"), AiMessage("assistant", "hello"), AiMessage("user", "plan"))

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    /**
     * A provider whose model list is offline (so tiers resolve to the pinned
     * IDs) and whose answer to each generation call comes from [answer],
     * recording what it was sent.
     */
    private class Provider(val answer: (HttpRequestData, Int) -> Pair<Int, String>) {
        val calls = mutableListOf<HttpRequestData>()
        val client = mockClient { r ->
            if (r.method == HttpMethod.Get) return@mockClient respond("offline", HttpStatusCode.ServiceUnavailable)
            calls += r
            val (status, body) = answer(r, calls.size)
            respond(body, HttpStatusCode.fromValue(status), headersOf("content-type", "application/json"))
        }
        val ai = AiClient(client)
    }

    private val schema = """{"type": "object", "additionalProperties": false,
        "properties": {"title": {"type": ["string", "null"]}}, "required": ["title"]}"""

    @Test
    fun claudeRequestHasEffortPerTierNoTemperatureNoFallback() = realTest {
        val p = Provider { _, _ -> 200 to """{"model": "claude-sonnet-5-5", "stop_reason": "end_turn",
            "content": [{"type": "thinking", "thinking": "..."}, {"type": "text", "text": "Do "}, {"type": "text", "text": "this."}]}""" }
        val a = p.ai.ask(ModelVendor.anthropic, ModelTier.medium, key, system, messages, schema = obj(schema))
        assertEquals(AiAnswer(200, "Do this.", model = "claude-sonnet-5-5"), a)
        val r = p.calls.single()
        assertEquals("https://api.anthropic.com/v1/messages", r.url.toString())
        assertEquals(key, r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        assertNull(r.headers["anthropic-beta"], "no server-side fallback")
        assertEquals("application/json", r.body.contentType.toString())
        assertEquals(
            obj(
                """{"model": "claude-sonnet-5-5", "max_tokens": 8000,
                "output_config": {"effort": "medium", "format": {"type": "json_schema", "schema": $schema}},
                "system": "You plan days.",
                "messages": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}, {"role": "user", "content": "plan"}]}""",
            ),
            r.message(),
        )
    }

    @Test
    fun claudeWithoutSchemaSendsOnlyTheEffort() = realTest {
        val p = Provider { _, _ -> 200 to """{"stop_reason": "end_turn", "content": [{"type": "text", "text": "ok"}]}""" }
        val a = p.ai.ask(ModelVendor.anthropic, ModelTier.low, key, system, listOf(AiMessage("user", "hi")), maxTokens = 500)
        assertEquals(AiAnswer(200, "ok", model = "claude-haiku-5-5"), a, "the model sent when the answer names none")
        assertEquals(
            obj(
                """{"model": "claude-haiku-5-5", "max_tokens": 500, "output_config": {"effort": "low"},
                "system": "You plan days.", "messages": [{"role": "user", "content": "hi"}]}""",
            ),
            p.calls.single().message(),
        )
    }

    @Test
    fun claudeRefusalIsCheckedBeforeTheContent() = realTest {
        val p = Provider { _, _ -> 200 to """{"stop_reason": "refusal", "content": [{"type": "text", "text": "partial"}]}""" }
        val a = p.ai.ask(ModelVendor.anthropic, ModelTier.high, key, system, messages)
        assertEquals(AiAnswer(200, null, refused = true, model = "claude-opus-5-5"), a)
    }

    @Test
    fun openAiRequestIsTheResponsesApiWithJsonThroughTextFormat() = realTest {
        val p = Provider { _, _ -> 200 to """{"model": "gpt-6.1-terra-2026", "output": [
            {"type": "reasoning", "summary": []},
            {"type": "message", "content": [{"type": "output_text", "text": "{\"title\": \"x\"}"}]}]}""" }
        val a = p.ai.ask(ModelVendor.openai, ModelTier.medium, key, system, messages, schema = obj(schema), schemaName = "plan")
        assertEquals(AiAnswer(200, """{"title": "x"}""", model = "gpt-6.1-terra-2026"), a)
        val r = p.calls.single()
        assertEquals("https://api.openai.com/v1/responses", r.url.toString())
        assertEquals("Bearer $key", r.headers["authorization"])
        assertEquals(
            obj(
                """{"model": "gpt-6.1-terra", "instructions": "You plan days.",
                "input": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}, {"role": "user", "content": "plan"}],
                "max_output_tokens": 8000,
                "text": {"format": {"type": "json_schema", "name": "plan", "schema": $schema, "strict": true}}}""",
            ),
            r.message(),
        )
    }

    @Test
    fun openAiRefusalPart() = realTest {
        val p = Provider { _, _ -> 200 to """{"output": [{"type": "message", "content": [
            {"type": "output_text", "text": "x"}, {"type": "refusal", "refusal": "no"}]}]}""" }
        val a = p.ai.ask(ModelVendor.openai, ModelTier.low, key, system, messages)
        assertEquals(AiAnswer(200, null, refused = true, model = "gpt-6-luna"), a)
        assertFalse("text" in p.calls.single().message(), "no text.format without a schema")
    }

    @Test
    fun geminiRequestIsGenerateContentWithTheKeyInAHeader() = realTest {
        val p = Provider { _, _ -> 200 to """{"modelVersion": "gemini-4-pro-001", "candidates": [{"finishReason": "STOP",
            "content": {"parts": [{"text": "thinking", "thought": true}, {"text": "answer"}]}}]}""" }
        val a = p.ai.ask(ModelVendor.google, ModelTier.high, key, system, messages, schema = obj(schema))
        assertEquals(AiAnswer(200, "answer", model = "gemini-4-pro-001"), a)
        val r = p.calls.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro-latest:generateContent", r.url.toString())
        assertEquals(key, r.headers["x-goog-api-key"])
        assertFalse(key in r.url.toString(), "the key never goes in the URL")
        assertEquals(
            obj(
                """{"systemInstruction": {"parts": [{"text": "You plan days."}]},
                "contents": [{"role": "user", "parts": [{"text": "hi"}]}, {"role": "model", "parts": [{"text": "hello"}]},
                  {"role": "user", "parts": [{"text": "plan"}]}],
                "generationConfig": {"maxOutputTokens": 8000, "responseMimeType": "application/json",
                  "responseSchema": {"type": "object", "properties": {"title": {"type": "string", "nullable": true}}, "required": ["title"]}}}""",
            ),
            r.message(),
        )
    }

    @Test
    fun geminiSafetyBlocksAreRefusals() = realTest {
        for (body in listOf(
            """{"promptFeedback": {"blockReason": "SAFETY"}}""",
            """{"candidates": [{"finishReason": "PROHIBITED_CONTENT", "content": {"parts": [{"text": "x"}]}}]}""",
        )) {
            val p = Provider { _, _ -> 200 to body }
            assertEquals(AiAnswer(200, null, refused = true, model = "gemini-3.5-flash-lite"), p.ai.ask(ModelVendor.google, ModelTier.low, key, system, messages))
        }
        val empty = Provider { _, _ -> 200 to """{"candidates": []}""" }
        assertEquals(AiAnswer(200, null, model = "gemini-3.5-flash-lite"), empty.ai.ask(ModelVendor.google, ModelTier.low, key, system, messages))
    }

    @Test
    fun anErrorStatusIsSurfacedWithNoText() = realTest {
        val p = Provider { _, _ -> 429 to """{"error": {"message": "rate limited"}}""" }
        assertEquals(AiAnswer(429, null, model = "claude-haiku-5-5"), p.ai.ask(ModelVendor.anthropic, ModelTier.low, key, system, messages))
        assertEquals(1, p.calls.size, "no retry on errors other than a gone model")
    }

    @Test
    fun aGoneModelResolvesAgainAndRetriesOnce() = realTest {
        // The cache holds a model the provider retired; listing finds the new one.
        val cache = MemoryTierCache()
        val calls = mutableListOf<HttpRequestData>()
        val client = mockClient { r ->
            if (r.method == HttpMethod.Get) {
                return@mockClient respond(
                    """{"data": [{"id": "claude-haiku-5-5", "created_at": "2026-09-01T00:00:00Z"}], "has_more": false}""",
                    HttpStatusCode.OK,
                )
            }
            calls += r
            if (r.message()["model"].str() == "claude-haiku-4-5") {
                respond("""{"type":"error","error":{"type":"not_found_error","message":"model: claude-haiku-4-5"}}""", HttpStatusCode.NotFound)
            } else {
                respond("""{"stop_reason": "end_turn", "content": [{"type": "text", "text": "ok"}]}""", HttpStatusCode.OK)
            }
        }
        val now = kotlin.time.Clock.System.now()
        cache.write(ModelResolver.cacheKey(ModelVendor.anthropic, ModelTier.low), "claude-haiku-4-5", now)
        val ai = AiClient(client, ModelResolver(client, cache = cache))
        assertEquals(AiAnswer(200, "ok", model = "claude-haiku-5-5"), ai.ask(ModelVendor.anthropic, ModelTier.low, key, system, messages))
        assertEquals(listOf("claude-haiku-4-5", "claude-haiku-5-5"), calls.map { it.message()["model"].str() })
    }

    @Test
    fun aModelStillGoneAfterTheRetryIsSurfacedAsItsStatus() = realTest {
        val p = Provider { _, _ -> 404 to """{"error":{"code":"model_not_found"}}""" }
        assertEquals(AiAnswer(404, null), p.ai.ask(ModelVendor.openai, ModelTier.low, key, system, messages))
        assertEquals(1, p.calls.size, "resolving again gave the same pinned model, so no retry")
    }

    @Test
    fun geminiSchemaDropsWhatGeminiCannotRead() {
        val s = obj(
            """{"${'$'}schema": "x", "type": "object", "additionalProperties": false,
            "properties": {"a": {"type": ["null"]}, "b": {"type": "array", "items": {"type": ["integer", "null"], "additionalProperties": true}}}}""",
        )
        assertEquals(
            obj(
                """{"type": "object", "properties": {"a": {"type": "string", "nullable": true},
                "b": {"type": "array", "items": {"type": "integer", "nullable": true}}}}""",
            ),
            AiClient.geminiSchema(s),
        )
    }
}

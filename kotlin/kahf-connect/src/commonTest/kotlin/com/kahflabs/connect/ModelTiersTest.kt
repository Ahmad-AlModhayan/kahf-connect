package com.kahflabs.connect

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Port of `dart/test/model_tiers_test.dart` (MODEL_TIERS.md, "Resolving a tier"). */
class ModelTiersTest {
    private val t0 = Instant.parse("2026-10-27T09:00:00Z")

    /** An answer: a JSON body with status 200, or a status and raw body. */
    private class Answer(val body: String, val status: Int = 200)

    /** A provider that answers each host from [answers] (503 for any other host), recording requests. */
    private fun provider(answers: Map<String, Answer>): Pair<io.ktor.client.HttpClient, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val client = mockClient { r ->
            seen += r
            val a = answers[r.url.host] ?: Answer("offline", 503)
            respond(a.body, HttpStatusCode.fromValue(a.status), headersOf("content-type", "application/json"))
        }
        return client to seen
    }

    private val anthropicModels = Answer(
        """{"data": [
            {"id": "claude-haiku-4-5", "created_at": "2025-10-01T00:00:00Z"},
            {"id": "claude-haiku-5-5", "created_at": "2026-09-01T00:00:00Z"},
            {"id": "claude-sonnet-5-5", "created_at": "2026-09-01T00:00:00Z"},
            {"id": "claude-opus-5-5", "created_at": "2026-09-15T00:00:00Z"},
            {"id": "claude-opus-5", "created_at": "2026-05-01T00:00:00Z"},
            {"id": "claude-fable-5-1", "created_at": "2026-10-01T00:00:00Z"}
        ], "has_more": false}""",
    )

    @Test
    fun theTableThisSdkCarriesIsTheCatalogFile() {
        assertEquals(Fixtures.json("catalog/model-tiers.json"), Json.parseToJsonElement(bundledModelTiersJson))
        assertEquals("claude-haiku-5-5", TierTable.bundled.spec(ModelVendor.anthropic, ModelTier.low).pinned)
    }

    @Test
    fun theDefaultTierIsLowAndTheLabelsAreTheDocs() {
        assertEquals(ModelTier.low, ModelTier.parse(null))
        assertEquals(ModelTier.low, ModelTier.parse("nonsense"))
        assertEquals("Best", ModelTier.high.label("en"))
        assertEquals("متوازن", ModelTier.medium.label("ar"))
        assertEquals("high", ModelTier.high.effort)
    }

    @Test
    fun claudeNewestByCreatedAtInTheTiersFamilyOnly() = realTest {
        val (client, _) = provider(mapOf("api.anthropic.com" to anthropicModels))
        val r = ModelResolver(client, clock = { t0 })
        assertEquals("claude-haiku-5-5", r.resolve(ModelVendor.anthropic, ModelTier.low, "k").id)
        assertEquals("claude-opus-5-5", r.resolve(ModelVendor.anthropic, ModelTier.high, "k").id, "Fable is newer but not the high tier")
    }

    @Test
    fun claudeEveryPageIsRead() = realTest {
        var page = 0
        val client = mockClient { r ->
            page++
            val body = if (r.url.parameters["after_id"] == null) {
                """{"data": [{"id": "claude-sonnet-5", "created_at": "2026-03-01T00:00:00Z"}], "has_more": true, "last_id": "claude-sonnet-5"}"""
            } else {
                """{"data": [{"id": "claude-sonnet-5-5", "created_at": "2026-09-01T00:00:00Z"}], "has_more": false}"""
            }
            respond(body, HttpStatusCode.OK)
        }
        val r = ModelResolver(client, clock = { t0 })
        assertEquals("claude-sonnet-5-5", r.resolve(ModelVendor.anthropic, ModelTier.medium, "k").id)
        assertEquals(2, page)
    }

    @Test
    fun chatGptNewestByCreatedInTheFamilyPatternOnly() = realTest {
        val (client, seen) = provider(
            mapOf(
                "api.openai.com" to Answer(
                    """{"data": [
                        {"id": "gpt-6-luna", "created": 1780000000},
                        {"id": "gpt-6.1-luna", "created": 1790000000},
                        {"id": "gpt-6.1-luna-preview", "created": 1795000000},
                        {"id": "gpt-6.1-sol", "created": 1790000000}
                    ]}""",
                ),
            ),
        )
        val r = ModelResolver(client, clock = { t0 })
        assertEquals("gpt-6.1-luna", r.resolve(ModelVendor.openai, ModelTier.low, "k").id)
        assertEquals("gpt-6.1-sol", r.resolve(ModelVendor.openai, ModelTier.high, "k").id)
        assertEquals("Bearer k", seen.first().headers["authorization"])
    }

    @Test
    fun geminiTheLatestAliasWhenListedElseTheHighestVersion() = realTest {
        val (client, seen) = provider(
            mapOf(
                "generativelanguage.googleapis.com" to Answer(
                    """{"models": [
                        {"name": "models/gemini-3.5-flash-lite", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/gemini-4-flash-lite", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/gemini-flash-latest", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/gemini-4-flash", "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/text-embedding-9", "supportedGenerationMethods": ["embedContent"]}
                    ]}""",
                ),
            ),
        )
        val r = ModelResolver(client, clock = { t0 })
        assertEquals("gemini-4-flash-lite", r.resolve(ModelVendor.google, ModelTier.low, "gk").id)
        assertEquals("gemini-flash-latest", r.resolve(ModelVendor.google, ModelTier.medium, "gk").id)
        // The key travels in a header, never the URL.
        assertTrue(seen.all { "gk" !in it.url.toString() })
        assertEquals("gk", seen.first().headers["x-goog-api-key"])
    }

    @Test
    fun cachedForADayThenAskedAgain() = realTest {
        var now = t0
        val (client, seen) = provider(mapOf("api.anthropic.com" to anthropicModels))
        val r = ModelResolver(client, clock = { now })
        assertEquals(ModelSource.live, r.resolve(ModelVendor.anthropic, ModelTier.low, "k").source)
        now = t0 + 23.hours
        assertEquals(ModelSource.cache, r.resolve(ModelVendor.anthropic, ModelTier.low, "k").source)
        assertEquals(1, seen.size)
        now = t0 + 25.hours
        assertEquals(ModelSource.live, r.resolve(ModelVendor.anthropic, ModelTier.low, "k").source)
        assertEquals(2, seen.size)
    }

    @Test
    fun offlineTheStaleCacheAndWithNoCacheThePinnedId() = realTest {
        val cache = MemoryTierCache()
        cache.write(ModelResolver.cacheKey(ModelVendor.openai, ModelTier.low), "gpt-6.1-luna", t0 - 9.days)
        val (client, _) = provider(emptyMap())
        val r = ModelResolver(client, cache = cache, clock = { t0 })
        assertEquals(ResolvedModel("gpt-6.1-luna", ModelSource.cache), r.resolve(ModelVendor.openai, ModelTier.low, "k"))
        assertEquals(ResolvedModel("gemini-pro-latest", ModelSource.pinned), r.resolve(ModelVendor.google, ModelTier.high, "k"))
    }

    @Test
    fun aKeyThatMayNotListModelsFallsBackToo() = realTest {
        val (client, _) = provider(mapOf("api.anthropic.com" to Answer("""{"error":"forbidden"}""", 403)))
        val r = ModelResolver(client, clock = { t0 })
        assertEquals(ResolvedModel("claude-sonnet-5-5", ModelSource.pinned), r.resolve(ModelVendor.anthropic, ModelTier.medium, "k"))
    }

    @Test
    fun anExactModelThePersonPinnedIsUsedAsItIs() = realTest {
        val (client, seen) = provider(mapOf("api.anthropic.com" to anthropicModels))
        val r = ModelResolver(client, clock = { t0 })
        assertEquals(ResolvedModel("claude-opus-5", ModelSource.exact), r.resolve(ModelVendor.anthropic, ModelTier.low, "k", exact = "claude-opus-5"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aGoneModelDropsTheCacheResolvesAgainRetriesOnce() = realTest {
        val cache = MemoryTierCache()
        cache.write(ModelResolver.cacheKey(ModelVendor.anthropic, ModelTier.low), "claude-haiku-4-5", t0)
        val (client, _) = provider(mapOf("api.anthropic.com" to anthropicModels))
        val r = ModelResolver(client, cache = cache, clock = { t0 })
        val tried = mutableListOf<String>()
        val out = r.run(ModelVendor.anthropic, ModelTier.low, "k", isModelGone = { "model_not_found" in it.toString() }) { model ->
            tried += model
            if (model == "claude-haiku-4-5") throw IllegalStateException("model_not_found")
            "ok:$model"
        }
        assertEquals("ok:claude-haiku-5-5", out)
        assertEquals(listOf("claude-haiku-4-5", "claude-haiku-5-5"), tried)
    }

    @Test
    fun neverLoopsASecondFailureIsTheCallers() = realTest {
        val (client, _) = provider(mapOf("api.anthropic.com" to anthropicModels))
        val r = ModelResolver(client, clock = { t0 })
        var calls = 0
        assertFailsWith<IllegalStateException> {
            r.run(ModelVendor.anthropic, ModelTier.low, "k", isModelGone = { true }) {
                calls++
                throw IllegalStateException("model_not_found")
            }
        }
        assertEquals(1, calls, "resolving again gave the same model, so no retry")
    }

    @Test
    fun otherErrorsAreNotHealed() = realTest {
        val (client, _) = provider(mapOf("api.anthropic.com" to anthropicModels))
        val r = ModelResolver(client, clock = { t0 })
        var calls = 0
        assertFailsWith<IllegalStateException> {
            r.run(ModelVendor.anthropic, ModelTier.low, "k", isModelGone = { "model_not_found" in it.toString() }) {
                calls++
                throw IllegalStateException("rate limited")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun modelGoneReadsEachProvidersNotFound() {
        assertTrue(ModelResolver.modelGone(404, """{"type":"error","error":{"type":"not_found_error","message":"model: x"}}"""))
        assertTrue(ModelResolver.modelGone(404, """{"error":{"code":"model_not_found"}}"""))
        assertTrue(ModelResolver.modelGone(404, """{"error":{"status":"NOT_FOUND","message":"models/x is not found"}}"""))
        assertFalse(ModelResolver.modelGone(429, "rate limited"))
        assertFalse(ModelResolver.modelGone(400, "max_tokens too large"))
    }

    @Test
    fun aTableMissingAFieldIsRefusedByName() {
        val broken = bundledModelTiersJson.replace("\"pinned\": \"gpt-6-luna\", ", "")
        val e = assertFailsWith<IllegalArgumentException> { TierTable.parse(Json.parseToJsonElement(broken).jsonObject) }
        assertEquals("model-tiers: no openai.low.pinned", e.message)
    }
}

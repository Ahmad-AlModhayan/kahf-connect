package com.kahflabs.connect

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// Model tiers (`docs/MODEL_TIERS.md`): products name a tier (low, medium,
// high), never a model, and resolve it to the provider's newest model at run
// time, with the person's own key. Port of `dart/lib/src/model_tiers.dart`.
//
// [ModelResolver.resolve] does what the doc's "Resolving a tier" says: list
// the provider's models, keep the tier's pattern minus its exclusions, pick
// the newest; cache 24 hours; fall back to the cache and then the pinned ID;
// and [ModelResolver.run] heals once when a request says the model is gone.

@Suppress("EnumEntryName") // the names are the wire names in model-tiers.json
enum class ModelTier {
    low,
    medium,
    high;

    /** The words the person sees (MODEL_TIERS.md, "The choice the person sees"). */
    fun label(language: String): String = when (this) {
        low -> if (language == "ar") "سريع" else "Fast"
        medium -> if (language == "ar") "متوازن" else "Balanced"
        high -> if (language == "ar") "الأدق" else "Best"
    }

    /** Claude's `output_config.effort` for this tier. */
    val effort: String get() = name

    companion object {
        /** The tier for a stored name; the default (`low`) for anything else. */
        fun parse(name: String?): ModelTier = when (name) {
            "medium" -> medium
            "high" -> high
            else -> low
        }
    }
}

/** The providers a tier resolves on. Names as in `model-tiers.json`. */
@Suppress("EnumEntryName")
enum class ModelVendor { anthropic, openai, google }

class TierSpec(
    val pattern: Regex,
    val exclude: Regex? = null,
    val pinned: String,
    /** Google's always-newest alias (`gemini-flash-latest`), preferred when the list has it. */
    val alias: String? = null,
    val verified: Boolean,
) {
    fun matches(id: String): Boolean = pattern.containsMatchIn(id) && exclude?.containsMatchIn(id) != true
}

/** `catalog/model-tiers.json`. */
class TierTable private constructor(
    val version: Int,
    val checked: String,
    private val specs: Map<Pair<ModelVendor, ModelTier>, TierSpec>,
) {
    fun spec(vendor: ModelVendor, tier: ModelTier): TierSpec = specs.getValue(vendor to tier)

    companion object {
        /** The table this SDK was released with (`ModelTiersTable.kt`, kept equal to `catalog/model-tiers.json` by a test). */
        val bundled: TierTable by lazy { parse(Json.parseToJsonElement(bundledModelTiersJson).jsonObject) }

        /** Throws [IllegalArgumentException] when a provider, tier or field is missing (Dart: `FormatException`). */
        fun parse(json: JsonObject): TierTable {
            val providers = json["providers"] as? JsonObject
                ?: throw IllegalArgumentException("model-tiers: no providers")
            val specs = mutableMapOf<Pair<ModelVendor, ModelTier>, TierSpec>()
            for (vendor in ModelVendor.entries) {
                val p = providers[vendor.name] as? JsonObject
                    ?: throw IllegalArgumentException("model-tiers: no ${vendor.name}")
                val tiers = p["tiers"] as? JsonObject
                    ?: throw IllegalArgumentException("model-tiers: no ${vendor.name} tiers")
                for (tier in ModelTier.entries) {
                    val t = tiers[tier.name] as? JsonObject
                        ?: throw IllegalArgumentException("model-tiers: no ${vendor.name}.${tier.name}")
                    fun field(name: String) = t[name].str()
                        ?: throw IllegalArgumentException("model-tiers: no ${vendor.name}.${tier.name}.$name")
                    specs[vendor to tier] = TierSpec(
                        pattern = Regex(field("pattern")),
                        exclude = t["exclude"].str()?.let(::Regex),
                        pinned = field("pinned"),
                        alias = t["alias"].str(),
                        verified = t["verified"].isTrue(),
                    )
                }
            }
            val version = json["version"].asNum() ?: throw IllegalArgumentException("model-tiers: no version")
            return TierTable(version.toInt(), json["checked"].str() ?: "", specs)
        }
    }
}

/** One model a provider lists. [created] is when the provider says it was made (Anthropic `created_at`, OpenAI `created`); Google gives none. */
class ListedModel(val id: String, val created: Instant? = null)

/** Where a resolved ID came from. */
@Suppress("EnumEntryName")
enum class ModelSource { exact, live, cache, pinned }

data class ResolvedModel(val id: String, val source: ModelSource) {
    override fun toString(): String = "$id (${source.name})"
}

/** A resolved ID and when it was resolved. */
data class CachedModel(val id: String, val at: Instant)

/** Device-local storage for resolved IDs: never in backups or exports. */
interface TierCache {
    suspend fun read(key: String): CachedModel?
    suspend fun write(key: String, id: String, at: Instant)
    suspend fun drop(key: String)
}

class MemoryTierCache : TierCache {
    private val m = mutableMapOf<String, CachedModel>()
    override suspend fun read(key: String): CachedModel? = m[key]
    override suspend fun write(key: String, id: String, at: Instant) {
        m[key] = CachedModel(id, at)
    }
    override suspend fun drop(key: String) {
        m.remove(key)
    }
}

/** The objects in a JSON array; nothing for anything else (Dart: `as List? ?? const []`, then `if (m is Map)`). */
internal fun jsonObjects(e: JsonElement?): List<JsonObject> = (e as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

/** Listing models answered something other than 200. Carries no key and no body. */
class ModelListError(val status: Int) : Exception("listing models answered $status")

/**
 * Resolves tiers to model IDs. [client] defaults to Ktor's platform engine;
 * [close] closes it either way.
 */
class ModelResolver(
    client: HttpClient? = null,
    val cache: TierCache = MemoryTierCache(),
    val table: TierTable = TierTable.bundled,
    private val clock: () -> Instant = { Clock.System.now() },
    val timeout: Duration = 5.seconds,
    val ttl: Duration = 24.hours,
) {
    private val http: HttpClient = client ?: HttpClient()

    /** The model for [tier] on [vendor]. [exact], when the person pinned one, is used as it is and never changed. */
    suspend fun resolve(vendor: ModelVendor, tier: ModelTier, key: String, exact: String? = null): ResolvedModel {
        if (exact != null && exact.isNotBlank()) return ResolvedModel(exact.trim(), ModelSource.exact)
        val k = cacheKey(vendor, tier)
        val cached = cache.read(k)
        if (cached != null && clock() - cached.at < ttl) return ResolvedModel(cached.id, ModelSource.cache)
        val live = live(vendor, tier, key)
        if (live != null) {
            cache.write(k, live, clock())
            return ResolvedModel(live, ModelSource.live)
        }
        // Offline, no permission to list, nothing matched: the last good
        // answer, however old, beats the table.
        if (cached != null) return ResolvedModel(cached.id, ModelSource.cache)
        return ResolvedModel(table.spec(vendor, tier).pinned, ModelSource.pinned)
    }

    /**
     * Runs [call] with the resolved model. If it fails because the model is
     * gone ([isModelGone]), drops the cache, resolves again once, and retries
     * once. Never loops.
     */
    suspend fun <T> run(
        vendor: ModelVendor,
        tier: ModelTier,
        key: String,
        exact: String? = null,
        isModelGone: (Throwable) -> Boolean,
        call: suspend (model: String) -> T,
    ): T {
        val first = resolve(vendor, tier, key, exact)
        try {
            return call(first.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isModelGone(e) || first.source == ModelSource.exact) throw e
            cache.drop(cacheKey(vendor, tier))
            val again = resolve(vendor, tier, key)
            if (again.id == first.id) throw e
            return call(again.id)
        }
    }

    private suspend fun live(vendor: ModelVendor, tier: ModelTier, key: String): String? {
        val models = try {
            withTimeoutOrNull(timeout) { list(vendor, key) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return pickNewest(vendor, table.spec(vendor, tier), models)
    }

    /** Every model [vendor] lists for [key], all pages. Throws [ModelListError] on a non-200 answer. */
    suspend fun list(vendor: ModelVendor, key: String): List<ListedModel> = when (vendor) {
        ModelVendor.anthropic -> anthropic(key)
        ModelVendor.openai -> openai(key)
        ModelVendor.google -> google(key)
    }

    private suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): JsonObject {
        val r = http.get(url) {
            for ((k, v) in query) this.url.parameters.append(k, v)
            for ((k, v) in headers) header(k, v)
        }
        if (r.status.value != 200) throw ModelListError(r.status.value)
        return Json.parseToJsonElement(r.bodyAsBytes().decodeToString()).jsonObject
    }

    private suspend fun anthropic(key: String): List<ListedModel> {
        val out = mutableListOf<ListedModel>()
        var after: String? = null
        repeat(20) {
            val j = get(
                "https://api.anthropic.com/v1/models",
                buildMap {
                    put("limit", "1000")
                    if (after != null) put("after_id", after!!)
                },
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            )
            for (m in jsonObjects(j["data"])) {
                val id = m["id"].str() ?: continue
                out += ListedModel(id, m["created_at"].str()?.let(::parseDartTime))
            }
            if (!j["has_more"].isTrue()) return out
            after = j["last_id"].str() ?: return out
        }
        return out
    }

    private suspend fun openai(key: String): List<ListedModel> {
        val j = get("https://api.openai.com/v1/models", emptyMap(), mapOf("authorization" to "Bearer $key"))
        return jsonObjects(j["data"]).mapNotNull { m ->
            val id = m["id"].str() ?: return@mapNotNull null
            ListedModel(id, m["created"].asNum()?.let { Instant.fromEpochSeconds(it.toLong()) })
        }
    }

    private suspend fun google(key: String): List<ListedModel> {
        val out = mutableListOf<ListedModel>()
        var token: String? = null
        repeat(20) {
            // The key goes in a header, never the URL (MODEL_TIERS.md).
            val j = get(
                "https://generativelanguage.googleapis.com/v1beta/models",
                buildMap {
                    put("pageSize", "1000")
                    if (token != null) put("pageToken", token!!)
                },
                mapOf("x-goog-api-key" to key),
            )
            for (m in jsonObjects(j["models"])) {
                val name = m["name"].str() ?: continue
                val methods = m["supportedGenerationMethods"] as? JsonArray
                if (methods != null && methods.none { it.str() == "generateContent" }) continue
                out += ListedModel(name.replaceFirst("models/", ""))
            }
            token = j["nextPageToken"].str()
            if (token.isNullOrEmpty()) return out
        }
        return out
    }

    fun close() = http.close()

    companion object {
        fun cacheKey(vendor: ModelVendor, tier: ModelTier): String = "model-tier.${vendor.name}.${tier.name}"

        /** Whether an HTTP answer means "that model does not exist (any more)". */
        fun modelGone(status: Int, body: String): Boolean {
            if (status != 404 && status != 400) return false
            val b = body.lowercase()
            return "not_found" in b || "model_not_found" in b || "not found" in b || "deprecated" in b ||
                (status == 404 && "model" in b)
        }

        /** The newest of [models] for [spec] (MODEL_TIERS.md step 1), or null. */
        fun pickNewest(vendor: ModelVendor, spec: TierSpec, models: List<ListedModel>): String? {
            if (vendor == ModelVendor.google && spec.alias != null && models.any { it.id == spec.alias }) {
                return spec.alias
            }
            val fit = models.filter { spec.matches(it.id) }
            if (fit.isEmpty()) return null
            if (vendor == ModelVendor.google) {
                return fit.sortedWith { a, b -> compareVersions(version(b.id), version(a.id)) }.first().id
            }
            return fit.sortedWith { a, b ->
                val ca = a.created
                val cb = b.created
                when {
                    ca == null && cb == null -> b.id.compareTo(a.id)
                    ca == null -> 1
                    cb == null -> -1
                    else -> cb.compareTo(ca).takeIf { it != 0 } ?: b.id.compareTo(a.id)
                }
            }.first().id
        }

        private val DIGITS = Regex("\\d+")

        private fun version(id: String): List<Long> = DIGITS.findAll(id).map { it.value.toLong() }.toList()

        private fun compareVersions(a: List<Long>, b: List<Long>): Int {
            for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return a.size.compareTo(b.size)
        }
    }
}

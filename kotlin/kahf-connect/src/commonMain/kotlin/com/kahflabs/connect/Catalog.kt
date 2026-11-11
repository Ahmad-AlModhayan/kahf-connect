package com.kahflabs.connect

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// The catalog of connectors Kahf Labs has reviewed (SPEC §8): signed with
// Ed25519 over the exact bytes of `catalog.json`, so a catalog fetched over
// any network, from any mirror, is only believed when the key says so.
// Port of `dart/lib/src/catalog.dart`.

/**
 * The keys catalogs are signed with: `key_id` → base64 Ed25519 public key.
 * A new key is added here before it signs anything; a retired one stays
 * until every catalog it signed has been replaced.
 */
object KahfKeys {
    val catalog: Map<String, String> = mapOf(
        "kahf-2026-1" to "yYJg7VinUy6h9gipuzXpxuKUROJ5+xrcQxkVLrjVF5s=",
    )
}

class CatalogEntry(val manifest: Manifest) {
    /** Every catalog entry was reviewed; one added by address was not. */
    val reviewed: Boolean get() = true
}

class Catalog private constructor(
    val issuedAt: Instant,
    val keyId: String,
    val entries: List<CatalogEntry>,
) {
    fun entry(id: String): CatalogEntry? = entries.firstOrNull { it.manifest.id == id }

    companion object {
        /** Dart's `base64.decode`: either alphabet, even mixed; padding required. */
        private fun decode64(s: String): ByteArray? =
            runCatching { Base64.Default.decode(s.replace('-', '+').replace('_', '/')) }.getOrNull()

        /**
         * Verifies [signature] (base64) over [bytes] with the key the catalog
         * names, then parses it. Throws [CatalogError] when either fails; an
         * entry with a bad manifest fails the whole catalog, so a signed
         * catalog is all good or not used.
         */
        suspend fun verify(bytes: ByteArray, signature: String, keys: Map<String, String> = KahfKeys.catalog): Catalog {
            val json = try {
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (_: CharacterCodingException) {
                throw CatalogError("json")
            } catch (_: SerializationException) {
                throw CatalogError("json")
            }
            if (json !is JsonObject) throw CatalogError("shape")
            if (json["kahf_connect"].str() != "1") throw CatalogError("version-of-spec")
            val keyId = json["key_id"].str()
            val key = keyId?.let { keys[it] } ?: throw CatalogError("key-unknown", json["key_id"].show())

            val sig = decode64(signature.trim()) ?: throw CatalogError("signature")
            if (!ed25519Verify(key, bytes, sig)) throw CatalogError("signature")

            val at = json["issued_at"].str()?.let(::parseDartTime) ?: throw CatalogError("issued-at")
            val raw = json["entries"] as? JsonArray ?: throw CatalogError("shape")
            val entries = raw.map { e ->
                val manifest = (e as? JsonObject)?.get("manifest")
                if (e !is JsonObject || !e["reviewed"].isTrue() || manifest !is JsonObject) throw CatalogError("entry")
                try {
                    CatalogEntry(Manifest.parse(manifest))
                } catch (m: ManifestError) {
                    throw CatalogError("entry-manifest", "${m.rule}: ${m.detail}")
                }
            }
            val ids = mutableSetOf<String>()
            for (e in entries) if (!ids.add(e.manifest.id)) throw CatalogError("entry-duplicate", e.manifest.id)
            return Catalog(at, keyId, entries)
        }

        /** False for a bad signature, and for one of the wrong length or shape. */
        private suspend fun ed25519Verify(publicKey: String, data: ByteArray, signature: ByteArray): Boolean {
            val keyBytes = Base64.Default.decode(publicKey)
            return try {
                CryptographyProvider.Default.get(EdDSA)
                    .publicKeyDecoder(EdDSA.Curve.Ed25519)
                    .decodeFromByteArray(EdDSA.PublicKey.Format.RAW, keyBytes)
                    .signatureVerifier()
                    .tryVerifySignature(data, signature)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false // the provider refused the key or signature bytes
            }
        }
    }
}

class CatalogError(val rule: String, val detail: String = "") : Exception("CatalogError($rule: $detail)")

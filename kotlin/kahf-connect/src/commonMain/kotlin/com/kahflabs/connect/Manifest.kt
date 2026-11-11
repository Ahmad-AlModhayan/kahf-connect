package com.kahflabs.connect

import io.ktor.http.Url
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// A connector's manifest (SPEC §3): who it is, where it runs, how it
// authenticates and what it supplies. Parsed strictly: anything the schema
// forbids is a [ManifestError] naming the rule, so every SDK agrees on the
// same verdicts over `fixtures/manifests`. Port of `dart/lib/src/manifest.dart`.

/** Text in several languages; `en` is always there. */
class Localised(values: Map<String, String>) {
    val values: Map<String, String> = values.toMap()

    /** In [language] when given, else English. */
    fun of(language: String): String = values[language] ?: values.getValue("en")

    fun toJson(): JsonObject = JsonObject(values.mapValues { JsonPrimitive(it.value) })

    companion object {
        private val LANG = Regex("^[a-z]{2}(-[A-Z]{2})?$")

        fun parse(raw: JsonElement?, field: String): Localised {
            if (raw !is JsonObject) throw ManifestError("localised", "$field is not a map")
            val out = LinkedHashMap<String, String>()
            for ((k, v) in raw) {
                if (!LANG.matches(k)) throw ManifestError("localised", "$field has a bad language \"$k\"")
                val s = v.str()
                if (s.isNullOrEmpty()) throw ManifestError("localised", "$field.$k is empty")
                out[k] = s
            }
            if ("en" !in out) throw ManifestError("localised", "$field has no \"en\"")
            return Localised(out)
        }
    }
}

enum class HostKind { Remote, Builtin, Device }

enum class AuthKind { None, Bearer, OAuth2 }

enum class SettingKind(val wire: String) { Text("text"), Url("url"), Secret("secret"), SecretUrl("secret-url") }

/** The capabilities this version of the SDK knows (SPEC §4). */
object Capabilities {
    const val TIME_BUSY = "time.busy"
    const val CONTEXT_READ = "context.read"
    const val ACTIONS_PROPOSE = "actions.propose"

    val known: Set<String> = setOf(TIME_BUSY, CONTEXT_READ, ACTIONS_PROPOSE)

    /** The MCP tool a capability is served as: `time.busy@1` → `kahf_time_busy_v1`. */
    fun tool(id: String, version: Long): String = "kahf_${id.replace('.', '_')}_v$version"
}

class CapabilityDecl(val id: String, val version: Long, val reason: Localised) {
    val tool: String get() = Capabilities.tool(id, version)
    val key: String get() = "$id@$version"

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
        put("reason", reason.toJson())
    }
}

class SettingDecl(
    val key: String,
    val kind: SettingKind,
    val label: Localised,
    val help: Localised? = null,
    val required: Boolean = false,
) {
    /** Kept in secure storage, never logged or synced (SPEC §3). */
    val secret: Boolean get() = kind == SettingKind.Secret || kind == SettingKind.SecretUrl

    fun toJson(): JsonObject = buildJsonObject {
        put("key", key)
        put("kind", kind.wire)
        put("label", label.toJson())
        help?.let { put("help", it.toJson()) }
        if (required) put("required", true)
    }
}

class Manifest private constructor(
    val id: String,
    val name: Localised,
    val description: Localised,
    val version: String,
    val authorName: String,
    val authorUrl: String?,
    val homepage: String?,
    val privacyUrl: String?,
    val hostKind: HostKind,
    val hostUrl: String?,
    val hostPackage: String?,
    val authKind: AuthKind,
    val authSetting: String?,
    val authorizeUrl: String?,
    val tokenUrl: String?,
    val scopes: List<String>,
    val capabilities: List<CapabilityDecl>,
    val settings: List<SettingDecl>,
    /** The manifest as it was given, for storing and re-parsing. */
    val raw: JsonObject,
) {
    fun capability(id: String): CapabilityDecl? = capabilities.firstOrNull { it.id == id }

    fun toJson(): JsonObject = raw

    companion object {
        private val ID = Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
        private val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private val SETTING_KEY = Regex("^[a-z][a-z0-9_]*$")

        private val TOP = setOf(
            "kahf_connect", "id", "name", "description", "version", "author",
            "homepage", "privacy_url", "host", "auth", "capabilities", "settings",
        )

        /** Parses and checks [json]. Throws [ManifestError] on the first broken rule. */
        fun parse(json: JsonObject): Manifest {
            for (k in json.keys) if (k !in TOP) throw ManifestError("unknown-field", k)
            if (json["kahf_connect"].str() != "1") throw ManifestError("version-of-spec", json["kahf_connect"].show())
            val id = json["id"].str()
            if (id == null || id.length > 128 || !ID.matches(id)) throw ManifestError("id", json["id"].show())
            val version = json["version"].str()
            if (version == null || !VERSION.matches(version)) throw ManifestError("version", json["version"].show())
            val author = json["author"]
            val authorName = (author as? JsonObject)?.get("name").str()
            if (author !is JsonObject || authorName.isNullOrEmpty() || author.keys.any { it != "name" && it != "url" }) {
                throw ManifestError("author", author.show())
            }

            val host = json["host"] as? JsonObject ?: throw ManifestError("host", "missing")
            val hostKind = when (host["kind"].str()) {
                "remote" -> HostKind.Remote
                "builtin" -> HostKind.Builtin
                "device" -> HostKind.Device
                else -> throw ManifestError("host", "kind \"${host["kind"].show()}\"")
            }
            val hostUrl = uri(host["url"], "host.url")
            if (hostKind == HostKind.Remote) {
                if (hostUrl == null) throw ManifestError("host-url", "remote needs url")
                if (!secure(hostUrl)) throw ManifestError("host-https", hostUrl.text)
            }
            val pkg = host["package"].str()
            if (hostKind == HostKind.Device && (pkg == null || !PACKAGE.matches(pkg))) {
                throw ManifestError("host-package", host["package"].show())
            }

            val auth = json["auth"] as? JsonObject ?: throw ManifestError("auth", "missing")
            val authKind = when (auth["kind"].str()) {
                "none" -> AuthKind.None
                "bearer" -> AuthKind.Bearer
                "oauth2" -> AuthKind.OAuth2
                else -> throw ManifestError("auth", "kind \"${auth["kind"].show()}\"")
            }

            val caps = json["capabilities"]
            if (caps !is JsonArray || caps.isEmpty()) throw ManifestError("capabilities", "none declared")
            val capabilities = mutableListOf<CapabilityDecl>()
            for (c in caps) {
                if (c !is JsonObject) throw ManifestError("capabilities", c.show())
                val cid = c["id"].str()
                if (cid == null || cid !in Capabilities.known) throw ManifestError("capability-unknown", c["id"].show())
                val v = c["version"].asInt()
                if (v == null || v < 1) throw ManifestError("capability-version", c["version"].show())
                if (c["reason"].isNull()) throw ManifestError("capability-reason", cid)
                val decl = CapabilityDecl(cid, v, Localised.parse(c["reason"], "$cid.reason"))
                if (capabilities.any { it.id == cid }) throw ManifestError("capability-duplicate", cid)
                capabilities += decl
            }

            val settings = mutableListOf<SettingDecl>()
            val rawSettings = json["settings"]
            if (!rawSettings.isNull()) {
                if (rawSettings !is JsonArray) throw ManifestError("settings", "not a list")
                for (s in rawSettings) {
                    if (s !is JsonObject) throw ManifestError("settings", s.show())
                    val key = s["key"].str()
                    if (key == null || !SETTING_KEY.matches(key)) throw ManifestError("setting-key", s["key"].show())
                    if (settings.any { it.key == key }) throw ManifestError("setting-duplicate", key)
                    val kind = SettingKind.entries.firstOrNull { it.wire == s["kind"].str() }
                        ?: throw ManifestError("setting-kind", s["kind"].show())
                    settings += SettingDecl(
                        key,
                        kind,
                        Localised.parse(s["label"], "settings.$key.label"),
                        help = if (s["help"].isNull()) null else Localised.parse(s["help"], "settings.$key.help"),
                        required = s["required"].isTrue(),
                    )
                }
            }

            val authSetting = auth["setting"].str()
            if (authKind == AuthKind.Bearer) {
                if (authSetting == null) throw ManifestError("auth-setting", "bearer needs a setting")
                val s = settings.firstOrNull { it.key == authSetting }
                if (s == null || !s.secret) throw ManifestError("auth-setting", "bearer token must be a secret setting")
            }
            val authorizeUrl = uri(auth["authorize_url"], "auth.authorize_url")
            val tokenUrl = uri(auth["token_url"], "auth.token_url")
            if (authKind == AuthKind.OAuth2 && (authorizeUrl == null || tokenUrl == null)) {
                throw ManifestError("auth-oauth2", "needs authorize_url and token_url")
            }
            for (u in listOfNotNull(authorizeUrl, tokenUrl)) {
                if (!secure(u)) throw ManifestError("auth-oauth2-https", u.text)
            }

            // Same order as the Dart constructor's arguments, so the first broken rule matches.
            val name = Localised.parse(json["name"], "name")
            val description = Localised.parse(json["description"], "description")
            val authorUrl = uri(author["url"], "author.url")
            val homepage = uri(json["homepage"], "homepage")
            val privacyUrl = uri(json["privacy_url"], "privacy_url")
            val scopes = (auth["scopes"] as? JsonArray)?.map { it.show() } ?: emptyList()

            return Manifest(
                id = id,
                name = name,
                description = description,
                version = version,
                authorName = authorName,
                authorUrl = authorUrl?.text,
                homepage = homepage?.text,
                privacyUrl = privacyUrl?.text,
                hostKind = hostKind,
                hostUrl = hostUrl?.text,
                hostPackage = pkg,
                authKind = authKind,
                authSetting = authSetting,
                authorizeUrl = authorizeUrl?.text,
                tokenUrl = tokenUrl?.text,
                scopes = scopes,
                capabilities = capabilities.toList(),
                settings = settings.toList(),
                raw = json,
            )
        }

        /**
         * HTTPS, or plain HTTP on this machine for development. Judged on the scheme and
         * host of the Ktor [Url] the client will request, not on a second parse.
         */
        private fun secure(u: Link): Boolean {
            val scheme = u.url.protocol.name.lowercase()
            val host = u.url.host.lowercase()
            return scheme == "https" || (scheme == "http" && (host == "localhost" || host == "127.0.0.1"))
        }

        /** A URI field: its text as given, and the URL the client will request for it. */
        private class Link(val text: String, val url: Url)

        /** The text and its URL; null when absent. */
        private fun uri(raw: JsonElement?, field: String): Link? {
            if (raw.isNull()) return null
            val s = raw.str()
            val u = s?.let(::checkedUrl) ?: throw ManifestError("uri", "$field: ${raw.show()}")
            return Link(s, u)
        }
    }
}

/**
 * A manifest that breaks a rule. [rule] is stable: the conformance fixtures
 * name it, so every SDK must throw the same one.
 */
class ManifestError(val rule: String, val detail: String = "") : Exception("ManifestError($rule: $detail)")

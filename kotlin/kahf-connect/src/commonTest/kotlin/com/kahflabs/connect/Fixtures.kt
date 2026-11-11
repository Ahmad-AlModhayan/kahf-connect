package com.kahflabs.connect

import kotlin.io.encoding.Base64
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The repository's files under `fixtures/` and `catalog/`, by path from the repo root. */
internal object Fixtures {
    fun bytes(path: String): ByteArray =
        Base64.decode(kahfFixtureFiles[path] ?: fail("no fixture $path"))

    fun text(path: String): String = bytes(path).decodeToString()

    fun json(path: String): JsonElement = Json.parseToJsonElement(text(path))

    fun obj(path: String): JsonObject = json(path).jsonObject

    /** Files directly in [dir], sorted by name. */
    fun list(dir: String): List<String> =
        kahfFixtureFiles.keys
            .filter { it.startsWith("$dir/") && '/' !in it.removePrefix("$dir/") }
            .sorted()
}

/**
 * Runs every case and fails once with all the cases that broke, so one red
 * case never hides another.
 */
internal class Cases(private val group: String) {
    private val failures = mutableListOf<String>()
    private var count = 0

    fun case(name: String, body: () -> Unit) {
        count++
        try {
            body()
        } catch (e: Throwable) {
            failures += "$name: ${e.message ?: e}"
        }
    }

    fun done() {
        println("$group: ${count - failures.size}/$count fixture cases pass")
        if (failures.isNotEmpty()) fail("$group:\n" + failures.joinToString("\n"))
        if (count == 0) fail("$group: no cases ran")
    }
}

/** Asserts [body] throws [T] whose rule is [rule]. */
internal inline fun <reified T : Throwable> assertRule(rule: String, ruleOf: (T) -> String, body: () -> Unit) {
    val e = try {
        body()
        null
    } catch (e: Throwable) {
        e
    }
    when {
        e == null -> fail("expected rule $rule, but it passed")
        e !is T -> fail("expected ${T::class.simpleName}($rule), got $e")
        ruleOf(e) != rule -> fail("expected rule $rule, got ${ruleOf(e)} ($e)")
    }
}

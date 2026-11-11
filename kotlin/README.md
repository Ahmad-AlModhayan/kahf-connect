# Kahf Connect: Kotlin SDK

A Kotlin Multiplatform port of the Dart SDK in `../dart`, for apps built with Compose Multiplatform. One module, `kahf-connect`, package `com.kahflabs.connect`.

Covers the manifest (SPEC §3), the `time.busy@1` and `context.read@1` checks (SPEC §4), the signed catalog (SPEC §8), the connector, `ConnectorHost` with grants and audit (SPEC §5–6), the MCP client, the calendar-feed builtin and model tiers. Every rule string and error kind is the Dart SDK's, so both SDKs give the same verdict on the same input.

- `McpHttpClient` speaks MCP streamable HTTP on Ktor and reads JSON and event-stream answers. With no client given it takes the platform engine: OkHttp on Android and the JVM, Darwin on iOS.
- Model tiers (`docs/MODEL_TIERS.md`): `ModelResolver` resolves a `ModelTier` on a `ModelVendor` to the newest model with the person's key, caches it through a `TierCache`, and heals once on a gone model. The bundled table `ModelTiersTable.kt` is generated from `catalog/model-tiers.json` by `./gradlew :kahf-connect:syncModelTiers`; `ModelTiersTest` fails if they differ. `AiClient` is the bring-your-own-key provider client (Anthropic `/v1/messages`, OpenAI `/v1/responses`, Gemini `generateContent`) on plain Ktor.
- `CalendarFeedConnector` takes a `FeedParser`; the default is the ICS parser below.

## Install

Not yet published to a Maven repository. Include this Gradle build from a checkout (JDK 17).

## Build and test

```sh
./gradlew check   # JVM + Android host tests; no credentials, plain Gradle
```

- Targets: `jvm` (fast conformance runs), Android (`minSdk` 23), and `iosArm64` + `iosSimulatorArm64` when Xcode is installed (or `-Pkahf.ios=true`). Linux CI builds JVM and Android only.
- Ed25519 goes through cryptography-kotlin: the JDK provider over Bouncy Castle on Android and the JVM (`cryptography-provider-jdk-bc` registers BC itself), CryptoKit on iOS. An Android app that shrinks with R8 keeps the BC provider classes (cryptography-kotlin's `proguard-r8.md`).
- Versions live in `gradle/libs.versions.toml` and pin Kotlin 2.4.21, kotlinx.serialization, kotlinx-datetime 0.8.0 and Ktor 3.6.1.

## ICS parser

`IcsCalendar` ports `dart/lib/src/ics.dart`: `IcsCalendar.parse(text).busy(from, to)` (or `busy(from, to, labels = false)`) gives the busy windows of an iCalendar feed (TZID, VTIMEZONE fallback, all-day and floating times, RRULE, EXDATE, RECURRENCE-ID). It is the default `FeedParser` of `CalendarFeedConnector`. The Dart parser is the reference, quirks included, so both SDKs draw the same windows. `IcsTest` ports the Dart parser tests case for case and adds clock-change and recurrence cases whose expected values were printed by the Dart parser. `./gradlew check` runs it again with the device zone set to UTC, `America/New_York` and `Europe/Berlin` (`jvmIcsTest*`).

## Conformance harness

`commonTest/ConformanceTest.kt` ports `dart/test/conformance_test.dart` and `catalog_live_test.dart` case for case. The task `generateFixtures` embeds the repository's `fixtures/` and `catalog/catalog.json{,.sig}` into generated test code, so every target, iOS included, reads the same bytes with no file system. A vendored copy that keeps the fixtures elsewhere passes `-Pkahf.repoRoot=<dir>`.

`jvmTest/ReferenceServerTest.kt` runs the host and MCP client end to end against `reference/python/server.py`: at `KAHF_REFERENCE_URL` when set, else started by the test when `python3` has `mcp>=2.3` and `uvicorn`, else skipped (Linux CI).

Dependencies and their licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Contributing and licence

See [CONTRIBUTING.md](../CONTRIBUTING.md) (DCO sign-off required) and [SECURITY.md](../SECURITY.md). Apache-2.0: [LICENSE](../LICENSE).

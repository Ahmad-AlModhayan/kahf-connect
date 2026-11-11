# Kahf Connect

An open contract for connectors and agents: one specification, SDKs in several languages, one conformance suite.

A **connector** gives an app data (busy times from a calendar, notes for an agent). An **agent** reads that data and
proposes changes. Connectors are MCP servers with a manifest. An app reaches them through a **host** that enforces
the person's grants, checks every answer and audits every call. Connectors never write: changes are proposed and held
until the person accepts them.

Kahf Connect is built by Kahf Labs and used in its products, but nothing in it is specific to them. Anyone can write a
connector or an SDK.

## What is here

| Path | What |
|---|---|
| [SPEC.md](SPEC.md) | The contract (normative) |
| [docs/DESIGN.md](docs/DESIGN.md) | Why it is shaped this way, decisions, roadmap |
| [docs/MODEL_TIERS.md](docs/MODEL_TIERS.md) | `low` / `medium` / `high` model tiers, resolved to each provider's newest model |
| [schema/](schema) | JSON Schemas: manifest, catalog, capabilities |
| [fixtures/](fixtures) | The conformance suite every SDK must pass |
| [dart/](dart) | Dart SDK |
| [kotlin/](kotlin) | Kotlin Multiplatform SDK (JVM, Android, iOS) |
| [reference/python/](reference/python) | A working reference connector to copy |
| [catalog/](catalog) | The signed catalog of reviewed connectors |

## Install

The SDKs are not published to a package registry yet. Use them from a checkout:

- Dart: add a path or git dependency on `dart/` (package `kahf_connect`; Dart 3.3 or newer).
- Kotlin: include the `kotlin/` Gradle build (module `kahf-connect`, package `com.kahflabs.connect`; JDK 17).

## Use

Writing a connector: serve the capability tools (`kahf_time_busy_v1`, `kahf_context_read_v1`) from any MCP server,
answering with `structuredContent` that matches `schema/capabilities/`, and write a manifest that passes
`schema/manifest.schema.json`. Start from [reference/python/server.py](reference/python/server.py).

Hosting connectors: the SDKs provide the host, grants, audit, the MCP client, catalog verification and model-tier
resolution. See each SDK's README.

## Run the checks

```sh
pip install jsonschema
python tools/check_schemas.py          # schemas agree with the fixtures
cd dart && dart test                   # Dart SDK, including the conformance suite
cd kotlin && ./gradlew check           # Kotlin SDK, the same conformance suite
```

End to end against the reference connector:

```sh
cd reference/python && python server.py                               # http://127.0.0.1:8765/mcp
cd dart && KAHF_REFERENCE_URL=http://127.0.0.1:8765/mcp dart test
```

## Signed catalog

`catalog/catalog.json` is signed with Ed25519 and the public key is compiled into each SDK. The private key is held by
Kahf Labs and is not in this repository. `fixtures/catalog/test-only.key` is a test key and signs nothing real.
Connectors installed by address, outside the catalog, are labelled "not reviewed".

## Contributing

Pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md): commits need a DCO sign-off (`git commit -s`), and
behaviour changes start with a fixture. Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

Report vulnerabilities privately; see [SECURITY.md](SECURITY.md). Do not open a public issue.

## Licence

Apache-2.0: see [LICENSE](LICENSE). The names Kahf and Kahf Connect are not licensed for use by forks.

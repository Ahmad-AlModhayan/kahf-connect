# Kahf Connect — design

Status: v1 draft, 2026-10-26. Owner: Kahf Labs. The normative contract is [SPEC.md](../SPEC.md); this explains it.

## Why a pillar, not a Qasd feature

Kahf Labs, 2026-10-26: connectors "like in Obsidian community plugins … from Kahf Labs or any other developer", and
"the infrastructure has to be so strong and capable of adapting to connectors and agents … a pillar in Kahf Labs
that we will benefit from so much in other products and future products."

Every Kahf Labs product needs the same three things from the outside world: **data in** (a calendar, prayer times,
notes), **agents reading** (Raqeeb, Claude), and **agents proposing** (a planner, an importer). Built once per product
they would drift; built once here they are the same everywhere, and a connector written for Qasd works in the next
product without a line changed.

## Decisions

1. **MCP is the transport.** It is what Claude, ChatGPT and the agent world already speak, so a Kahf connector is
   usable by any MCP client, and any MCP server can become a Kahf connector by serving the capability tools. No
   Kahf-only wire protocol to maintain.
2. **Kahf adds what MCP leaves open:** a manifest (identity, host, auth, settings), typed and versioned
   capabilities, per-capability grants, an audit log, and a signed catalog. Those are what make "install a stranger's
   connector" safe.
3. **Capabilities are narrow and typed.** `time.busy@1`, `context.read@1`, `actions.propose@1`. A product asks for a
   capability, never "access". New needs get a new capability (`tasks.source@1`, `files.read@1` …), never a widening of
   an old one. Major versions coexist.
4. **Connectors never write** (SPEC §7). Changes from outside go through the product's agent changes: attributed,
   held, accepted, revertible. This one rule is why the trust model is simple: the worst a connector can do is
   supply wrong data, which the host checks and the person can switch off.
5. **The host is the product's only door** (`ConnectorHost` in each SDK): it enforces grants, validates every answer
   against its contract, strips labels the grant does not keep, isolates failures (one broken connector never spoils
   another's answer), times out, and audits. Product code never calls a connector directly.
6. **Three hosts, one contract.** Remote (MCP over HTTPS), built-in (in-process, same contract), on-device (another
   Kahf app via platform IPC). This is how Mizwala gives Qasd prayer windows: Mizwala is a `device` connector serving
   `time.busy`, so Qasd never learns what the windows are for — Qasd stays a global app.
7. **Products are connectors too.** Qasd's agent head is an MCP server; it gains a manifest and serves
   `actions.propose`. Raqeeb consumes products the same way it consumes third-party connectors.
8. **Spec first, many SDKs.** Kahf Labs ships Dart (Qasd), Rust (Wasl, Raqeeb), Kotlin (Mizwala), TypeScript (web,
   Obsidian Coder) and Python (servers). Each SDK is conformant when it agrees with every fixture in `fixtures/`; the
   JSON Schemas are checked against the same fixtures (`tools/check_schemas.py`), so schema and SDKs cannot drift.
9. **Signed catalog.** Ed25519 over the exact bytes of `catalog.json`; public keys are compiled into each SDK
   (`KahfKeys.catalog`). The private key lives off every repository, in Kahf Labs' offline key storage, with a
   backup. Third-party connectors install by address and are labelled "not reviewed" everywhere.
10. **Privacy defaults.** Labels (event titles) off, agent visibility off, secrets in secure storage only, audit
    without data, everything cached deleted on revoke.

## What exists (v1)

| Part | Where | State |
|---|---|---|
| Spec | `SPEC.md` | draft 1 |
| Schemas | `schema/` | manifest, catalog, 3 capabilities |
| Fixtures | `fixtures/` | 5 valid + 28 invalid manifests, capability answers, signed and tampered catalogs |
| Dart SDK | `dart/` | manifest, capabilities, grants, audit, host, MCP client (JSON + SSE), catalog verify, iCalendar reader, Calendar feed connector |
| Reference connector | `reference/python/` | MCP server serving `time.busy@1` and `context.read@1` with a bearer token |
| Catalog | `catalog/` | signed with `kahf-2026-1` |
| First product | Qasd 1.6 | Connectors screen; Calendar feed, Phone calendar, any MCP connector by address |

## Roadmap

1. **Mizwala as a `device` connector** (Kotlin SDK, Android bound service) — prayer windows into Qasd's protected
   time, opt-in, Qasd never knowing what they are.
2. **OAuth 2 with PKCE** in the SDKs, for connectors that need an account (Google Calendar API, Notion, Todoist).
3. **Rust SDK** for Wasl and Raqeeb; Raqeeb's planner reads products through Kahf Connect.
4. **`tasks.source@1`** (import tasks as a proposal) and **`files.read@1`** (Wasl as a connector).
5. **Catalog hosting** on kahflabs.com with a developer submission flow and review checklist.
6. **Agents through connectors**: Qasd's agent chat reads `context.read` from connectors whose grant allows agents.

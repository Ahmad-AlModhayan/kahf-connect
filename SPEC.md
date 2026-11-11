# Kahf Connect 1 — specification

Status: draft 1, 2026-10-26. Normative. "MUST", "SHOULD" and "MAY" are as in RFC 2119.

Kahf Connect is how Kahf Labs products take data in from **connectors** and let **agents** act on them. It is
one model for every product (Qasd, Mizwala, Wasl, Raqeeb, and what comes next) and every language they are
written in. The why and the roadmap are in [docs/DESIGN.md](docs/DESIGN.md); this file says what an
implementation must do.

## 1. Terms

- **Product**: a Kahf Labs app that hosts connectors (Qasd on a phone, Raqeeb on a server).
- **Connector**: something that supplies a product with data through one or more **capabilities**.
- **Agent**: something that reads a product's data and proposes changes to it (Raqeeb, Claude, the product's own
  planner). An agent proposes; the person decides (§7).
- **Capability**: a versioned contract, `<name>@<major>`, for one kind of data: what is asked, what comes back.
- **Manifest**: a connector's declaration of who it is, where it runs, how it authenticates and what it supplies.
- **Grant**: the person's yes to one capability of one installed connector, with its options.
- **Catalog**: a signed list of manifests Kahf Labs has reviewed.

## 2. Transport: MCP

A connector is an MCP server ([Model Context Protocol](https://modelcontextprotocol.io), 2025-06-18 or later).

- Each capability is one MCP **tool** named `kahf_<name with dots as underscores>_v<major>`, e.g. `time.busy@1` is
  `kahf_time_busy_v1`. Its arguments and its `structuredContent` MUST validate against
  `schema/capabilities/<name>.v<major>.json` (`input` and `output`).
- A connector MAY expose other tools and resources. Products MUST ignore tools they have no grant for.
- A product calls only `initialize`, `notifications/initialized`, `tools/list` and `tools/call`. A product MUST
  check, after `tools/list`, that every capability the manifest declares is present, and treat a missing one as
  unavailable rather than failing the connector.
- A tool result with `isError: true`, or `structuredContent` that fails its schema, is an error for that call.

### 2.1 Hosts

| `host.kind` | Where it runs | How it is reached |
|---|---|---|
| `remote` | anywhere | MCP streamable HTTP at `host.url` (HTTPS; `http://localhost` only for development) |
| `builtin` | inside the product | in process, through the SDK's connector interface — no MCP framing, same contract |
| `device` | another app on the same device | platform IPC (Android: a bound service named in `host.package`); MCP JSON-RPC messages as the payload |

A `builtin` connector MUST behave exactly as if reached over MCP: same capability schemas, same grants, same audit.

## 3. Manifest

A manifest is JSON validating against [`schema/manifest.schema.json`](schema/manifest.schema.json). Key rules:

- `id` is reverse-DNS (`com.kahflabs.calendar-feed`), unique, stable for the connector's life.
- `name` and `description` are localised maps; `en` is required, `ar` SHOULD be given.
- Every capability carries a `reason`: one sentence, localised, telling the person why it needs that data. The
  product shows it on the grant screen verbatim.
- `settings` lists what the person fills in when adding it (a feed URL, a token). A setting of kind `secret` or
  `secret-url` MUST be stored in the platform's secure storage and MUST NOT be logged, synced, or sent to any
  host other than the connector's own.
- `auth.kind` is `none`, `bearer` (a setting named in `auth.setting` holds the token) or `oauth2` (authorization
  code with PKCE; `auth.authorize_url`, `auth.token_url`, `auth.scopes`). Both URLs MUST be HTTPS (plain HTTP
  only on localhost, for development): they carry the code and the tokens.
- Every URL field of a manifest, and a feed address, MUST have a strict RFC 3986 authority: at most one `@`, a host of
  letters, digits and `-._~` (an IDN as punycode, no percent-escapes) or a bracketed IPv6 literal, and a numeric port;
  no backslash, space or non-ASCII character. An SDK MUST decide HTTPS-or-localhost on the scheme and host of the URL
  its HTTP client will request, so the host it checks is the host it connects to (`fixtures/manifests/invalid`,
  rules `uri` and `host-https`). "Localhost" is `localhost` and `127.0.0.1`, nothing else.

## 4. Capabilities (v1)

| Capability | Asks | Returns | Used for |
|---|---|---|---|
| `time.busy@1` | `from`, `to` (RFC 3339) | `windows[]`: `start`, `end`, optional `label`, optional `all_day` | Times the person is not free: a calendar, prayer times from Mizwala, a shift rota |
| `context.read@1` | `query` (MAY be empty), `limit` | `items[]`: `id`, `title`, `text`, optional `url`, `updated_at` | Notes, documents, messages an agent may read when helping |
| `actions.propose@1` | `label`, `commands[]` (product-defined, e.g. Qasd's `CoreCommand` wire form) | `proposal_id`, `status` | An agent proposing a changeset to a product (§7) |

Capability rules:

- A product MUST NOT ask for more than it needs: `time.busy` windows are asked for the span being planned, not
  the whole calendar.
- Labels are personal. A product MUST drop `label` unless the grant's `labels` option is on (default off).
- New fields MAY be added to an output within a major version; products MUST ignore fields they do not know.
  Removing or changing a field is a new major version.

## 5. Grants

- Nothing is called before the person grants it. Installing a connector shows each capability with its `reason`
  and lets the person grant each one separately.
- A grant has options: `labels` (keep labels; default off) and `agents` (agents may see this data; default off).
- Grants are per device and per account. Revoking a grant, or removing the connector, MUST delete everything
  cached from it.

## 6. Audit

Every call is recorded locally: `at`, `connector`, `capability`, `outcome` (`ok`, `error`, `denied`,
`unavailable`), `items` (how many records came back) and `ms`. Never the data itself, never a secret. The person can
read the log for each connector. Audit records follow the account: they are deleted with it.

## 7. Connectors never write

A connector supplies data. It MUST NOT be given a way to change the product's data. When something outside the
product wants to change it — an agent planning a day, a connector offering to import — it goes through the
product's **agent changes**: one labelled changeset, held until the person accepts, applied only as far as the
product's own rules allow, and revertible. This is the same model as Wasl's and Qasd's agent changes, and it is
what makes connectors safe to install.

## 8. Catalog

- `catalog/catalog.json` validates against [`schema/catalog.schema.json`](schema/catalog.schema.json): `issued_at`,
  `key_id` and `entries[]` (each a manifest plus `reviewed: true`).
- `catalog/catalog.json.sig` is the base64 Ed25519 signature of the exact bytes of `catalog.json`, by the key named
  in `key_id`. SDKs carry the Kahf Labs public keys and MUST refuse a catalog whose signature does not verify.
- A connector added by address, not from the catalog, is installable but MUST be shown as **not reviewed** on every
  screen that names it.
- A remote connector MAY be added by its address alone. The product then derives its manifest from what the server
  says: `serverInfo` gives the name and version, and the capabilities are the `kahf_*` tools in `tools/list`. Its id is
  `address.` followed by the host's labels reversed (`https://notes.example.com/mcp` → `address.com.example.notes`),
  and its reasons are generic. A server that serves no `kahf_*` tool cannot be added.

## 9. Conformance

[`fixtures/`](fixtures) holds manifests (valid and invalid, each invalid one naming the rule it breaks), capability
outputs, and a signed catalog with a test key. Every SDK's test suite MUST run all of them and agree on every
verdict. An SDK is conformant when it does.

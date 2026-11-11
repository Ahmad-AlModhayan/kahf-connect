# Kahf Connect: Dart SDK

The Dart implementation of the [Kahf Connect contract](../SPEC.md): manifest and capability checks, `ConnectorHost`
with grants and audit, the MCP client, the calendar-feed builtin with an ICS parser, the signed catalog, and model
tiers.

## Install

Not yet on pub.dev. Depend on it by path or git (package `kahf_connect`, Dart 3.3 or newer):

```yaml
dependencies:
  kahf_connect:
    path: ../kahf-connect/dart
```

## Test

```sh
dart pub get
dart test        # includes the conformance suite in ../fixtures
```

End to end, with the [reference connector](../reference/python) running:

```sh
KAHF_REFERENCE_URL=http://127.0.0.1:8765/mcp dart test
```

## Tools

- `dart run tool/catalog_tool.dart sign <catalog.json> <private-key>`: sign a catalog (the private key is not in this repository).
- `dart run tool/sync_model_tiers.dart`: write `lib/src/model_tiers_table.dart` from `../catalog/model-tiers.json`.

## Contributing and licence

See [CONTRIBUTING.md](../CONTRIBUTING.md) (DCO sign-off required) and [SECURITY.md](../SECURITY.md). Apache-2.0: [LICENSE](../LICENSE).

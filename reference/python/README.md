# Kahf Connect: reference connector

A small remote MCP server that serves `time.busy@1` and `context.read@1` with a bearer token. It is the working
example to copy when writing a connector, and what each SDK's end-to-end test runs against. Its data is made up.

```sh
pip install "mcp>=2.3,<3" uvicorn
KAHF_REFERENCE_TOKEN=reference python server.py        # http://127.0.0.1:8765/mcp
```

The contract it answers is [SPEC.md](../../SPEC.md); the answers match [schema/capabilities/](../../schema/capabilities).
Apache-2.0: [LICENSE](../../LICENSE).

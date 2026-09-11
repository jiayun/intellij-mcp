# External MCP end-to-end verification

These runs use the **final 1.11.1 production ZIP**, loaded in isolated IDE instances with the production MCP server enabled. A separate Python process connects over TCP/HTTP and performs MCP JSON-RPC requests. Query execution is not mocked and does not call `IntelligenceService` directly from the harness.

**All 21 fixture runs passed: 119 language-query assertions, 2 additional Java result-limit/batch checks, and 547 recorded HTTP JSON-RPC exchanges**, including initialization, discovery, invalid-parameter checks and readiness retries.

The IDE harness only opens the copied fixture project, configures its SDK, applies unsaved edits and checks disk/editor state. Each query result is decoded from the actual MCP `result.content[0].text` payload before the existing semantic assertions run. The server is stopped after the run, and normal user IDE settings are unchanged.

## Coverage

- MCP initialization using protocol `2024-11-05`, installed plugin version `1.11.1`, tool capabilities and the standard `notifications/initialized` notification with HTTP 204/no response body.
- `/health` and `/info`; `tools/list` includes the schemas for all three new tools.
- `list_projects` and explicit `projectPath` selection; `get_supported_languages` retains existing fields and reports unknown LSP capabilities before initialization.
- Integer, negative, string and large integer JSON-RPC IDs survive the full HTTP response unchanged.
- Invalid diagnostics file lists, zero-based coordinates and invalid call directions return JSON-RPC `-32602` errors.
- Actual `get_diagnostics`, incoming/outgoing `get_call_hierarchy` and `find_implementations` queries across every language adapter, including unsaved error/fix cycles and unchanged disk bytes.
- Vue script/script-setup, Volar, existing/unopened editor cleanup; Python imported-use identity and ambiguous imports; Dart CRLF mapping.
- Rust native and older fallback; csharp-ls 0.27.0, csharp-ls 0.26.0 and OmniSharp 1.39.15. Expected `partial`/`unsupported` outcomes are checked explicitly rather than interpreted as successful empty results.
- Java additionally verifies `maxNodes=1` produces `partial` with `truncated=true`, and that a diagnostics batch returns both a complete file result and an error for a missing file.

See [summary.json](summary.json) for the exact fixture, query and HTTP exchange counts. Each adjacent fixture JSON contains `transport`, `mcpProbe`, `mcpExchanges`, decoded query results and the assertion outcome. Java also records `mcpPostProbe`.

## Findings and fixes

The first external run succeeded in querying Java but exposed stale MCP metadata: initialization and `/info` reported a hardcoded `1.0.0`. The final build reads the installed plugin version through the public `PluginAwareClassLoader` descriptor. The standard initialized notification is also recognized explicitly. Version assertions now run against the real HTTP responses.

The first RustRover attempt stalled in a modal new-UI onboarding dialog before MCP startup. The runner now disables `ide.experimental.ui.onboarding` only for its disposable IDE process; the final Rust runs use that setting. This was a fixture-startup issue, not an MCP query timeout.

## Reproduction and limits

Build the production ZIP and test harness, then add `--mcp` to the [fixture runner commands](../../../fixtures/README.md). All runs here use IDEA Ultimate 2025.3.1 except Rust native, which uses RustRover 2026.2.2. SDK/backend versions match the [language acceptance environments](../../code-intelligence.md).

This is an external HTTP MCP client test, not a claim that every Claude/Codex/other client application, every MCP protocol revision or every existing execution-management tool has been tested. The scope is the three new code-intelligence tools plus their discovery/transport path. Server-specific limitations remain unchanged; in particular OmniSharp's initial unconfirmed diagnostics are partial, and older C# outgoing / OmniSharp call hierarchy are unsupported.

The final [artifact metadata](../artifact.json), [51-test results](../tests.json) and [four-platform verifier results](../README.md#binary-compatibility) apply to the same ZIP. Previous direct-service reports remain separate historical evidence.

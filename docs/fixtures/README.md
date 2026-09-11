# Real IDE / SDK acceptance fixtures

These fixtures run the built production ZIP through a separate, test-only IDE plugin. They are not added to the shipping plugin. Each `smoke.json` states the expected queries and statuses; deliberately limited backends can pass by returning the expected `partial` or `unsupported` result.

## Full MCP HTTP acceptance

Add `--mcp` to either runner command below to start the production MCP server on a temporary port in the isolated IDE. `scripts/mcp_smoke_client.py` runs as an external Python process, connects to `127.0.0.1`, performs MCP initialization and sends `tools/call` through the real HTTP/JSON-RPC endpoint. The IDE harness still prepares SDKs and edits unsaved Documents, but it does not execute tool queries directly in this mode.

Each run verifies the advertised plugin version, health/info endpoints, `tools/list`, `list_projects`, `get_supported_languages`, unknown capabilities before LSP startup, invalid parameters, notification responses and integer/string request IDs. Java also checks graph truncation and a diagnostics batch with one missing file. All fixture assertions run against the decoded MCP text-content response, including unsaved error/fix and Vue editor cleanup. Reports identify the transport and retain JSON-RPC exchanges.

Without `--mcp`, queries call `IntelligenceService` directly. Keep the two kinds of evidence distinct: neither direct-service fixtures nor in-memory HTTP handler tests alone prove the complete external MCP path. The client currently exercises protocol version `2024-11-05`, matching the server's advertised version; it is not a test of every third-party MCP client application.

The runner suppresses new-UI onboarding only in its disposable IDE configuration so modal onboarding cannot stall Cargo/project indexing. Server shutdown and client-process cleanup occur after the run; the user's normal IDE MCP settings are not changed.

Build first:

```sh
./gradlew :core:test :core:buildPlugin :core:smokeHarnessJar
```

Configure installed SDK paths in the environment (`MCP_GO_SDK`, `MCP_DART_SDK`, `MCP_RUST_BIN`, `MCP_PYTHON`). Put the chosen `csharp-ls` or `OmniSharp` executable, `dotnet`, Node and Swift on `PATH`; set `DOTNET_ROOT` when using a private .NET SDK. Fixtures use installed language plugins and do not download new language servers. Vue fixtures need `npm ci --prefix docs/fixtures/<fixture-name>` for `vue`, `vue-script`, `vue-volar`, `vue-editor` and `vue-semantics`.

Run a matrix against a local IDE distribution:

```sh
python3 scripts/run_smoke_matrix.py \
  --ide /absolute/path/to/IDE/Contents \
  --plugins core/build/idea-sandbox/IU-2025.3.1/plugins \
  --license-file /absolute/path/to/existing/idea.key \
  --languages java,kotlin,javascript,javascript-js,vue,vue-script,vue-volar,vue-editor,python,php,go,dart,dart-crlf,swift,csharp \
  --reports /tmp/intellij-mcp-matrix
```

The optional `--plugins` directory supplies extra language plugins; its contents are copied into each isolated sandbox. `--license-file` links an existing local IDE license into that sandbox without reading its contents. Omit it when the distribution does not need it. Go and Vue runs use editor UI, so run in a graphical login session. The runner copies the fixture before changing documents, and Swift fixtures are built with an index store in that copy. Unsaved changes are checked against unchanged disk bytes. Logs and sanitized JSON responses go to the report directory; the runner prints its temporary sandbox location for inspection.

Additional `<language>-semantics` fixtures test declaration/use identity, overload separation and language-specific traits/mixins. Select the csharp-ls 0.26 executable before running `csharp-026`, and OmniSharp before running `csharp-omnisharp`. The main `csharp` fixture requires csharp-ls 0.27.0+ for outgoing hierarchy.

For Rust, configure `MCP_RUST_BIN` to the toolchain's executable directory. Run the current native backend in RustRover, and run the older plugin with explicit fallback expectations:

```sh
python3 scripts/run_ide_smoke.py /path/to/RustRover/Contents docs/fixtures/rust /tmp/rust-native.json --ui --license-file /path/to/rustrover.key
python3 scripts/run_ide_smoke.py /path/to/older/IDE docs/fixtures/rust /tmp/rust-fallback.json --ui --rust-fallback --plugins /path/to/plugins --license-file /path/to/idea.key
```

`--rust-fallback` changes expected statuses only; it does not force backend selection. An older installed Rust plugin must actually lack the new native hierarchy classes. Repeat with `rust-semantics` for recursion and trait/reference checks.

See [the executed acceptance report](../verification/code-intelligence.md) for exact environments, results and limits. Binary Plugin Verifier checks and live SDK checks are recorded separately.

# Code intelligence tools (1.11.1)

All three tools accept an optional `projectPath`, use absolute file paths and **1-based UTF-16 line/column coordinates**, and return JSON inside the existing MCP text content. The request timeout defaults to 30,000 ms and must be between 1 and 60,000 ms. One deadline covers preparation and every file/graph request. No tool saves IDE documents or changes inspections.

| Tool | Required arguments | Optional arguments |
| --- | --- | --- |
| `get_diagnostics` | `filePaths: string[]` (nonempty) | `minSeverity: error/warning/information/hint` (warning), `timeout`, `projectPath` |
| `find_implementations` | `filePath`, `line`, `column` | `limit` (200, max 1000), `includeLibraries` (false), `timeout`, `projectPath` |
| `get_call_hierarchy` | `filePath`, `line`, `column`, `direction: incoming/outgoing` | `depth` (1, max 3), `maxNodes` (100, max 500, includes root), `includeLibraries` (false), `timeout`, `projectPath` |

Examples:

```json
{"name":"get_diagnostics","arguments":{"filePaths":["/project/src/Example.java"],"minSeverity":"warning"}}
{"name":"find_implementations","arguments":{"filePath":"/project/src/Base.java","line":4,"column":10}}
{"name":"get_call_hierarchy","arguments":{"filePath":"/project/src/Example.java","line":8,"column":10,"direction":"incoming","depth":2}}
```

## Results and completeness

Diagnostics returns one result per distinct requested file. Each result has `filePath`, `status`, optional `reason`, `diagnostics`, and `backend`. A failed file does not suppress successful files. Diagnostic entries contain severity, message, location, and source.

Implementation results contain `target`, `implementations`, `status`, optional `reason`, `truncated`, and `backend`. The original declaration is excluded, and duplicate declarations are removed. A cursor on an unresolved or ambiguous reference does not silently select its enclosing function.

Call results contain `root` (node ID), `nodes` (`id`, `symbol`), `edges` (`from`, `to`, `callLocations`), `status`, optional `reason`, `truncated`, and `backend`. Edges always point from caller to callee, including for an incoming traversal. IDs include declaration location and signature. Recursion retains its edge but does not repeatedly expand a declaration. `depth` defines the requested graph boundary; reaching that boundary is not truncation. Reaching `maxNodes` is truncation. Call sites are included when the backend exposes them publicly; several IDE tree implementations expose declarations only.

| Status | Meaning |
| --- | --- |
| `complete` | The backend completed the requested query, within its documented semantics and scope. Only this status permits interpreting an empty collection as a negative result. |
| `partial` | Results are incomplete: deadline/cancellation, source changed during analysis, truncation, limited fallback semantics, or unconfirmed diagnostics freshness. Check `reason`. |
| `unsupported` | The operation or selected symbol is not supported by the installed backend. |
| `not_ready` | SDK, language server, PSI, project, or indexes are unavailable or not ready. |
| `error` | Analysis failed. Check `reason`; an empty collection here does not mean clean code. |

`get_supported_languages` preserves `id`, `name`, and `extensions`, and adds `capabilities` with `diagnostics`, `implementations`, `incoming`, and `outgoing`. Each capability has `status`, `backend`, and `limitations`. `available` describes an installed operation; it is not a guarantee that every symbol is supported. LSP capabilities remain `unknown` until an existing project session has initialized. Listing never starts a server. The optional `projectPath` selects the session; otherwise the existing project resolver is used where unambiguous.

## Backend matrix

| Language | Diagnostics | Implementations | Incoming / outgoing | Constraints |
| --- | --- | --- | --- | --- |
| Java | IDE CodeSmellDetector | IDE DefinitionsScopedSearch | Native Java hierarchy | Configured module SDK and indexes required; language plugins control cross-language results. |
| Kotlin | IDE CodeSmellDetector | IDE DefinitionsScopedSearch | K2 native hierarchy | K2 hierarchy is loaded through the installed language provider; no K1 fallback. |
| JavaScript / TypeScript | IDE inspections + installed language service | Language definitions executors | Native JS hierarchy | Results follow the installed JavaScript plugin and its inference. |
| Vue | IDE SFC inspections + installed language service | Script definitions executors | JS/TS script hierarchy | `<script>` and `<script setup>` only for symbol queries. Template component/event relationships excluded; locations remain in the original `.vue` view. |
| Python | IDE CodeSmellDetector | Language definitions executors | Native Python hierarchy | Module SDK required. Dynamic runtime calls may not be statically resolvable. |
| PHP | IDE CodeSmellDetector | Definitions executors + nested trait usage index | Native PHP hierarchy | Installed PHP plugin and its indexes required. |
| Go | IDE CodeSmellDetector | Go definitions executors | Native Go hierarchy | Uses the plugin's structural/implicit interface implementation semantics. |
| Dart | Dart Analysis Server `analysis.getErrors` | Server `search.getTypeHierarchy` | Server references/navigation + PSI call sites | Configured SDK, active server, and included analysis roots required. Legacy server requests cannot always be interrupted remotely; late responses are discarded after the request deadline. |
| Rust | IDE CodeSmellDetector | `RsImplsSearch` via DefinitionsScopedSearch | Native hierarchy when present; direct PSI calls on older plugins | New hierarchy classes are referenced by name only. Older fallback always reports `partial`: no function pointers, unresolved dynamic dispatch, or macro expansion. |
| Swift | SourceKit-LSP pull/push | LSP implementation | LSP prepare/incoming/outgoing | Installed toolchain and indexed project required; capability-dependent. |
| C# | csharp-ls / OmniSharp pull/push | LSP implementation | LSP prepare/incoming/outgoing | csharp-ls outgoing requires a reported version >= 0.27.0. Older versions are unsupported; absent version is unknown. OmniSharp follows actual capabilities. No Rider ReSharper backend. |

IDE CodeSmellDetector only guarantees warning/error analysis; requesting information or hints returns `partial` with that limitation. Analysis follows enabled IDE inspections and language-server configuration. It is not a project-wide scan and does not enable disabled inspections or analyzers.

LSP sessions retain initialize capabilities and dynamic registrations/unregistrations, respect dynamic document selectors, and send versioned open/change/close notifications. Incremental-only servers receive a full old-document range replacement. Queries synchronize the requested IDE snapshot and other unsaved files of that language in the same project. Pull diagnostics is preferred; push results without a matching version are partial. Null query responses and unprepared call targets are not reported as complete empty graphs.

Rust native hierarchy was introduced in [RustRover 2026.1](https://www.jetbrains.com.cn/rust/whatsnew/2026-1/). Both the 2026.2.2 native backend and the older plugin's direct-call fallback have passed live Cargo fixtures. csharp-ls 0.27.0 has passed bidirectional hierarchy, implementation, and unsaved diagnostic checks with .NET 10. See the executed matrix below for exact environments.

JS/TS services receive unopened documents before diagnostics; each suspended continuation releases IDE read access while waiting. Unsaved TypeScript configuration returns `not_ready` because the IDE's highlighter would otherwise save that configuration automatically. For unopened Vue files, diagnostics temporarily activates an IDE editor without requesting keyboard focus and closes the editor created by the query afterward. It may briefly select a tab. Documents are never saved; concurrent user edits leave that editor open. No inspection settings are changed.

## Verification

See [verification results](verification/code-intelligence.md) for actual executed checks and unverified combinations, and [fixture instructions](fixtures/README.md) for the SDK-backed IDE matrix. Compiling against 2025.3.1 while retaining `since-build=251` is not by itself proof of 2025.1 compatibility.

The [1.11.1 verification supplement](verification/1.11.1/README.md) covers the public API migration for Vue and Python; the original 1.11.0 reports remain historical evidence for the full language matrix.

```sh
./gradlew :core:test :core:buildPlugin
./gradlew :core:verifyPlugin
# Verify a specific installed distribution:
./gradlew :core:verifyPlugin -PverificationIdePath=/absolute/path/to/IDE

# Installed SourceKit-LSP smoke:
swift build --package-path docs/fixtures/swift --enable-index-store
python3 scripts/lsp_smoke.py /absolute/path/to/sourcekit-lsp docs/fixtures/swift
```

The Gradle verifier matrix targets IDEA 2025.1, 2025.3.1, and 2026.2.1. Local distributions can be selected without changing the build. Plugin ZIPs are generated in `core/build/distributions/`. This change does not publish to Marketplace.

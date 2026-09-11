# Internal API migration — 1.11.1

This patch removes the four internal API usages reported for 1.11.0. The final build additionally fixes MCP version reporting and was tested through an external HTTP client; see the [MCP end-to-end report](mcp/README.md). The production ZIP was checked again with Plugin Verifier 1.410: **zero internal API usages and compatible verdicts on all four tested IDE distributions**.

## Changes

- Vue diagnostics uses public `FileEditorManager.openFile(file, false)` and `runWhenLoaded`. It no longer references `FileEditorOpenOptions`, its two internal option methods, or the reflective editor-opening overload. Temporary editor cleanup and the unsaved TypeScript configuration guard remain in place.
- Python import resolution uses public `PyImportElement.multiResolve()`, preserves the highest-rated valid candidates and deduplicates their navigation targets. An unresolved import retains the existing fallback; multiple equally ranked declarations return `partial` with an ambiguity reason instead of selecting one arbitrarily.
- MCP initialization and `/info` report the installed plugin version through the public `PluginAwareClassLoader` descriptor, and the standard `notifications/initialized` method is recognized.
- Version 1.11.1 is a separate artifact. The submitted 1.11.0 ZIP and its [original verification records](../code-intelligence.md) remain unchanged.

The public API migration follows the [JetBrains internal API guidance](https://plugins.jetbrains.com/docs/intellij/api-internal.html). The [Python API documentation](https://github.com/JetBrains/intellij-community/blob/master/python/python-psi-api/src/com/jetbrains/python/psi/PyImportElement.java) explicitly recommends `multiResolve()` as the replacement for `resolve()`.

## Executed verification

`./gradlew :core:test :core:buildPlugin :core:smokeHarnessJar --offline --no-configuration-cache` passed: **51 tests**, zero failures/errors/skips. See [suite counts](tests.json).

The initial internal-API-only build (identified in [its artifact record](internal-api-artifact.json)) was tested before the MCP metadata fix. Its isolated production-ZIP regression matrix ran in IDEA Ultimate 2025.3.1 with Python 3.13 and the existing configured Vue/TypeScript services: **35 checks across 7 fixture runs**. See [live summary](live/summary.json).

| Fixture | Checks | Coverage |
| --- | --- | --- |
| [Python](live/python.json) | 6 | Incoming/outgoing calls, implementations, clean diagnostics and unsaved error/fix. |
| [Python semantics](live/python-semantics.json) | 3 | Imported-use/declaration target identity; new conditional-definition import returns `partial`, an ambiguity reason and exactly zero edges. |
| [Vue script setup](live/vue.json) | 6 | Calls, implementations and unopened-file unsaved diagnostics. |
| [Vue script](live/vue-script.json) | 6 | The same queries for a regular script section. |
| [Vue Volar](live/vue-volar.json) | 6 | The same queries with Volar selected. |
| [Vue opened editor](live/vue-editor.json) | 6 | Existing-editor lifecycle regression. |
| [Vue semantics](live/vue-semantics.json) | 2 | Declaration/use identity and original SFC positions. |

Queries assert that disk bytes remain unchanged. Vue diagnostics for unopened files also assert that the temporary editor is closed. The smoke harness gained an explicit `edgeCount` expectation for the ambiguous Python import: the initial run returned the correct production result but failed the harness's old unconditional nonempty-edge assertion; the final Python semantics run uses the corrected assertion. The harness is a separate, non-shipping JAR.

## Binary compatibility

| Distribution | Verdict | Internal usages | Remaining API notices |
| --- | --- | --- | --- |
| IDEA Ultimate 2025.1 / IU-251.23774.435 | [Compatible](idea-2025.1/verification-verdict.txt) | 0 | 25 deprecated, 4 experimental. |
| IDEA Ultimate 2025.3.1 / IU-253.29346.138 | [Compatible](idea-2025.3.1/verification-verdict.txt) | 0 | 28 deprecated. |
| IDEA 2026.2.2 / IU-262.10315.125 | [Compatible](idea-2026.2.2/verification-verdict.txt) | 0 | 1 scheduled-for-removal, 32 deprecated, 30 experimental. |
| RustRover 2026.2.2 / RR-262.10315.167 | [Compatible](rustrover-2026.2.2/verification-verdict.txt) | 0 | 1 scheduled-for-removal, 32 deprecated, 30 experimental. |

Each directory preserves the verifier dependency and API-usage reports. Zero internal usages applies to the resolved dependencies of these distributions. Marketplace may resolve other plugin versions or test other IDE builds; these local results do not guarantee review approval. The scheduled-for-removal callback and other deprecated/experimental APIs were not changed in this internal-API-only patch.

The earlier direct-service regression was targeted to Python/Vue. The final ZIP additionally has the [external MCP matrix](mcp/README.md) across all language adapters, with backend/version limits recorded separately. Cross-version results above are binary verification, not full live acceptance on every IDE version.

## Artifact

- Path: `core/build/distributions/intellij-mcp-1.11.1.zip`
- Size: **8,952,801 bytes**
- SHA-256: `bdec30b555952fd2a9726773284fc8d4bb1a8de663c52a0b10296a2a0534be9c`
- [Machine-readable metadata](artifact.json)

The ZIP integrity and embedded version were checked. This patch has not been uploaded to Marketplace.

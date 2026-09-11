# Code intelligence verification — 2026-09-11

The three tools are implemented in plugin **1.11.0**. The final build passed **51 tests**, and **142 SDK-backed checks across 31 fixture runs passed**, covering every language adapter. Reports record actual tool results, including expected `partial` and `unsupported` responses; a passing fixture does not imply every backend supports every operation.

## Executed checks

- `:core:test`: **51 passed**, zero failures, errors or skipped tests. [Suite counts](tests.json): 14 IDE fixtures, 12 shared/protocol tests, 12 LSP state tests, 1 LSP transport test, 11 existing HTTP/JSON-RPC regression tests, and 1 Rust binary regression test.
- Shared/protocol coverage includes tool registration and dispatch, parameter boundaries, one-based UTF-16/CRLF/EOF coordinates, shared deadlines, cancellation, worker failures, graph cycles/depth/limits, deduplication, per-file partial failures, and JSON-RPC request IDs.
- IDE fixtures include Java/Kotlin cross-language calls in both directions, declaration/use identity, cross-file overrides, unsaved source changes, and protection against automatic saving of unsaved TypeScript configuration.
- A controlled LSP server over real LSP4J JSON-RPC pipes verifies opaque hierarchy data, call ranges, implementations, dynamic registration/unregistration and selectors, delayed initialization, canonical document identity, open/change/close, stale diagnostics rejection, and C# version gating.
- Separate IDE processes load the production ZIP plus a test-only harness into isolated configurations. SDK-backed fixtures query the actual intelligence service and language plugins/servers. Basic fixtures check incoming, outgoing, implementations, clean diagnostics, an unsaved error, and an unsaved fix. Additional fixtures check declaration/use identity, overloads, traits, mixins and recursion. Each query checks that source bytes on disk are unchanged. Vue unopened-file fixtures also assert that the temporary editor is closed afterward.

The [live matrix summary](live/summary.json) links by fixture name to individual JSON reports in the same directory. The [fixture runner instructions](../fixtures/README.md) describe how to reproduce these checks.

## Live language matrix

Unless noted, tests ran in IDEA Ultimate **2025.3.1 (IU-253.29346.138)** with its language plugins and configured SDKs. This is a concrete fixture acceptance matrix, not exhaustive verification of all language constructs or IDE/plugin releases.

| Language / backend | Verified behavior | Evidence / limitation |
| --- | --- | --- |
| Java | Both call directions, implementations, unsaved diagnostics, declaration/use identity and overload exclusion | [Basic](live/java.json), [semantics](live/java-semantics.json); IDE JBR SDK. Java/Kotlin cross-language calls also covered by IDE fixtures. |
| Kotlin K2 | Both directions, overrides, unsaved diagnostics, declaration/use identity and overload exclusion | [Basic](live/kotlin.json), [semantics](live/kotlin-semantics.json). |
| JavaScript / TypeScript | Both directions, definitions/implementations, unsaved diagnostics and reference identity | [JavaScript](live/javascript-js.json), [TypeScript](live/javascript.json), [semantics](live/javascript-semantics.json); installed IDE language service. |
| Vue | Both directions and implementations mapped to original SFC positions; unopened, unsaved error/fix lifecycle | [Script setup](live/vue.json), [script](live/vue-script.json), [Volar](live/vue-volar.json), [opened editor](live/vue-editor.json), [semantics](live/vue-semantics.json). Vue 3.5.22, TypeScript 5.9.3, Node 26.3.1. Template call relationships remain outside scope. |
| Python | Both directions, overrides, unsaved diagnostics, imported-reference target identity | [Basic](live/python.json), [semantics](live/python-semantics.json); Python 3.13. |
| PHP | Both directions, implementations, unsaved diagnostics, nested trait usage | [Basic](live/php.json), [trait semantics](live/php-semantics.json); PHP 8.3. |
| Go | Both directions, structural/implicit interface implementations, unsaved diagnostics, reference identity | [Basic](live/go.json), [semantics](live/go-semantics.json); Go 1.25.5. |
| Dart | Both directions, interface/mixin implementations, unsaved overlays, diagnostics and CRLF coordinate conversion | [Basic](live/dart.json), [mixin semantics](live/dart-semantics.json), [CRLF](live/dart-crlf.json); Dart SDK 3.13.3 and its Analysis Server. |
| Rust native | Both directions, trait implementations, unsaved diagnostics, reference identity and recursive edges | [Basic](live/rust-native.json), [semantics](live/rust-native-semantics.json); actual Cargo project in RustRover **2026.2.2 (RR-262.10315.167)**. |
| Rust older fallback | Direct incoming/outgoing calls, trait implementations, unsaved diagnostics, reference identity and recursion | [Basic](live/rust-fallback.json), [semantics](live/rust-fallback-semantics.json); older Rust plugin in IDEA 2025.3.1. Call results correctly remain `partial`: function pointers, unresolved dynamic dispatch and macro expansion are excluded. |
| Swift / SourceKit-LSP | Cross-file incoming/outgoing, protocol implementation, unsaved error/fix and reference identity | [Basic](live/swift.json), [semantics](live/swift-semantics.json); installed Xcode toolchain, SwiftPM fixture built with `--enable-index-store`. |
| C# / csharp-ls 0.27.0 | Both directions, implementations, unsaved error/fix and reference identity | [Basic](live/csharp-027.json), [semantics](live/csharp-semantics.json); .NET SDK 10.0.401. |
| C# / csharp-ls 0.26.0 | Incoming, implementations and unsaved diagnostics; outgoing correctly `unsupported` | [Version-gate acceptance](live/csharp-026.json). |
| C# / OmniSharp 1.39.15 | Implementations and version-matched push diagnostics after unsaved edits | [Capability acceptance](live/omnisharp.json). This server advertises no call hierarchy, so both directions return `unsupported`. Initial clean-file diagnostics emitted no confirming push before the deadline and correctly returned `partial`; later unsaved error/fix reports were complete. |

Live testing exposed and fixed Dart request envelopes and CRLF conversion, the Dart/Gson classloader collision, Go's coroutine execution requirement, Python imported-reference resolution, PHP trait searches, delayed C# capability registration, SourceKit protocol target selection and canonical document URIs, and the Vue editor/service initialization lifecycle.

Vue diagnostics may temporarily select an editor tab without requesting keyboard focus, then close the editor created for the request. The document is never saved. If the user changes it during analysis, cleanup leaves the editor open. Unsaved TypeScript configuration returns `not_ready` before opening an editor, because the IDE highlighter would otherwise save that configuration.

## Final artifact and platform compatibility

`./gradlew :core:test :core:buildPlugin :core:smokeHarnessJar --offline --no-configuration-cache` completed successfully. The installable artifact is **`core/build/distributions/intellij-mcp-1.11.0.zip`**, **8,951,031 bytes**, SHA-256:

```text
e39cb466c54d299145cb2ad3aab62bffd3d6048b993f3f827b74d548f823903e
```

ZIP integrity was checked. The test harness is a separate artifact and is not shipped. IDE-owned JVM Kotlin/coroutine runtime classes and Gson are not bundled; this avoids optional-language plugin classloader conflicts. No Marketplace publication was performed.

Plugin Verifier **1.410** checked this exact ZIP against all four distributions using `check-plugin <zip> <ide-paths...> -offline`. Compilation uses IDEA 2025.3.1; the minimum remains build 251 (2025.1).

| Distribution | Build | Final verdict |
| --- | --- | --- |
| IDEA Ultimate 2025.1 | IU-251.23774.435 | [Compatible](idea-2025.1/verification-verdict.txt); 25 deprecated, 4 experimental and 3 internal API usages. |
| IDEA Ultimate 2025.3.1 | IU-253.29346.138 | [Compatible](idea-2025.3.1/verification-verdict.txt); 28 deprecated and 3 internal API usages. |
| IDEA 2026.2.2 | IU-262.10315.125 | [Compatible](idea-2026.2.2/verification-verdict.txt); 1 scheduled-for-removal, 33 deprecated, 30 experimental and 4 internal API usages. |
| RustRover 2026.2.2 | RR-262.10315.167 | [Compatible](rustrover-2026.2.2/verification-verdict.txt); 1 scheduled-for-removal, 33 deprecated, 30 experimental and 4 internal API usages. |

RustRover initially reported **67 unresolved Java APIs**. Replacing the optional dependency identifier `com.intellij.modules.java` with the actual plugin ID `com.intellij.java` eliminated all 67 without suppressing verifier checks. Java remains optional behind `java-support.xml`, following the [official Java plugin dependency declaration](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html). The [original report](rustrover-2026.2.2/before-java-dependency-fix/compatibility-problems.txt) is retained. Live Cargo acceptance now supplements the binary verifier for both Rust backends.

Each verdict directory retains resolved dependencies and API-usage reports. Binary verification covers only resolved dependencies; it cannot certify reflection-loaded API signatures or every optional Marketplace plugin release. Those paths are additionally exercised in the live environments above. The remaining deprecated, experimental and internal APIs are explicit maintenance risks, including Vue editor options and Python import resolution.

## Coverage boundaries

- IDEA 2025.1 and IDEA 2026.2.2 have binary verification; the full language acceptance matrix ran on 2025.3.1. Rust native additionally ran on RustRover 2026.2.2. Do not interpret this as a complete live matrix on every IDE version or product. A 2026.2 Vue live startup did not finish indexing within the harness deadline and is not accepted as a pass.
- Rust 2026.1's first native hierarchy release was not separately run. Runtime detection isolates newer classes and the tested older fallback remains available.
- SourceKit-LSP results require a built/indexed Swift project. Different SourceKit-LSP, OmniSharp and csharp-ls releases remain capability-dependent.
- Tests cover representative static semantics, not every overload, dynamic dispatch, macro, generated source or language construct. Vue template component/event call relationships and whole-project diagnostics are outside the agreed scope.

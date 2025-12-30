# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

intellij-mcp is a JetBrains IDE plugin that exposes code analysis capabilities via MCP (Model Context Protocol) for integration with AI coding assistants. It runs an HTTP server (default port 9876) that provides language-aware code navigation tools.

## Build Commands

```bash
# Build the plugin
./gradlew :core:buildPlugin

# Run IDE with plugin for testing
./gradlew :core:runIde

# Clean build
./gradlew clean :core:buildPlugin
```

Output: `core/build/distributions/intellij-mcp-x.x.x.zip`

## Architecture

### Multi-module Gradle Project
- Root project configures Kotlin 2.1.20 and Java 21
- `:core` module contains all plugin code, uses IntelliJ Platform Gradle Plugin 2.x

### Key Components

**Language Adapter System** (`api/LanguageAdapter.kt`)
- Extension point pattern for language support
- Each language implements `LanguageAdapter` interface with `findSymbol`, `findReferences`, `getSymbolInfo`, `getFileSymbols`, `getTypeHierarchy`
- Registered via `plugin.xml` extension point `info.jiayun.intellij-mcp.languageAdapter`
- Python adapter in `python/PythonLanguageAdapter.kt` uses Python PSI APIs (PyClass, PyFunction, etc.)
- Java adapter in `java/JavaLanguageAdapter.kt` uses Java PSI APIs (PsiClass, PsiMethod, AllClassesSearch, etc.)

**MCP Server** (`mcp/McpServer.kt`)
- Ktor Netty server exposing JSON-RPC 2.0 endpoints
- `/mcp` - main MCP endpoint for tool calls
- `/health`, `/info` - health check and server info
- Application-level service (singleton)

**Tool Executor** (`mcp/McpToolExecutor.kt`)
- Translates MCP tool calls to LanguageAdapter methods
- Handles project resolution via `ProjectResolver`
- All PSI operations wrapped in `ReadAction.compute`

### Data Flow
1. MCP request → McpServer parses JSON-RPC
2. McpServer.executeTool → McpToolExecutor
3. McpToolExecutor → LanguageAdapterRegistry → appropriate LanguageAdapter
4. LanguageAdapter performs PSI operations → returns Models (SymbolInfo, LocationInfo, etc.)

### Important Notes

- **1-based line/column numbers**: All position parameters use 1-based indexing (matching editor display)
- Python support requires PythonCore plugin (bundled in PyCharm, optional in IntelliJ)
- Java support requires Java plugin (bundled in IntelliJ IDEA, not available in PyCharm Community)
- IDE must be running with project open for MCP tools to work
- Index must be ready (not in "dumb mode") for symbol operations

## Build Configuration

### Dependencies Setup

```kotlin
intellijPlatform {
    intellijIdeaUltimate("2025.3")
    bundledPlugin("com.intellij.java")
    plugin("PythonCore:253.29346.138")
}
```

### Why This Configuration

Building requires both Java and Python PSI libraries. Key learnings:

| Platform | Java | Python | Notes |
|----------|------|--------|-------|
| PyCharm Community | ❌ | ✅ bundled | No Java PSI |
| PyCharm Professional | ❌ | ✅ bundled | Limited Java, not full PSI |
| IntelliJ IDEA Community | ✅ bundled | ❌ | Python not bundled |
| IntelliJ IDEA Ultimate | ✅ bundled | ❌ | Python available via marketplace |

**Solution**: Use IntelliJ IDEA Ultimate + `PythonCore` from marketplace.

### PythonCore vs Pythonid

- **PythonCore** (plugin ID: `PythonCore`) - Community Python plugin, contains base PSI classes (PyClass, PyFunction, PyNamedParameter, etc.)
- **Pythonid** (plugin ID: `Pythonid`) - Professional Python plugin, depends on PythonCore but doesn't include the base classes

Always use `PythonCore` for compilation - it provides the actual PSI implementation.

### Updating Plugin Versions

When updating the IntelliJ Platform version:
1. Change `intellijIdeaUltimate("version")`
2. Update `plugin("PythonCore:matching-version")` - find versions at https://plugins.jetbrains.com/plugin/631-python/versions
3. Ensure `ideaVersion.sinceBuild` and `untilBuild` cover the target range

## Adding Language Support

1. Create adapter class implementing `LanguageAdapter` in `<lang>/<Lang>LanguageAdapter.kt`
2. Register in new config file (e.g., `<lang>-support.xml`)
3. Add optional dependency in `plugin.xml` with config-file attribute
4. Add required PSI dependencies in `build.gradle.kts`

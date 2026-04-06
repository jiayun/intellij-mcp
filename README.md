# Code Intelligence MCP

Expose JetBrains IDE code analysis capabilities via [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) for integration with AI coding assistants like Claude Code.

## Features

- **Find Symbol** - Search for class, function, or variable definitions by name
- **Find References** - Find all usages of a symbol across the project
- **Get Symbol Info** - Get detailed information (type, documentation, signature)
- **List File Symbols** - List all symbols in a file with hierarchy
- **Get Type Hierarchy** - Get inheritance hierarchy for classes
- **Run Configurations** - List and execute IDE run configurations (tests, applications, etc.)
- **Test Results** - Get structured test results with pass/fail status, duration, failure messages, and stack traces*

\* Structured test results require SM Test Runner support. Rider's .NET test runner uses ReSharper backend which does not expose structured results to platform APIs. See [Known Limitations](#test-execution-limitations).

## Supported Languages

| Language | Status | File Extensions |
|----------|--------|-----------------|
| JavaScript/TypeScript | ✅ Supported | `.js`, `.mjs`, `.cjs`, `.ts`, `.mts`, `.cts`, `.jsx`, `.tsx` |
| Vue.js | ✅ Supported | `.vue` |
| Python | ✅ Supported | `.py`, `.pyi` |
| PHP | ✅ Supported | `.php`, `.phtml` |
| Java | ✅ Supported | `.java` |
| Kotlin | ✅ Supported | `.kt`, `.kts` |
| Rust | ✅ Supported | `.rs` |
| Go | ✅ Supported | `.go` |
| Swift | ✅ Supported | `.swift` |
| C# | ✅ Supported | `.cs` |
| Dart | ✅ Supported | `.dart` |

## Requirements

- JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, etc.) version 2025.1+
- For JavaScript/TypeScript support: JavaScript plugin (bundled in WebStorm, IntelliJ IDEA Ultimate)
- For Vue.js support: Vue.js plugin (bundled in WebStorm, available in IntelliJ IDEA Ultimate)
- For Python support: Python plugin installed
- For PHP support: PHP plugin installed (bundled in PhpStorm, available in IntelliJ IDEA Ultimate)
- For Java support: Java plugin installed (bundled in IntelliJ IDEA)
- For Kotlin support: Kotlin plugin installed (bundled in IntelliJ IDEA)
- For Rust support: Rust plugin installed (bundled in RustRover, available in IntelliJ IDEA Ultimate/CLion)
- For Go support: Go plugin installed (bundled in GoLand, available in IntelliJ IDEA Ultimate)
- For Swift support: **macOS only** - requires Xcode or Swift toolchain with SourceKit-LSP
- For C# support: requires [csharp-ls](https://github.com/razzmatazz/csharp-language-server) or [OmniSharp](https://github.com/OmniSharp/omnisharp-roslyn)
- For Dart support: Dart plugin installed (bundled in Android Studio, available in IntelliJ IDEA)

## Installation

### From JetBrains Marketplace

[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/29509-code-intelligence-mcp.svg)](https://plugins.jetbrains.com/plugin/29509-code-intelligence-mcp)

1. Open **Settings** → **Plugins** → **Marketplace**
2. Search for "[Code Intelligence MCP](https://plugins.jetbrains.com/plugin/29509-code-intelligence-mcp)"
3. Click **Install** → **Restart IDE**

### From Disk

1. Download `intellij-mcp-x.x.x.zip` from [Releases](https://github.com/jiayun/intellij-mcp/releases)
2. Open **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk...**
3. Select the zip file → **Restart IDE**

## Usage

### 1. Start the MCP Server

The server starts automatically when the IDE launches. You can also control it manually:

- **Tools** → **Code Intelligence MCP** → **Start MCP Server**
- **Tools** → **Code Intelligence MCP** → **Stop MCP Server**

The server runs on `http://localhost:9876` by default.

### 2. Verify Server

```bash
# Health check
curl http://localhost:9876/health
# Returns: OK

# Server info
curl http://localhost:9876/info
# Returns: {"name":"intellij-mcp","version":"1.0.0","languages":["python"]}
```

### 3. Connect Claude Code

```bash
claude mcp add intellij-mcp --transport http http://localhost:9876/mcp
```

Or copy the config from: **Tools** → **Code Intelligence MCP** → **Copy Claude Code Config**

### 4. Configure CLAUDE.md (Recommended)

Add the following to your project's `CLAUDE.md` or `~/.claude/CLAUDE.md` to help Claude Code leverage the IDE's code analysis:

```markdown
## Code Intelligence MCP Integration

When working with this Python codebase, prefer using intellij-mcp tools for:

- **Finding symbol definitions** - Use `find_symbol` instead of grep/ripgrep when looking for class, function, or variable definitions. The IDE understands the language structure and provides accurate results.

- **Finding references** - Use `find_references` to find all usages of a symbol. This is more accurate than text search as it understands scope and imports.

- **Getting symbol details** - Use `get_symbol_info` to get type information, documentation, and function signatures for a symbol at a specific location.

- **Understanding code structure** - Use `get_file_symbols` to get an overview of a file's classes, methods, and functions with their signatures.

- **Exploring inheritance** - Use `get_type_hierarchy` to understand class inheritance relationships.

**Note:** Line and column numbers are **1-based**, matching editor display. Line 16 in editor = `line=16` in API.

Note: intellij-mcp requires the JetBrains IDE to be running with the project open.
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `list_projects` | List all open projects in the IDE |
| `get_supported_languages` | Get list of supported languages |
| `find_symbol` | Find symbol definition by name |
| `find_references` | Find all references to a symbol |
| `get_symbol_info` | Get detailed symbol information |
| `get_file_symbols` | List all symbols in a file |
| `get_type_hierarchy` | Get class inheritance hierarchy |
| `list_run_configurations` | List all run configurations in the IDE project |
| `run_configuration` | Execute a run configuration by name |
| `get_test_results` | Get structured test results from a test execution |

### `includeLibraries` Parameter

The `find_symbol`, `find_references`, and `get_type_hierarchy` tools support an optional `includeLibraries` parameter (default: `false`). When set to `true`, the search scope expands to include project dependencies and libraries (e.g., JDK classes, Python stdlib, Dart SDK, Cargo crates).

Supported for: Java, Kotlin, Python, PHP, Rust, Go, Dart. Not applicable for JavaScript/TypeScript/Vue.js (FilenameIndex-based) or Swift/C# (LSP-based, scope controlled by language server).

## Example

```bash
# Find a symbol
curl -X POST http://localhost:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"find_symbol","arguments":{"name":"MyClass"}}}'
```

## Swift Support (macOS Only)

Swift support uses [SourceKit-LSP](https://github.com/swiftlang/sourcekit-lsp) for code analysis.

### Prerequisites

1. **macOS** - Swift support is only available on macOS
2. **Xcode or Swift Toolchain** - One of the following:
   - Install Xcode (recommended, includes SourceKit-LSP)
   - Install Xcode Command Line Tools: `xcode-select --install`
   - Install standalone Swift toolchain from [swift.org](https://swift.org/download/)

### Supported Project Types

- **SwiftPM projects** (with `Package.swift`) - Best support
- **Xcode projects** (`.xcodeproj`) - Requires building the project first

### Important Notes

- **First-time indexing**: When opening a Swift project for the first time, SourceKit-LSP needs to index the project. This may take 10-30 seconds depending on project size. You'll see "Waiting for SourceKit-LSP indexing..." in the IDE logs during this time.
- **Build your project first**: Run `swift build` before using code analysis for best results.

### Limitations

- `get_type_hierarchy` is not supported (SourceKit-LSP limitation)
- Project must be built for best `find_references` results
- `find_symbol` requires a non-empty search query

## C# Support

C# support uses an external LSP server ([csharp-ls](https://github.com/razzmatazz/csharp-language-server) or [OmniSharp](https://github.com/OmniSharp/omnisharp-roslyn)) for code analysis.

### Prerequisites

Install csharp-ls (recommended):
```bash
dotnet tool install --global csharp-ls
```

### Supported Project Types

- **SDK-style projects only** (.NET Core / .NET 5+)
- **Solution files** (`.sln`) - Best support
- **Project files** (`.csproj`) - Single project support
- Legacy .NET Framework projects (old-style `.csproj` with `ToolsVersion`) are **not supported**

### Important Notes

- **First-time indexing**: When opening a C# project for the first time, the LSP server needs to index the project. This may take 10-30 seconds depending on project size.
- **Build your project first**: Run `dotnet build` before using code analysis for best results.

### Compared to Swift Support

- `get_type_hierarchy` **is supported** for C# (unlike Swift)
- Cross-platform support (Windows, macOS, Linux)


## Test Execution Limitations

The `run_configuration` and `get_test_results` tools work best with IDEs that use the standard SM Test Runner framework:

| IDE | Run Tests | Structured Results |
|-----|-----------|-------------------|
| IntelliJ IDEA (JUnit, TestNG) | ✅ | ✅ |
| PyCharm (pytest, unittest) | ✅ | ✅ |
| WebStorm (Jest, Mocha, Vitest) | ✅ | ✅ |
| GoLand (go test) | ✅ | ✅ |
| Rider (.NET xUnit, NUnit, TUnit) | ✅ Triggers execution | ❌ ReSharper backend |

**Rider limitation**: Rider's .NET test runner communicates through the ReSharper backend via RD Protocol, which does not expose structured test results to IntelliJ Platform APIs. Tests will execute successfully in the IDE, but `get_test_results` returns a degraded response without individual test details. As a workaround, agents can run `dotnet test` directly for structured output.

## Development

```bash
# Build
./gradlew :core:buildPlugin

# Output: core/build/distributions/intellij-mcp-x.x.x.zip
```

### Run IDE with Plugin

```bash
# IntelliJ IDEA Ultimate (default)
./gradlew :core:runIde

# PyCharm Professional (for Python testing)
./gradlew :core:runPyCharm

# PhpStorm (for PHP testing)
./gradlew :core:runPhpStorm

# WebStorm (for JavaScript/TypeScript/Vue.js testing)
./gradlew :core:runWebStorm

# RustRover (for Rust testing)
./gradlew :core:runRustRover

# GoLand (for Go testing)
./gradlew :core:runGoLand

# Rider (for C# testing)
./gradlew :core:runRider
```

## License

[MIT](LICENSE)

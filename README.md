# Code Intelligence MCP

Expose JetBrains IDE code analysis capabilities via [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) for integration with AI coding assistants like Claude Code.

## Features

- **Find Symbol** - Search for class, function, or variable definitions by name
- **Find References** - Find all usages of a symbol across the project
- **Get Symbol Info** - Get detailed information (type, documentation, signature)
- **List File Symbols** - List all symbols in a file with hierarchy
- **Get Type Hierarchy** - Get inheritance hierarchy for classes

## Supported Languages

| Language | Status |
|----------|--------|
| Python   | ✅ Supported |
| Java     | ✅ Supported |
| Kotlin   | ✅ Supported |

## Requirements

- JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, etc.) version 2025.1+
- For Python support: Python plugin installed
- For Java support: Java plugin installed (bundled in IntelliJ IDEA)
- For Kotlin support: Kotlin plugin installed (bundled in IntelliJ IDEA)

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

## Example

```bash
# Find a symbol
curl -X POST http://localhost:9876/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"find_symbol","arguments":{"name":"MyClass"}}}'
```

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

# PyCharm Professional
./gradlew :core:runPyCharm
```

## License

[MIT](LICENSE)

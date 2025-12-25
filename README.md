# IntelliJ MCP

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
| Java     | 🚧 Coming soon |
| Kotlin   | 🚧 Coming soon |

## Requirements

- JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, etc.) version 2025.1+
- For Python support: Python plugin installed

## Installation

### From JetBrains Marketplace

1. Open **Settings** → **Plugins** → **Marketplace**
2. Search for "IntelliJ MCP"
3. Click **Install** → **Restart IDE**

### From Disk

1. Download `intellij-mcp-x.x.x.zip` from [Releases](https://github.com/jiayun/intellij-mcp/releases)
2. Open **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk...**
3. Select the zip file → **Restart IDE**

## Usage

### 1. Start the MCP Server

The server starts automatically when the IDE launches. You can also control it manually:

- **Tools** → **IntelliJ MCP** → **Start MCP Server**
- **Tools** → **IntelliJ MCP** → **Stop MCP Server**

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

Or copy the config from: **Tools** → **IntelliJ MCP** → **Copy Claude Code Config**

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

# Run IDE with plugin
./gradlew :core:runIde

# Output: core/build/distributions/intellij-mcp-x.x.x.zip
```

## License

[MIT](LICENSE)

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Ultimate 2025.3.1 (has Java bundled)
        intellijIdeaUltimate("2025.3.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("JavaScript")
        bundledPlugin("org.jetbrains.plugins.vue")
        // Python Community (provides base Python PSI classes)
        plugin("PythonCore:253.29346.138")
        // PHP plugin from marketplace
        plugin("com.jetbrains.php:253.29346.138")
        // Rust plugin from marketplace (RustRover features for IntelliJ IDEA)
        plugin("com.jetbrains.rust:253.29346.139")
        // Go plugin from marketplace (GoLand features for IntelliJ IDEA)
        plugin("org.jetbrains.plugins.go:253.29346.240")
        // Dart plugin from marketplace (bundled in Android Studio)
        plugin("Dart:503.0.0")

        pluginVerifier()
        zipSigner()
    }

    // JSON processing
    implementation("com.google.code.gson:gson:2.11.0")

    // HTTP Server - exclude slf4j to avoid conflict with IntelliJ's SLF4J
    implementation("io.ktor:ktor-server-core:2.3.12") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-server-netty:2.3.12") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-serialization-gson:2.3.12") {
        exclude(group = "org.slf4j")
    }

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // LSP4J for Swift (SourceKit-LSP) and C# (csharp-ls/OmniSharp) language support
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.2")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.21.2")
}

tasks {
    buildPlugin {
        archiveBaseName.set("intellij-mcp")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "info.jiayun.intellij-mcp"
        name = "Code Intelligence MCP"
        version = project.version.toString()

        description = """
            <p>Expose JetBrains IDE code analysis capabilities via
            <a href="https://modelcontextprotocol.io/">MCP (Model Context Protocol)</a>
            for integration with AI coding assistants like Claude Code.</p>

            <h3>Features</h3>
            <ul>
                <li><b>Find Symbol</b> - Search for class, function, or variable definitions by name</li>
                <li><b>Find References</b> - Find all usages of a symbol across the project</li>
                <li><b>Get Symbol Info</b> - Get type information, documentation, and signatures</li>
                <li><b>List File Symbols</b> - List all symbols in a file with hierarchy</li>
                <li><b>Get Type Hierarchy</b> - Get inheritance hierarchy for classes</li>
            </ul>

            <h3>Usage</h3>
            <ol>
                <li>Install the plugin and restart IDE</li>
                <li>MCP server starts automatically on port 9876</li>
                <li>Connect Claude Code: <code>claude mcp add intellij-mcp --transport http http://localhost:9876/mcp</code></li>
            </ol>

            <h3>Supported Languages</h3>
            <ul>
                <li>JavaScript/TypeScript (requires JavaScript plugin)</li>
                <li>Vue.js (requires Vue.js plugin)</li>
                <li>Python (requires Python plugin)</li>
                <li>PHP (requires PHP plugin)</li>
                <li>Java (requires Java plugin)</li>
                <li>Kotlin (requires Kotlin plugin)</li>
                <li>Rust (requires Rust plugin)</li>
                <li>Go (requires Go plugin)</li>
                <li>Swift (macOS only, requires Xcode or Swift toolchain)</li>
                <li>C# (requires csharp-ls or OmniSharp language server)</li>
                <li>Dart (requires Dart plugin)</li>
            </ul>

            <p>Source code: <a href="https://github.com/jiayun/intellij-mcp">GitHub</a></p>
        """.trimIndent()

        vendor {
            name = "Jiayun"
            url = "https://github.com/jiayun/intellij-mcp"
        }

        ideaVersion {
            sinceBuild = "251"
            untilBuild = "253.*"
        }

        changeNotes = """
            <h3>1.7.0</h3>
            <ul>
                <li><b>New:</b> Dart language support - find symbols, references, file structure, and type hierarchy in Dart code</li>
                <li>Requires Dart plugin (bundled in Android Studio, available in IntelliJ IDEA)</li>
            </ul>

            <h3>1.6.0</h3>
            <ul>
                <li><b>New:</b> Go language support - find symbols, references, and file structure in Go code</li>
                <li>Requires Go plugin (bundled in GoLand, available in IntelliJ IDEA Ultimate)</li>
            </ul>

            <h3>1.5.0</h3>
            <ul>
                <li><b>New:</b> C# language support via LSP - find symbols, references, file structure, and type hierarchy</li>
                <li>Requires csharp-ls (<code>dotnet tool install --global csharp-ls</code>) or OmniSharp</li>
                <li>Cross-platform support (Windows, macOS, Linux)</li>
                <li><b>Fix:</b> Swift line/column numbers off by one in find_references and get_symbol_info</li>
            </ul>

            <h3>1.4.0</h3>
            <ul>
                <li><b>New:</b> Rust language support - find symbols, references, and file structure in Rust code</li>
                <li>Requires Rust plugin (RustRover features for IntelliJ IDEA/CLion)</li>
            </ul>

            <h3>1.3.0</h3>
            <ul>
                <li><b>New:</b> Swift language support (macOS only) - uses SourceKit-LSP for code analysis</li>
                <li>Requires Xcode or Swift toolchain installed on macOS</li>
            </ul>

            <h3>1.2.0</h3>
            <ul>
                <li><b>New:</b> PHP support - full support for PHP classes, functions, traits</li>
                <li><b>New:</b> JavaScript/TypeScript support - full support for JS, TS, JSX, TSX files</li>
                <li><b>New:</b> Vue.js support - analyze Vue Single File Components (.vue)</li>
                <li>Added PhpStorm and WebStorm run configurations for testing</li>
            </ul>

            <h3>1.1.0</h3>
            <ul>
                <li><b>New:</b> Java language support - all features now work with Java code</li>
                <li><b>New:</b> Kotlin language support - full support for Kotlin code analysis</li>
            </ul>

            <h3>1.0.1</h3>
            <ul>
                <li><b>Breaking:</b> Line/column numbers are now 1-based (matching editor display) instead of 0-based</li>
                <li>Add <code>nameLocation</code> field to symbol info - provides precise location of function/class name for accurate reference lookups</li>
            </ul>
        """.trimIndent()
    }
}

intellijPlatformTesting {
    runIde {
        // Run with PyCharm Professional (for Python testing)
        // Note: IntelliJ IDEA Community and PyCharm Community are no longer published since 2025.3
        register("runPyCharm") {
            type = IntelliJPlatformType.PyCharmProfessional
            version = "2025.3.1"
        }

        // Run with WebStorm (for JavaScript/TypeScript/Vue.js testing)
        register("runWebStorm") {
            type = IntelliJPlatformType.WebStorm
            version = "2025.3.1"
        }

        // Run with PhpStorm (for PHP testing)
        register("runPhpStorm") {
            type = IntelliJPlatformType.PhpStorm
            version = "2025.3.1"
        }

        // Run with RustRover (for Rust testing)
        register("runRustRover") {
            type = IntelliJPlatformType.RustRover
            version = "2025.3.1"
        }

        // Run with GoLand (for Go testing)
        register("runGoLand") {
            type = IntelliJPlatformType.GoLand
            version = "2025.3.1"
        }

        // Run with Rider (for C# testing)
        register("runRider") {
            type = IntelliJPlatformType.Rider
            version = "2025.3.1"
        }
    }
}

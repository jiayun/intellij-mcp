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

    // LSP4J for Swift language support (via SourceKit-LSP)
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
                <li>Swift (macOS only, requires Xcode or Swift toolchain)</li>
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
    }
}

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
        // Use PyCharm Community for compilation (has bundled PythonCore)
        pycharmCommunity("2025.1.3")
        bundledPlugin("PythonCore")

        pluginVerifier()
        zipSigner()
    }

    // JSON processing
    implementation("com.google.code.gson:gson:2.11.0")

    // HTTP Server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
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
                <li>Python (requires Python plugin)</li>
                <li>Java - coming soon</li>
                <li>Kotlin - coming soon</li>
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
    }
}

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
        name = "IntelliJ MCP"
        version = project.version.toString()

        description = """
            Exposes IDE code analysis capabilities via MCP (Model Context Protocol)
            for integration with AI coding assistants like Claude Code.

            <h3>Supported Languages</h3>
            <ul>
                <li>Python (requires Python plugin)</li>
                <li>Java - coming soon</li>
                <li>Kotlin - coming soon</li>
            </ul>
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

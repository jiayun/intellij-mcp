package info.jiayun.intellijmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import info.jiayun.intellijmcp.mcp.McpServer
import info.jiayun.intellijmcp.settings.PluginSettings

class McpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = PluginSettings.getInstance()
        val server = McpServer.getInstance()

        if (settings.autoStart && !server.isRunning) {
            server.start(settings.port)
        }
    }
}

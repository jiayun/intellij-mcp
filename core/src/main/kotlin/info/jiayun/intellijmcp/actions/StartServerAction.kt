package info.jiayun.intellijmcp.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import info.jiayun.intellijmcp.mcp.McpServer
import info.jiayun.intellijmcp.settings.PluginSettings

class StartServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val server = McpServer.getInstance()
        if (server.isRunning) {
            notify(e, "MCP Server already running on port ${server.port}", NotificationType.INFORMATION)
            return
        }
        server.start(PluginSettings.getInstance().port)
        notify(e, "MCP Server started on port ${server.port}", NotificationType.INFORMATION)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !McpServer.getInstance().isRunning
    }

    private fun notify(e: AnActionEvent, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Intelligence MCP")
            .createNotification(message, type)
            .notify(e.project)
    }
}

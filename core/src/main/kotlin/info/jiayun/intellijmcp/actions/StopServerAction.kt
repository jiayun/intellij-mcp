package info.jiayun.intellijmcp.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import info.jiayun.intellijmcp.mcp.McpServer

class StopServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val server = McpServer.getInstance()
        if (!server.isRunning) {
            notify(e, "MCP Server is not running", NotificationType.INFORMATION)
            return
        }
        server.stop()
        notify(e, "MCP Server stopped", NotificationType.INFORMATION)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = McpServer.getInstance().isRunning
    }

    private fun notify(e: AnActionEvent, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("IntelliJ MCP")
            .createNotification(message, type)
            .notify(e.project)
    }
}

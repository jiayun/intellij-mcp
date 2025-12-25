package info.jiayun.intellijmcp.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import info.jiayun.intellijmcp.settings.PluginSettings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val port = PluginSettings.getInstance().port
        val config = "claude mcp add intellij-mcp --transport http http://localhost:$port/mcp"

        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(config), null)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Intelligence MCP")
            .createNotification("Claude Code config copied!", NotificationType.INFORMATION)
            .notify(e.project)
    }
}

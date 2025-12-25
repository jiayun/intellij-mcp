package info.jiayun.intellijmcp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import info.jiayun.intellijmcp.mcp.McpServer
import java.awt.event.MouseEvent
import javax.swing.Timer

class McpStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "IntellijMcpStatus"

    override fun getDisplayName(): String = "IntelliJ MCP Status"

    override fun createWidget(project: Project): StatusBarWidget {
        return McpStatusWidget(project)
    }

    override fun isAvailable(project: Project): Boolean = true
}

class McpStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val updateTimer: Timer

    init {
        // Update status every 2 seconds
        updateTimer = Timer(2000) {
            statusBar?.updateWidget(ID())
        }
        updateTimer.start()
    }

    override fun ID(): String = "IntellijMcpStatus"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        updateTimer.stop()
        Disposer.dispose(this)
    }

    override fun getText(): String {
        val server = McpServer.getInstance()
        return if (server.isRunning) {
            "MCP: ${server.port}"
        } else {
            "MCP: Off"
        }
    }

    override fun getTooltipText(): String {
        val server = McpServer.getInstance()
        return if (server.isRunning) {
            "MCP Server running on port ${server.port}\nClick to stop"
        } else {
            "MCP Server is not running\nClick to start"
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val server = McpServer.getInstance()
        if (server.isRunning) {
            server.stop()
        } else {
            server.start()
        }
        statusBar?.updateWidget(ID())
    }

    override fun getAlignment(): Float = 0f
}

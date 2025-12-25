package info.jiayun.intellijmcp.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "IntellijMcpSettings",
    storages = [Storage("intellij-mcp.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var autoStart: Boolean = true,
        var port: Int = 9876
    )

    private var state = State()

    override fun getState() = state
    override fun loadState(state: State) {
        this.state = state
    }

    var autoStart: Boolean
        get() = state.autoStart
        set(value) {
            state.autoStart = value
        }

    var port: Int
        get() = state.port
        set(value) {
            state.port = value
        }

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}

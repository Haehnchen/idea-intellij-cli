package de.espend.intellij.cli.settings

import de.espend.intellij.cli.util.IdeProductInfo
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level persistent settings for the Agent CLI plugin.
 *
 * Settings are stored in de.espend.intellij.cli.xml in the IDE's config directory.
 * Server host and port use IDE-specific defaults (no configuration needed).
 */
@Service(Service.Level.APP)
@State(
    name = "de.espend.intellij.cli",
    storages = [Storage("de.espend.intellij.cli.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    /**
     * Persistent state for plugin settings.
     */
    data class State(
        var enabled: Boolean = false // Server is disabled by default
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    /**
     * Returns the IDE-specific server host (always 127.0.0.1).
     */
    val serverHost: String
        get() = DEFAULT_SERVER_HOST

    /**
     * Returns the IDE-specific server port.
     */
    val serverPort: Int
        get() = IdeProductInfo.getDefaultPort()

    /**
     * Returns the server URL for display purposes.
     */
    fun getServerUrl(): String = "http://$serverHost:$serverPort"

    companion object {
        const val DEFAULT_SERVER_HOST = "127.0.0.1"

        fun getInstance(): PluginSettings = service()
    }
}

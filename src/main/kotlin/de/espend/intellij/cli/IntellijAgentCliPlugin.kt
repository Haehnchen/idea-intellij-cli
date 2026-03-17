package de.espend.intellij.cli

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import de.espend.intellij.cli.server.HttpServer
import de.espend.intellij.cli.settings.PluginSettings

/**
 * Main plugin component that manages the HTTP server lifecycle.
 *
 * The server is disabled by default and must be enabled in settings.
 */
@Service(Service.Level.APP)
class IntellijAgentCliPlugin {

    private var server: HttpServer? = null

    /**
     * Starts the HTTP server if it's enabled in settings.
     */
    fun startServer() {
        val settings = PluginSettings.getInstance()
        if (!settings.enabled) {
            println("[intellij-agent-cli] Server is disabled in settings. Skipping start.")
            return
        }

        if (server == null) {
            server = HttpServer()
        }
        server?.start()
    }

    /**
     * Stops the HTTP server.
     */
    fun stopServer() {
        server?.stop()
        server = null
    }

    companion object {
        val instance: IntellijAgentCliPlugin
            get() = service()
    }
}

/**
 * Application listener to start the server when the IDE starts.
 * The server will only start if it's enabled in settings.
 */
class IdeStartupListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: com.intellij.openapi.wm.IdeFrame) {
        // Start the HTTP server when IDE is activated (if enabled in settings)
        IntellijAgentCliPlugin.instance.startServer()
    }
}

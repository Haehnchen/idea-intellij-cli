package de.espend.intellij.cli

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
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
class IntellijAgentCliPlugin : Disposable {

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

    override fun dispose() {
        stopServer()
    }

    companion object {
        val instance: IntellijAgentCliPlugin
            get() = service()
    }
}

/**
 * Starts the HTTP server when the IDE finishes initializing.
 * Uses AppLifecycleListener which supports dynamic plugin loading.
 */
class IdeStartupListener : AppLifecycleListener {
    override fun appStarted() {
        IntellijAgentCliPlugin.instance.startServer()
    }
}

package de.espend.intellij.cli

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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
    @Volatile private var running = false

    /**
     * Starts the HTTP server if it's enabled in settings.
     * Safe to call multiple times (e.g. once per project open).
     */
    fun startServer() {
        if (running) return

        val settings = PluginSettings.getInstance()
        if (!settings.enabled) {
            println("[intellij-agent-cli] Server is disabled in settings. Skipping start.")
            return
        }

        running = true
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
        running = false
        stopServer()
    }

    companion object {
        val instance: IntellijAgentCliPlugin
            get() = service()
    }
}

/**
 * Starts the HTTP server when the first project opens.
 * postStartupActivity is dynamic-plugin-compatible and fires even when
 * the plugin is installed without a restart.
 */
class ServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        IntellijAgentCliPlugin.instance.startServer()
    }
}

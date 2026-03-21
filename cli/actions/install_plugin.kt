// Action: Install or update a plugin from a local ZIP/JAR file
// Usage: intellij-cli action install_plugin file="/absolute/path/to/plugin.zip"
// Usage: intellij-cli action install_plugin file="/absolute/path/to/plugin.zip" force_restart=true
//
// Parameters:
//   file=<string>            absolute path to plugin ZIP or JAR
//   force_restart=<bool>     restart IDE after scheduling install (default: false)

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginNode
import com.intellij.openapi.application.ApplicationManager
import java.nio.file.Paths

// --- Configure ---
val file: String           = ""    // absolute path to plugin ZIP or JAR
val force_restart: Boolean = false // restart IDE after scheduling install
// -----------------

if (file.isEmpty()) {
    println("ERROR: file parameter is required")
    println("Usage: intellij-cli action install_plugin file=/absolute/path/to/plugin.zip")
} else {
    val pluginPath = Paths.get(file)

    if (!pluginPath.toFile().exists()) {
        println("ERROR: File not found: $file")
    } else {
        // Use IntelliJ's own descriptor loader (same logic as the plugin manager UI)
        val loadMethod = PluginInstaller::class.java
            .getDeclaredMethod("lambda\$installFromDisk\$0", java.nio.file.Path::class.java)
        loadMethod.isAccessible = true
        val descriptor = loadMethod.invoke(null, pluginPath) as? IdeaPluginDescriptorImpl

        if (descriptor == null) {
            println("ERROR: Could not load plugin descriptor from: $file")
        } else {
            val pluginId   = descriptor.pluginId
            val pluginName = descriptor.name
            val pluginVersion = descriptor.version

            println("Plugin:  $pluginName")
            println("ID:      $pluginId")
            println("Version: $pluginVersion")
            println()

            val existing = PluginManagerCore.getPlugin(pluginId)
            if (existing != null) {
                println("Installed: v${existing.version} → updating to v$pluginVersion")
            } else {
                println("Status: new installation")
            }
            println()

            // Try dynamic install first (no restart required)
            val dynamic = runCatching {
                PluginInstaller.installAndLoadDynamicPlugin(pluginPath, descriptor)
            }

            if (dynamic.getOrNull() == true) {
                println("SUCCESS: Plugin installed and loaded dynamically (no restart required)")
            } else {
                // Fall back: schedule install after restart
                val scheduled = runCatching {
                    val node = PluginNode(pluginId, pluginName, pluginVersion)
                    PluginInstaller.installAfterRestart(node, pluginPath, existing?.pluginPath, false)
                }

                if (scheduled.isSuccess) {
                    if (force_restart) {
                        println("SUCCESS: Plugin scheduled for installation — restarting IntelliJ now")
                        ApplicationManager.getApplication().restart()
                    } else {
                        println("SUCCESS: Plugin scheduled for installation — please restart IntelliJ")
                    }
                } else {
                    println("ERROR: Installation failed")
                    println(scheduled.exceptionOrNull()?.message ?: "unknown error")
                }
            }
        }
    }
}

// Action: List installed plugins with versions
// Usage: intellij-cli action plugins

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

val plugins = PluginManagerCore.plugins.sortedBy { it.name }

println("Installed Plugins (${plugins.size})")
println("=" .repeat(60))

for (plugin in plugins) {
    val enabled = if (plugin.isEnabled) "✓" else " "
    val version = plugin.version ?: "unknown"
    val id = plugin.pluginId.idString
    println("[$enabled] ${plugin.name} v$version")
    println("    id: $id")
}

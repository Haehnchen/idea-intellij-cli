// Action: Install or update a plugin from a local ZIP/JAR file
// Usage: intellij-cli action install_plugin file="/absolute/path/to/plugin.zip"

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginNode
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

// --- Configure ---
val file: String = ""  // absolute path to plugin ZIP or JAR
// -----------------

fun readPluginXmlFromZip(zipPath: Path): String? {
    return try {
        ZipFile(zipPath.toFile()).use { zip ->
            // 1. Try direct: META-INF/plugin.xml at root of zip
            val direct = zip.entries().asSequence().firstOrNull { e ->
                e.name.endsWith("/META-INF/plugin.xml") || e.name == "META-INF/plugin.xml"
            }
            if (direct != null) return zip.getInputStream(direct).bufferedReader().readText()

            // 2. plugin.xml is nested inside a JAR within the zip (IntelliJ plugin distribution format)
            val jarEntry = zip.entries().asSequence().firstOrNull { e ->
                e.name.endsWith(".jar") && !e.isDirectory &&
                    !e.name.contains("/lib/kotlin") && !e.name.contains("/lib/jetty") &&
                    !e.name.contains("/lib/javalin") && !e.name.contains("annotations")
            } ?: return null

            val jarBytes = zip.getInputStream(jarEntry).readBytes()
            val innerZip = java.util.zip.ZipInputStream(jarBytes.inputStream())
            var entry = innerZip.nextEntry
            while (entry != null) {
                if (entry.name == "META-INF/plugin.xml") {
                    return innerZip.readBytes().toString(Charsets.UTF_8)
                }
                entry = innerZip.nextEntry
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}

fun parsePluginXml(xml: String): Triple<String, String, String>? {
    return try {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val root = doc.documentElement
        val id = root.getElementsByTagName("id").item(0)?.textContent?.trim()
            ?: root.getElementsByTagName("name").item(0)?.textContent?.trim()
            ?: return null
        val name = root.getElementsByTagName("name").item(0)?.textContent?.trim() ?: id
        val version = root.getElementsByTagName("version").item(0)?.textContent?.trim() ?: "unknown"
        Triple(id, name, version)
    } catch (e: Exception) {
        null
    }
}

if (file.isEmpty()) {
    println("ERROR: file parameter is required")
    println("Usage: intellij-cli action install_plugin file=/absolute/path/to/plugin.zip")
} else {
    val pluginPath = Paths.get(file)

    if (!pluginPath.toFile().exists()) {
        println("ERROR: File not found: $file")
    } else {
        val xml = readPluginXmlFromZip(pluginPath)
        if (xml == null) {
            println("ERROR: Could not find META-INF/plugin.xml in: $file")
        } else {
            val parsed = parsePluginXml(xml)
            if (parsed == null) {
                println("ERROR: Failed to parse plugin.xml")
            } else { val (pluginId, pluginName, pluginVersion) = parsed

            println("Plugin:  $pluginName")
            println("ID:      $pluginId")
            println("Version: $pluginVersion")
            println()

            val id = PluginId.getId(pluginId)
            val existing = PluginManagerCore.getPlugin(id)
            if (existing != null) {
                println("Installed: v${existing.version} → updating to v$pluginVersion")
            } else {
                println("Status: new installation")
            }
            println()

            // Try dynamic install first (reflection on private loader)
            val dynamicResult = runCatching {
                val loadMethod = PluginInstaller::class.java
                    .getDeclaredMethod("lambda\$installFromDisk\$0", Path::class.java)
                loadMethod.isAccessible = true
                val descriptor = loadMethod.invoke(null, pluginPath) as? IdeaPluginDescriptorImpl
                if (descriptor != null) {
                    PluginInstaller.installAndLoadDynamicPlugin(pluginPath, descriptor)
                } else {
                    false
                }
            }

            if (dynamicResult.getOrNull() == true) {
                println("SUCCESS: Plugin installed and loaded dynamically (no restart required)")
            } else {
                // Fall back: schedule install after restart using PluginNode
                val restartResult = runCatching {
                    val node = PluginNode(id, pluginName, pluginVersion)
                    val existingPath = existing?.pluginPath
                    PluginInstaller.installAfterRestart(node, pluginPath, existingPath, false)
                }

                if (restartResult.isSuccess) {
                    println("SUCCESS: Plugin scheduled for installation — please restart IntelliJ")
                } else {
                    println("ERROR: Installation failed")
                    println(restartResult.exceptionOrNull()?.message ?: "unknown error")
                }
            }
            } // end parsed != null
        }
    }
}

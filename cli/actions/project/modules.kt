// Action: List project modules
// Usage: intellij-cli action modules

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.Module

if (project == null) {
    println("Error: No project specified. Use -p <project-name>")
} else {
    val moduleManager = ModuleManager.getInstance(project)
    val modules = moduleManager.modules

    println("Modules in: ${project.name}")
    println("=".repeat(50))
    println("Total: ${modules.size} module(s)\n")

    for (module in modules) {
        val name = module.name
        val type = module.moduleTypeName ?: "unknown"
        val path = module.moduleFile?.path ?: "no file"

        println("- $name")
        println("  Type: $type")
        println("  Path: $path")
    }
}

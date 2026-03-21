// Action: Show project structure tree
// Usage: intellij-cli action tree
//        intellij-cli action tree path="src/main/kotlin"

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

// --- Configure ---
val path: String = ""  // subdirectory to start from (relative to project root), or empty for project root
// -----------------

if (project == null) {
    println("Error: No project specified. Use -p <project-name>")
} else {
    val basePath = project.basePath ?: error("Project base path not available")

    val startDir = if (path.isNotEmpty()) {
        val fullPath = if (path.startsWith("/")) path else "$basePath/$path"
        LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: error("Path not found: $path")
    } else {
        project.baseDir ?: error("Project has no base directory")
    }

    println("Project: ${project.name}")
    println("Base:    $basePath")
    if (path.isNotEmpty()) println("Path:    $path")
    println()

    fun printTree(dir: VirtualFile, prefix: String = "", depth: Int = 0) {
        if (depth > 3) return

        val children = dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in listOf("node_modules", "target", "build", "out", ".git") }
        val files = children.filter { !it.isDirectory }.take(10)

        for ((i, child) in (dirs + files).withIndex()) {
            val isLast = i == (dirs + files).size - 1
            val connector = if (isLast) "└── " else "├── "
            val newPrefix = if (isLast) prefix + "    " else prefix + "│   "

            println(prefix + connector + child.name)

            if (child.isDirectory && depth < 3) {
                printTree(child, newPrefix, depth + 1)
            }
        }
    }

    printTree(startDir)
}

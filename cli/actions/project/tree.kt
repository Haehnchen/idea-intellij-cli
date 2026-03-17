// Action: Show project structure tree
// Usage: intellij-cli action tree

import com.intellij.openapi.vfs.VirtualFile

if (project == null) {
    println("Error: No project specified. Use -p <project-name>")
} else {
    val baseDir = project.baseDir
    if (baseDir == null) {
        println("Error: Project has no base directory")
    } else {
        println("Project: ${project.name}")
        println("Base:    ${project.basePath}")
        println()

        fun printTree(dir: VirtualFile, prefix: String = "", depth: Int = 0) {
            if (depth > 3) return // Limit depth

            val children = dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in listOf("node_modules", "target", "build", "out", ".git") }
            val files = children.filter { !it.isDirectory }.take(10) // Limit files shown

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

        printTree(baseDir)
    }
}

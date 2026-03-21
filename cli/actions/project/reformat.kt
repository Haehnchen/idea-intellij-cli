// Action: Reformat files using the project's code style settings
// Usage: intellij-cli action reformat
//        intellij-cli action reformat file="src/main/kotlin/com/example/Foo.kt"
//        intellij-cli action reformat file="src/main/kotlin/**/*.kt"

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.nio.file.FileSystems
import java.nio.file.Files

// --- Configure ---
val file: String = ""  // file path, glob pattern, or empty to reformat all project files
// -----------------

val basePath = project.basePath ?: error("Project base path not available")

val ignoredDirs = setOf("node_modules", "target", "build", "out", ".git", ".gradle", ".idea")

fun collectGlobFiles(glob: String): List<VirtualFile> {
    val root = java.nio.file.Paths.get(basePath)
    val matcher = FileSystems.getDefault().getPathMatcher("glob:${root}/$glob")
    val matched = mutableListOf<VirtualFile>()
    Files.walk(root).use { stream ->
        stream.filter { !Files.isDirectory(it) && matcher.matches(it) }.forEach { p ->
            LocalFileSystem.getInstance().findFileByNioFile(p)?.let { matched.add(it) }
        }
    }
    return matched
}

fun collectAllFiles(): List<VirtualFile> {
    val root = java.nio.file.Paths.get(basePath)
    val matched = mutableListOf<VirtualFile>()
    Files.walk(root).use { stream ->
        stream.filter { p ->
            !Files.isDirectory(p) && p.none { part -> part.toString() in ignoredDirs }
        }.forEach { p ->
            LocalFileSystem.getInstance().findFileByNioFile(p)?.let { matched.add(it) }
        }
    }
    return matched
}

fun reformatVirtualFile(virtualFile: VirtualFile): Boolean {
    val relativePath = virtualFile.path.removePrefix(basePath).trimStart('/')
    val psiFile = readAction { PsiManager.getInstance(project).findFile(virtualFile) }
                  as? com.intellij.psi.PsiFile ?: return false
    application.invokeAndWait {
        writeAction {
            CodeStyleManager.getInstance(project).reformat(psiFile)
        }
    }
    println("Reformatted: $relativePath")
    return true
}

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else {
    val isGlob = file.contains('*') || file.contains('?') || file.contains('[')

    val files: List<VirtualFile> = when {
        file.isBlank() -> collectAllFiles()
        isGlob -> collectGlobFiles(file)
        else -> {
            val fullPath = if (file.startsWith("/")) file else "$basePath/$file"
            listOfNotNull(LocalFileSystem.getInstance().findFileByPath(fullPath)
                .also { if (it == null) println("Error: File not found: $file") })
        }
    }

    if (files.isNotEmpty()) {
        var count = 0
        for (vf in files) {
            if (reformatVirtualFile(vf)) count++
        }
        println("Done: $count file(s) reformatted.")
    }
}

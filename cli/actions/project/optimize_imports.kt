// Action: Optimize imports for a file
// Usage: intellij-cli action optimize_imports file=src/main/kotlin/com/example/Foo.kt

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager

// --- Configure ---
val file: String = ""  // relative to project root, e.g. "src/main/kotlin/com/example/Foo.kt"
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (file.isBlank()) {
    println("Error: file parameter is required.")
    println("Usage: intellij-cli action optimize_imports file=src/main/kotlin/com/example/Foo.kt")
} else {
    val virtualFile = LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$file")

    if (virtualFile == null) {
        println("Error: File not found: $file")
    } else {
        val relativePath = virtualFile.path.removePrefix(project.basePath ?: "").trimStart('/')
        val psiFile = readAction { PsiManager.getInstance(project).findFile(virtualFile) }
                      as? com.intellij.psi.PsiFile

        if (psiFile == null) {
            println("Error: Could not parse file: $relativePath")
        } else {
            application.invokeAndWait {
                OptimizeImportsProcessor(project, psiFile).run()
            }
            println("Imports optimized: $relativePath")
        }
    }
}

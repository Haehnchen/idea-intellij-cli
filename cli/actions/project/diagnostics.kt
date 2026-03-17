// Action: Get diagnostics (errors/warnings) for a file
// Usage: intellij-cli action diagnostics file=src/main/kotlin/Foo.kt

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

// --- Configure ---
val file: String? = null  // e.g. "src/main/kotlin/com/example/Foo.kt" or null for active file
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else {
    val editorManager = FileEditorManager.getInstance(project)

    // Resolve virtual file: by path or active editor
    val virtualFile = if (file != null) {
        val fullPath = "${project.basePath}/$file"
        LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: run { println("Error: File not found: $file"); null }
    } else {
        editorManager.selectedFiles.firstOrNull()
            ?: run { println("Error: No file open in editor and no file specified."); null }
    }

    if (virtualFile != null) {
        val relativePath = virtualFile.path.removePrefix(project.basePath ?: "").trimStart('/')

        // Open temporarily if not already open (daemon only analyzes open files)
        val wasAlreadyOpen = editorManager.isFileOpen(virtualFile)
        if (!wasAlreadyOpen) {
            application.invokeAndWait {
                editorManager.openFile(virtualFile, false)
            }
            Thread.sleep(800) // let daemon start analysis
        }

        try {
            println("Diagnostics: $relativePath")
            println("=".repeat(60))

            readAction {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

                if (psiFile == null || document == null) {
                    println("Error: Could not parse file.")
                } else {
                    data class Problem(val severity: String, val line: Int, val col: Int, val message: String)
                    val problems = mutableListOf<Problem>()

                    DaemonCodeAnalyzerEx.processHighlights(
                        document,
                        project,
                        HighlightSeverity.WEAK_WARNING,
                        0,
                        document.textLength
                    ) { info ->
                        if (info.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                            val line = document.getLineNumber(info.startOffset) + 1
                            val col  = info.startOffset - document.getLineStartOffset(line - 1) + 1
                            val severity = when {
                                info.severity.myVal >= HighlightSeverity.ERROR.myVal   -> "ERROR"
                                info.severity.myVal >= HighlightSeverity.WARNING.myVal -> "WARNING"
                                else -> "WEAK_WARNING"
                            }
                            problems.add(Problem(severity, line, col, info.description ?: "unknown"))
                        }
                        problems.size < 200
                    }

                    if (problems.isEmpty()) {
                        println("No problems found. File looks clean.")
                    } else {
                        val errors   = problems.count { it.severity == "ERROR" }
                        val warnings = problems.count { it.severity == "WARNING" }
                        val weak     = problems.count { it.severity == "WEAK_WARNING" }
                        println("Found ${problems.size} problem(s): $errors error(s), $warnings warning(s), $weak weak warning(s)\n")
                        for (p in problems) {
                            println("[${p.severity}] ${p.line}:${p.col}  ${p.message}")
                        }
                    }
                }
            }
        } finally {
            if (!wasAlreadyOpen) {
                application.invokeAndWait {
                    editorManager.closeFile(virtualFile)
                }
            }
        }
    }
}

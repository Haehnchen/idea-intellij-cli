// Action: Rename a symbol at a given file position across the entire project
// Usage: intellij-cli action rename file="src/Foo.kt" line=10 column=7 new_name="Bar"

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor

// --- Configure ---
val file: String?     = null  // relative to project root, e.g. "src/main/kotlin/Foo.kt" — required
val line: Int         = 0     // 1-based line number — required
val column: Int       = 0     // 1-based column number (0 = start of line)
val new_name: String? = null  // new name for the symbol — required
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (file == null) {
    println("Error: 'file' must be specified (relative to project root).")
} else if (line <= 0) {
    println("Error: 'line' must be a positive 1-based line number.")
} else if (new_name.isNullOrBlank()) {
    println("Error: 'new_name' must be specified.")
} else {
    val fullPath = "${project.basePath}/$file"
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)

    if (virtualFile == null) {
        println("Error: File not found: $file")
    } else {
        // Keep namedElement typed as PsiNamedElement so it can be passed to RenameProcessor
        var namedElement: PsiNamedElement? = null

        readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            if (psiFile != null && document != null) {
                val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
                val offset = document.getLineStartOffset(lineIdx) + (column - 1).coerceAtLeast(0)
                val element = psiFile.findElementAt(offset)
                namedElement = generateSequence(element) { it.parent }
                    .take(6)
                    .filterIsInstance<PsiNamedElement>()
                    .firstOrNull()
            }
        }

        if (namedElement == null) {
            println("Error: No renameable symbol at $file:$line:$column")
            println("Tip: Position cursor on a class, method, field, or variable name.")
        } else {
            val oldName = namedElement!!.name ?: "unknown"
            application.invokeAndWait {
                RenameProcessor(
                    project, namedElement!!, new_name,
                    /*searchInComments=*/ true,
                    /*searchInTextOccurrences=*/ false
                ).run()
            }
            println("Renamed '$oldName' → '$new_name'")
        }
    }
}

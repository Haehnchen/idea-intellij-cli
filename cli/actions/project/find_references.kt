// Action: Find all references to a symbol at a given file position
// Usage: intellij-cli action find_references file=src/Foo.kt line=42 column=15

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

// --- Configure ---
val file: String? = null   // relative to project root, e.g. "src/main/kotlin/Foo.kt" — null = active file
val line: Int    = 0       // 1-based line number (0 = use cursor position in active editor)
val column: Int  = 0       // 1-based column number (0 = use cursor position in active editor)
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else {
    val editorManager = FileEditorManager.getInstance(project)

    val virtualFile = if (file != null) {
        val fullPath = "${project.basePath}/$file"
        LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: run { println("Error: File not found: $file"); null }
    } else {
        editorManager.selectedFiles.firstOrNull()
            ?: run { println("Error: No file open in editor and no file specified."); null }
    }

    if (virtualFile != null) {
        readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

            if (psiFile == null || document == null) {
                println("Error: Could not parse file.")
            } else {
                val relativePath = virtualFile.path.removePrefix(project.basePath ?: "").trimStart('/')

                // Determine offset: from explicit line/col or active cursor
                val offset = if (line > 0) {
                    val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
                    document.getLineStartOffset(lineIdx) + (column - 1).coerceAtLeast(0)
                } else {
                    val textEditor = editorManager.selectedEditors.firstOrNull() as? TextEditor
                    textEditor?.editor?.caretModel?.primaryCaret?.offset ?: 0
                }

                val resolvedLine   = document.getLineNumber(offset) + 1
                val resolvedColumn = offset - document.getLineStartOffset(resolvedLine - 1) + 1

                val element = psiFile.findElementAt(offset)
                val namedElement = generateSequence(element) { it.parent }
                    .take(6)
                    .filterIsInstance<PsiNamedElement>()
                    .firstOrNull()

                if (namedElement == null) {
                    println("No named symbol at $relativePath:$resolvedLine:$resolvedColumn")
                    println("Tip: Position cursor on a class, method, field, or variable name.")
                } else {
                    val symbolName = namedElement.name ?: "unknown"
                    println("References to '$symbolName'  ($relativePath:$resolvedLine:$resolvedColumn)")
                    println("=".repeat(60))

                    data class Usage(val file: String, val line: Int, val context: String)
                    val usages = mutableListOf<Usage>()

                    ReferencesSearch.search(namedElement).forEach(Processor { ref ->
                        val refElement = ref.element
                        val refVFile = refElement.containingFile?.virtualFile
                        if (refVFile != null) {
                            val refDoc = PsiDocumentManager.getInstance(project)
                                .getDocument(refElement.containingFile)
                            if (refDoc != null) {
                                val refLine  = refDoc.getLineNumber(refElement.textOffset) + 1
                                val start    = refDoc.getLineStartOffset(refLine - 1)
                                val end      = refDoc.getLineEndOffset(refLine - 1)
                                val context  = refDoc.getText(TextRange(start, end)).trim()
                                val refPath  = refVFile.path.removePrefix(project.basePath ?: "").trimStart('/')
                                usages.add(Usage(refPath, refLine, context))
                            }
                        }
                        usages.size < 200
                    })

                    if (usages.isEmpty()) {
                        println("No references found.")
                    } else {
                        println("Found ${usages.size} reference(s):\n")
                        for (u in usages.sortedWith(compareBy({ it.file }, { it.line }))) {
                            println("${u.file}:${u.line}")
                            println("  ${u.context}")
                        }
                    }
                }
            }
        }
    }
}

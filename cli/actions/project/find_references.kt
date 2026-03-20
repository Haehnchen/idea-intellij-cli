// Action: Find all references to a symbol at a given file position
// Usage: intellij-cli action find_references file=src/Foo.kt line=42 column=15

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

// --- Configure ---
val file: String? = null   // relative to project root, e.g. "src/main/kotlin/Foo.kt" — required
val line: Int    = 0       // 1-based line number — required
val column: Int  = 0       // 1-based column number (0 = start of line)
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (file == null) {
    println("Error: 'file' must be specified (relative to project root, e.g. \"src/main/kotlin/Foo.kt\").")
} else if (line <= 0) {
    println("Error: 'line' must be a positive 1-based line number.")
} else {
    val fullPath = "${project.basePath}/$file"
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)

    if (virtualFile == null) {
        println("Error: File not found: $file")
    } else {
        readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

            if (psiFile == null || document == null) {
                println("Error: Could not parse file.")
            } else {
                val relativePath = virtualFile.path.removePrefix(project.basePath ?: "").trimStart('/')

                val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
                val offset = document.getLineStartOffset(lineIdx) + (column - 1).coerceAtLeast(0)

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
                    val defLine = document.getLineNumber(namedElement.textOffset) + 1
                    val defCol = namedElement.textOffset - document.getLineStartOffset(defLine - 1) + 1
                    val defStart = document.getLineStartOffset(defLine - 1)
                    val defEnd = document.getLineEndOffset(defLine - 1)
                    val defContext = document.getText(TextRange(defStart, defEnd))

                    println("Symbol: '$symbolName'  ($relativePath:$defLine:$defCol)")
                    println("  $defContext")
                    println("  ${" ".repeat(defCol - 1)}^")
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

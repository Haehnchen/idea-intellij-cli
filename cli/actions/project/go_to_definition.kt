// Action: Go to the declaration of a symbol at a given file position
// Usage: intellij-cli action go_to_definition file="src/Foo.kt" line=42 column=15

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement

// --- Configure ---
val file: String? = null  // relative to project root, e.g. "src/main/kotlin/Foo.kt" — required
val line: Int     = 0     // 1-based line number — required
val column: Int   = 0     // 1-based column number (0 = start of line)
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (file == null) {
    println("Error: 'file' must be specified (relative to project root).")
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
                val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
                val offset = document.getLineStartOffset(lineIdx) + (column - 1).coerceAtLeast(0)
                val element = psiFile.findElementAt(offset)

                if (element == null) {
                    println("No element at $file:$line:$column")
                } else {
                    // Try to resolve via reference chain (element or its parent)
                    val ref = generateSequence(element) { it.parent }
                        .take(4)
                        .flatMap { it.references.asSequence() }
                        .firstOrNull()
                    val resolved = ref?.resolve()

                    if (resolved != null) {
                        val targetVFile = resolved.containingFile?.virtualFile
                        val targetDoc = resolved.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        }
                        if (targetVFile != null && targetDoc != null) {
                            val targetLine = targetDoc.getLineNumber(resolved.textOffset) + 1
                            val targetCol = resolved.textOffset - targetDoc.getLineStartOffset(targetLine - 1) + 1
                            val targetPath = targetVFile.path.removePrefix(project.basePath ?: "").trimStart('/')
                            val name = (resolved as? PsiNamedElement)?.name ?: element.text

                            println("Definition of '$name':")
                            println("  $targetPath:$targetLine:$targetCol")

                            val lineStart = targetDoc.getLineStartOffset(targetLine - 1)
                            val lineEnd = targetDoc.getLineEndOffset(targetLine - 1)
                            println("  ${targetDoc.getText(TextRange(lineStart, lineEnd)).trim()}")
                        } else {
                            println("Resolved but no source location available (may be in a library).")
                        }
                    } else {
                        // Check if the element itself is a declaration
                        val namedElement = generateSequence(element) { it.parent }
                            .take(4)
                            .filterIsInstance<PsiNamedElement>()
                            .firstOrNull()
                        if (namedElement != null) {
                            println("'${namedElement.name}' is declared at this location ($file:$line:$column).")
                        } else {
                            println("Could not resolve definition at $file:$line:$column")
                            println("Tip: Position the cursor on an identifier that references a symbol.")
                        }
                    }
                }
            }
        }
    }
}

// Action: Find all references to a file (imports, includes, requires, etc.)
// Usage: intellij-cli action find_usages project="delos" file="templates/dashboard/empty.html.twig"
// Searches for direct references to the PsiFile using the standard IntelliJ reference search.

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.util.Processor
import java.util.concurrent.Callable

// --- Configure ---
val file: String? = null   // relative to project root, e.g. "src/main/kotlin/Foo.kt" — required
val limit: Int   = 500     // maximum number of results
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (file == null) {
    println("Error: 'file' must be specified (relative to project root, e.g. \"src/main/kotlin/Foo.kt\").")
} else {
    val fullPath = "${project.basePath}/$file"
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)

    if (virtualFile == null) {
        println("Error: File not found: $file")
    } else {
        val lines = ReadAction.nonBlocking(Callable {
            val out = mutableListOf<String>()
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)

            if (psiFile == null) {
                out.add("Error: Could not parse file: $file")
            } else {
                val projectBase = project.basePath ?: ""
                val docManager = PsiDocumentManager.getInstance(project)
                val usageTypeProviders = UsageTypeProvider.EP_NAME.extensionList

                out.add("File: $file")
                out.add("=".repeat(60))

                data class UsageEntry(
                    val usageFile: String,
                    val usageLine: Int,
                    val usageCol: Int,
                    val usageContext: String,
                    val usageTypeName: String
                )

                val usagesByType = LinkedHashMap<String, MutableList<UsageEntry>>()
                var totalCount = 0

                ReferencesSearch.search(psiFile, GlobalSearchScope.projectScope(project)).forEach(Processor { ref ->
                    if (totalCount >= limit) return@Processor false

                    val refElement = ref.element
                    val refVFile = refElement.containingFile?.virtualFile ?: return@Processor true

                    // Skip self-references
                    if (refVFile == virtualFile) return@Processor true

                    val refDoc = docManager.getDocument(refElement.containingFile) ?: return@Processor true

                    // Determine usage type
                    var typeName = "Usage"
                    for (provider in usageTypeProviders) {
                        val ut = provider.getUsageType(refElement)
                        if (ut != null) {
                            typeName = ut.toString()
                            break
                        }
                    }

                    val refLine = refDoc.getLineNumber(refElement.textOffset) + 1
                    val refCol = refElement.textOffset - refDoc.getLineStartOffset(refLine - 1) + 1
                    val start = refDoc.getLineStartOffset(refLine - 1)
                    val end = refDoc.getLineEndOffset(refLine - 1)
                    val context = refDoc.getText(TextRange(start, end)).trim()
                    val refPath = refVFile.path.removePrefix(projectBase).trimStart('/')

                    usagesByType.getOrPut(typeName) { mutableListOf() }
                        .add(UsageEntry(refPath, refLine, refCol, context, typeName))
                    totalCount++

                    true
                })

                if (totalCount == 0) {
                    out.add("No file references found.")
                } else {
                    out.add("Found $totalCount reference(s):\n")
                    for ((typeName, entries) in usagesByType) {
                        out.add("  [$typeName] (${entries.size})")
                        for (e in entries.sortedWith(compareBy({ it.usageFile }, { it.usageLine }))) {
                            out.add("    ${e.usageFile}:${e.usageLine}:${e.usageCol}")
                            out.add("      ${e.usageContext}")
                        }
                        out.add("")
                    }
                }
            }
            out
        }).executeSynchronously()

        for (line in lines) println(line)
    }
}

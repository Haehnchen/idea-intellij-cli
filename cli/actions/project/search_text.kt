// Action: Search for text across the project using IDE's word index
// Usage: intellij-cli action search_text query="someMethod"

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.Processor
import java.util.concurrent.Callable

// --- Configure ---
val query: String? = null  // word or identifier to search for
val limit: Int     = 100   // maximum number of matches
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (query.isNullOrBlank()) {
    println("Error: 'query' must be specified.")
} else {
    val scope = GlobalSearchScope.projectScope(project)
    val helper = PsiSearchHelper.getInstance(project)

    data class Match(val file: String, val line: Int, val context: String)

    val matches = ReadAction.nonBlocking(Callable {
        val result = mutableListOf<Match>()
        helper.processAllFilesWithWord(query, scope, Processor { psiFile ->
            val vFile = psiFile.virtualFile ?: return@Processor true
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@Processor true
            val path = vFile.path.removePrefix(project.basePath ?: "").trimStart('/')
            val text = doc.text
            var idx = 0
            while (idx < text.length && result.size < limit) {
                val found = text.indexOf(query, idx)
                if (found < 0) break
                val lineNum = doc.getLineNumber(found) + 1
                val lineStart = doc.getLineStartOffset(lineNum - 1)
                val lineEnd = doc.getLineEndOffset(lineNum - 1)
                val lineText = doc.getText(TextRange(lineStart, lineEnd)).trim()
                result.add(Match(path, lineNum, lineText))
                idx = found + query.length
            }
            result.size < limit
        }, false)
        result
    }).executeSynchronously()

    if (matches.isEmpty()) {
        println("No results found for '$query'")
    } else {
        println("Found ${matches.size} result(s) for '$query':\n")
        for (m in matches.sortedWith(compareBy({ it.file }, { it.line }))) {
            println("${m.file}:${m.line}")
            println("  ${m.context}")
        }
    }
}

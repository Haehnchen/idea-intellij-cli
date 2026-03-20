// Action: Find all usages of a class by name (language-agnostic)
// Usage: intellij-cli action find_class_usages name=FoobarService

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

// --- Configure ---
val name: String? = null   // class name — required
val limit: Int    = 200    // maximum number of results
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (name.isNullOrBlank()) {
    println("Error: 'name' must be specified (e.g. name=FoobarService).")
} else {
    readAction {
        val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList
        val classElements = mutableListOf<PsiElement>()

        for (contributor in contributors) {
            for (item in contributor.getItemsByName(name, name, project, true)) {
                if (item is PsiElement) {
                    classElements.add(item)
                }
            }
        }

        if (classElements.isEmpty()) {
            println("Error: Class not found: $name")
        } else {
            val projectBase = project.basePath ?: ""
            val docManager = PsiDocumentManager.getInstance(project)

            fun outputUsages(label: String, element: PsiElement) {
                val defDoc = docManager.getDocument(element.containingFile)
                val defVFile = element.containingFile?.virtualFile
                if (defDoc != null && defVFile != null) {
                    val defLine = defDoc.getLineNumber(element.textOffset) + 1
                    val defCol = element.textOffset - defDoc.getLineStartOffset(defLine - 1) + 1
                    val defStart = defDoc.getLineStartOffset(defLine - 1)
                    val defEnd = defDoc.getLineEndOffset(defLine - 1)
                    val defContext = defDoc.getText(TextRange(defStart, defEnd))
                    val defPath = defVFile.path.removePrefix(projectBase).trimStart('/')

                    println("Symbol: '$label'  ($defPath:$defLine:$defCol)")
                    println("  $defContext")
                    println("  ${" ".repeat(defCol - 1)}^")
                    println("=".repeat(60))
                }
                val usages = mutableListOf<Triple<String, Int, String>>()

                ReferencesSearch.search(element).forEach(Processor { ref ->
                    val refElement = ref.element
                    val refVFile = refElement.containingFile?.virtualFile
                    if (refVFile != null) {
                        val refDoc = docManager.getDocument(refElement.containingFile)
                        if (refDoc != null) {
                            val refLine = refDoc.getLineNumber(refElement.textOffset) + 1
                            val start = refDoc.getLineStartOffset(refLine - 1)
                            val end = refDoc.getLineEndOffset(refLine - 1)
                            val context = refDoc.getText(TextRange(start, end)).trim()
                            val refPath = refVFile.path.removePrefix(projectBase).trimStart('/')
                            usages.add(Triple(refPath, refLine, context))
                        }
                    }
                    usages.size < limit
                })

                if (usages.isEmpty()) {
                    println("No usages found.")
                } else {
                    println("Found ${usages.size} usage(s):\n")
                    for ((file, line, context) in usages.sortedWith(compareBy({ it.first }, { it.second }))) {
                        println("$file:$line")
                        println("  $context")
                    }
                }
                println()
            }

            for (classElement in classElements) {
                outputUsages(name, classElement)
            }
        }
    }
}

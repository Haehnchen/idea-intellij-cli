// Action: Find all usages of a class by name (language-agnostic)
// Usage: intellij-cli action find_usages_class name="FoobarService"
// Usage: intellij-cli action find_usages_class name="FoobarService" limit=50
//
// Parameters:
//   name=<string>   class name — required
//   limit=<int>     maximum number of results (default: 200)

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.util.Processor
import java.util.concurrent.Callable

// --- Configure ---
val name: String? = null   // class name — required
val limit: Int    = 200    // maximum number of results
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (name.isNullOrBlank()) {
    println("Error: 'name' must be specified (e.g. name=FoobarService).")
} else {
    val lines = ReadAction.nonBlocking(Callable {
        val out = mutableListOf<String>()
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
            out.add("Error: Class not found: $name")
        } else {
            val projectBase = project.basePath ?: ""
            val docManager = PsiDocumentManager.getInstance(project)
            val usageTypeProviders = UsageTypeProvider.EP_NAME.extensionList

            // Prefer project-defined classes; fall back to all if none found in project
            val projectElements = classElements.filter { it.containingFile?.virtualFile?.path?.startsWith(projectBase) == true }
            val elementsToSearch = if (projectElements.isNotEmpty()) projectElements else classElements

            fun collectUsages(label: String, element: PsiElement) {
                val defVFile = element.containingFile?.virtualFile
                val defDoc = docManager.getDocument(element.containingFile)
                val defPath = defVFile?.path?.removePrefix(projectBase)?.trimStart('/') ?: "?"
                val defLine = if (defDoc != null) defDoc.getLineNumber(element.textOffset) + 1 else 0

                out.add("Class: '$label'")
                out.add("Defined: $defPath:$defLine")
                out.add("")

                data class UsageEntry(
                    val usageFile: String,
                    val usageLine: Int,
                    val usageCol: Int,
                    val usageContext: String
                )

                val usagesByType = LinkedHashMap<String, MutableList<UsageEntry>>()
                var totalCount = 0

                ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).forEach(Processor { ref ->
                    if (totalCount >= limit) return@Processor false

                    val refElement = ref.element
                    val refVFile = refElement.containingFile?.virtualFile ?: return@Processor true
                    val refDoc = docManager.getDocument(refElement.containingFile) ?: return@Processor true

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
                        .add(UsageEntry(refPath, refLine, refCol, context))
                    totalCount++

                    true
                })

                if (totalCount == 0) {
                    out.add("No usages found.")
                } else {
                    out.add("Found $totalCount usage(s):")
                    out.add("")
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

            for (classElement in elementsToSearch) {
                collectUsages(name, classElement)
            }
        }
        out
    }).executeSynchronously()

    for (line in lines) println(line)
}

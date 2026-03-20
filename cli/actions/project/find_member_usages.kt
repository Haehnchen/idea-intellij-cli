// Action: Find all usages of a class member (method or field) by name (language-agnostic)
// Usage: intellij-cli action find_member_usages class_name=FoobarService member=createItem
// Usage: intellij-cli action find_member_usages class_name=FoobarService member=extensions

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

// --- Configure ---
val class_name: String? = null  // class name — required
val member: String? = null      // member name (method or field) — required
val limit: Int    = 200         // maximum number of results
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (class_name.isNullOrBlank()) {
    println("Error: 'class_name' must be specified (e.g. class_name=FoobarService).")
} else if (member.isNullOrBlank()) {
    println("Error: 'member' must be specified (e.g. member=createItem).")
} else {
    readAction {
        val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList
        val classElements = mutableListOf<PsiElement>()

        for (contributor in contributors) {
            for (item in contributor.getItemsByName(class_name, class_name, project, true)) {
                if (item is PsiElement) {
                    classElements.add(item)
                }
            }
        }

        if (classElements.isEmpty()) {
            println("Error: Class not found: $class_name")
        } else {
            val projectBase = project.basePath ?: ""
            val docManager = PsiDocumentManager.getInstance(project)

            for (classElement in classElements) {
                val members = PsiTreeUtil.findChildrenOfType(classElement, PsiNamedElement::class.java)
                    .filter { it.name == member }

                if (members.isEmpty()) {
                    println("Error: '$member' not found in ${classElement.javaClass.simpleName.removeSuffix("Impl")} $class_name")
                } else {
                    val usages = mutableListOf<Triple<String, Int, String>>()

                    for (m in members) {
                        val defDoc = docManager.getDocument(m.containingFile)
                        val defVFile = m.containingFile?.virtualFile
                        if (defDoc != null && defVFile != null) {
                            val defLine = defDoc.getLineNumber(m.textOffset) + 1
                            val defCol = m.textOffset - defDoc.getLineStartOffset(defLine - 1) + 1
                            val defStart = defDoc.getLineStartOffset(defLine - 1)
                            val defEnd = defDoc.getLineEndOffset(defLine - 1)
                            val defContext = defDoc.getText(TextRange(defStart, defEnd))
                            val defPath = defVFile.path.removePrefix(projectBase).trimStart('/')

                            println("Symbol: '$member'  ($defPath:$defLine:$defCol)")
                            println("  $defContext")
                            println("  ${" ".repeat(defCol - 1)}^")
                            println("=".repeat(60))
                        }

                        ReferencesSearch.search(m).forEach(Processor { ref ->
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
                    }

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
            }
        }
    }
}

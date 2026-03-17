// Action: Run code inspections on a directory and return results
// Usage: intellij-cli action inspect_code dir=src/main/kotlin/com/example
//        intellij-cli action inspect_code dir=src/main/kotlin/com/example recursive=true

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager

// --- Configure ---
val dir: String        = "src"  // directory relative to project root
val recursive: Boolean = true   // scan subdirectories recursively
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else {
    val fullPath = "${project.basePath}/$dir"
    val virtualDir = LocalFileSystem.getInstance().findFileByPath(fullPath)

    if (virtualDir == null || !virtualDir.isDirectory) {
        println("Error: Directory not found: $dir")
    } else {
        fun collectFiles(vf: VirtualFile): List<VirtualFile> {
            if (!vf.isDirectory) return if (vf.extension in listOf("kt", "java", "groovy")) listOf(vf) else emptyList()
            val children = vf.children?.toList() ?: emptyList()
            return if (recursive) children.flatMap { collectFiles(it) }
                   else children.filter { !it.isDirectory && it.extension in listOf("kt", "java", "groovy") }
        }

        val files = collectFiles(virtualDir)

        if (files.isEmpty()) {
            println("No Kotlin/Java files found in: $dir")
        } else {
            println("Inspecting ${files.size} file(s) in: $dir${if (recursive) " (recursive)" else ""}")
            println("=".repeat(60))

            val inspectionManager = InspectionManager.getInstance(project)
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile

            data class Issue(val severity: String, val line: Int, val message: String, val tool: String)
            val resultsByFile = mutableMapOf<String, MutableList<Issue>>()

            readAction {
                val psiManager = PsiManager.getInstance(project)

                for (vf in files) {
                    val psiFile = psiManager.findFile(vf) ?: continue
                    val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: continue
                    val relativePath = vf.path.removePrefix(project.basePath ?: "").trimStart('/')
                    val issues = mutableListOf<Issue>()

                    val globalContext = inspectionManager.createNewGlobalContext()
                    val toolWrappers = profile.getAllEnabledInspectionTools(project)
                    for (wrapper in toolWrappers) {
                        val toolWrapper = wrapper.tool as? LocalInspectionToolWrapper ?: continue

                        // Look up the actual profile severity for this tool+file
                        val key = HighlightDisplayKey.find(wrapper.shortName)
                        val errorLevel = if (key != null) profile.getErrorLevel(key, psiFile) else null

                        // Skip inspections below WARNING level (matches IntelliJ "Inspect Code" default)
                        if (errorLevel != null && errorLevel.severity < HighlightSeverity.WARNING) continue

                        try {
                            val problems = InspectionEngine.runInspectionOnFile(psiFile, toolWrapper, globalContext)
                            for (descriptor in problems) {
                                val element = descriptor.psiElement ?: continue
                                if (element.textOffset < 0 || element.textOffset >= document.textLength) continue
                                val line = document.getLineNumber(element.textOffset) + 1
                                val severity = when (descriptor.highlightType) {
                                    ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> "Error"
                                    ProblemHighlightType.WARNING -> "Warning"
                                    ProblemHighlightType.WEAK_WARNING -> "Weak warning"
                                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING ->
                                        errorLevel?.severity?.displayName ?: "Warning"
                                    else -> errorLevel?.severity?.displayName ?: "Warning"
                                }
                                issues.add(Issue(severity, line, descriptor.descriptionTemplate, wrapper.shortName))
                            }
                        } catch (_: Exception) {}
                    }

                    if (issues.isNotEmpty()) {
                        resultsByFile[relativePath] = issues.distinctBy { "${it.line}:${it.message}" }.toMutableList()
                    }
                }
            }

            if (resultsByFile.isEmpty()) {
                println("No issues found.")
            } else {
                var total = 0
                for ((filePath, issues) in resultsByFile.toSortedMap()) {
                    println("\n$filePath (${issues.size} issue(s)):")
                    for (issue in issues.sortedBy { it.line }) {
                        println("  [${issue.severity}] line ${issue.line}: ${issue.message}")
                    }
                    total += issues.size
                }
                println("\nTotal: $total issue(s) in ${resultsByFile.size}/${files.size} file(s)")
            }
        }
    }
}

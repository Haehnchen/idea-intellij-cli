// Action: Inspect Code - run code inspections (Code Analysis) to find errors, warnings, and problems (max 50 files for directories)
// Usage: intellij-cli action get_file_problems path=src/Foo.kt
//        intellij-cli action get_file_problems path=src/main/kotlin recursive=false
//        intellij-cli action get_file_problems path=src/Foo.kt inspection=UnusedDeclaration
//        intellij-cli action get_file_problems path=src/Foo.kt inspection=UnusedDeclaration,PhpUnused
//        intellij-cli action get_file_problems path=src/Foo.kt errorsOnly=true

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager

// --- Configure ---
val path: String = ""            // file or directory path relative to project root
val recursive: Boolean = true    // scan subdirectories recursively
val errorsOnly: Boolean = false  // true = only errors, false = errors + warnings
val inspection: String = ""      // optional: comma-separated inspection IDs (e.g., "UnusedDeclaration,PhpUnused")
// -----------------

fun jsonEscape(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

fun defaultCodeInsightContext(): Any {
    val contextsClass = Class.forName("com.intellij.codeInsight.multiverse.CodeInsightContexts")
    return contextsClass.getMethod("defaultContext").invoke(null)
        ?: error("CodeInsightContexts.defaultContext() returned null")
}

fun runInsideHighlightingSessionCompat(
    psiFile: com.intellij.psi.PsiFile,
    range: ProperTextRange,
    action: (HighlightingSessionImpl) -> Unit
) {
    val context = defaultCodeInsightContext()
    val method = HighlightingSessionImpl::class.java.methods.firstOrNull { candidate ->
        candidate.name == "runInsideHighlightingSession" && candidate.parameterCount == 6
    } ?: error("HighlightingSessionImpl.runInsideHighlightingSession() not found")

    method.invoke(
        null,
        psiFile,
        context,
        null,
        range,
        false,
        java.util.function.Consumer<Any> { session ->
            action(session as HighlightingSessionImpl)
        }
    )
}

fun buildProblemJson(
    severity: String,
    description: String,
    lineContent: String,
    line: Int,
    column: Int,
    inspectionId: String = ""
): String = buildString {
    append("{")
    append("\"severity\":\"${jsonEscape(severity)}\",")
    append("\"description\":\"${jsonEscape(description)}\",")
    append("\"lineContent\":\"${jsonEscape(lineContent)}\",")
    append("\"line\":$line,")
    append("\"column\":$column")
    if (inspectionId.isNotEmpty()) {
        append(",\"inspection\":\"${jsonEscape(inspectionId)}\"")
    }
    append("}")
}

// Run a specific inspection on the file
fun runSpecificInspection(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    inspectionId: String
): List<String> {
    val problems = mutableListOf<String>()
    val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
    val inspectionManager = InspectionManager.getInstance(project)

    val enabledTools = profile.getAllEnabledInspectionTools(project)
    val toolEntry = enabledTools.find { it.shortName.equals(inspectionId, ignoreCase = true) }

    if (toolEntry == null) return problems

    val localWrapper = toolEntry.tool as? LocalInspectionToolWrapper
    if (localWrapper == null) return problems

    val globalContext = inspectionManager.createNewGlobalContext()
    val descriptors = InspectionEngine.runInspectionOnFile(psiFile, localWrapper, globalContext)

    for (descriptor in descriptors) {
        val element = descriptor.psiElement ?: continue
        if (element.textOffset < 0 || element.textOffset >= document.textLength) continue

        val startLine = document.getLineNumber(element.textOffset)
        val lineStartOffset = document.getLineStartOffset(startLine)
        val lineContent = document.getText(TextRange(lineStartOffset, document.getLineEndOffset(startLine)))
        val column = element.textOffset - lineStartOffset

        val severity = when (descriptor.highlightType) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> "ERROR"
            ProblemHighlightType.WARNING -> "WARNING"
            ProblemHighlightType.WEAK_WARNING -> "WEAK_WARNING"
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
            else -> "INFORMATION"
        }

        if (errorsOnly && severity != "ERROR") continue

        problems.add(buildProblemJson(
            severity,
            descriptor.descriptionTemplate ?: "",
            lineContent,
            startLine + 1,
            column + 1,
            inspectionId
        ))
    }

    return problems
}

// Run all inspections on a file (default behavior)
fun runAllInspections(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document
): List<String> {
    val problems = mutableListOf<String>()
    val minSeverity = if (errorsOnly) HighlightSeverity.ERROR else HighlightSeverity.WEAK_WARNING
    val daemonIndicator = DaemonProgressIndicator()
    val range = ProperTextRange(0, document.textLength)

    ProgressManager.getInstance().runProcess(Runnable {
        runInsideHighlightingSessionCompat(psiFile, range) { session ->
            session.setMinimumSeverity(minSeverity)
            val highlights = (DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl)
                .runMainPasses(psiFile, document, daemonIndicator)

            for (info in highlights) {
                if (info.severity.myVal >= minSeverity.myVal) {
                    val startLine = document.getLineNumber(info.startOffset)
                    val lineStartOffset = document.getLineStartOffset(startLine)
                    val lineContent = document.getText(
                        TextRange(lineStartOffset, document.getLineEndOffset(startLine))
                    )
                    val column = info.startOffset - lineStartOffset

                    problems.add(buildProblemJson(
                        info.severity.name,
                        info.description ?: "",
                        lineContent,
                        startLine + 1,
                        column + 1
                    ))
                }
            }
        }
    }, daemonIndicator)

    return problems
}

// Collect files from directory (stops at 50 files)
fun collectFiles(vf: VirtualFile): List<VirtualFile> {
    val result = mutableListOf<VirtualFile>()

    fun collect(v: VirtualFile) {
        if (result.size > 50) return  // Stop early if we hit limit
        if (!v.isDirectory) {
            result.add(v)
        } else if (recursive) {
            v.children?.forEach { collect(it) }
        } else {
            v.children?.filter { !it.isDirectory }?.forEach { result.add(it) }
        }
    }

    collect(vf)
    return result
}

if (path.isEmpty()) {
    println("""{"error": "path parameter is required", "path": "", "errors": []}""")
} else if (DumbService.getInstance(project).isDumb) {
    println("""{"error": "IDE is indexing, please wait", "path": "${jsonEscape(path)}", "errors": []}""")
} else {
    val fullPath = "${project.basePath}/$path"
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(java.nio.file.Paths.get(fullPath))

    if (virtualFile == null || !virtualFile.isValid) {
        println("""{"error": "Path not found: $path", "path": "${jsonEscape(path)}", "errors": []}""")
    } else if (virtualFile.isDirectory) {
        // Handle directory
        val files = collectFiles(virtualFile)
        if (files.isEmpty()) {
            println("""{"error": "No files found in directory", "path": "${jsonEscape(path)}", "errors": [], "fileCount": 0}""")
        } else if (files.size > 50) {
            println("""{"error": "Too many files (max 50), found ${files.size}", "path": "${jsonEscape(path)}", "errors": [], "fileCount": ${files.size}}""")
        } else {
            readAction {
                val psiManager = PsiManager.getInstance(project)
                val allResults = mutableListOf<String>()

                for (vf in files) {
                    val psiFile = psiManager.findFile(vf) ?: continue
                    val document = FileDocumentManager.getInstance().getDocument(vf) ?: continue
                    val relativePath = vf.path.removePrefix(project.basePath ?: "").trimStart('/')

                    val problems = if (inspection.isNotEmpty()) {
                        val inspectionIds = inspection.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val fileProblems = mutableListOf<String>()
                        for (inspId in inspectionIds) {
                            fileProblems.addAll(runSpecificInspection(psiFile, document, inspId))
                        }
                        fileProblems
                    } else {
                        runAllInspections(psiFile, document)
                    }

                    if (problems.isNotEmpty()) {
                        allResults.add("""{"file":"${jsonEscape(relativePath)}","errors":[${problems.joinToString(",")}]}""")
                    }
                }

                println("""{"directory":"${jsonEscape(path)}","recursive":$recursive,"fileCount":${files.size},"files":[${allResults.joinToString(",")}]}""")
            }
        }
    } else {
        // Handle single file
        readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)

            if (psiFile == null || document == null) {
                println("""{"error": "Cannot parse file", "path": "${jsonEscape(path)}", "errors": []}""")
            } else if (inspection.isNotEmpty()) {
                val inspectionIds = inspection.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val allProblems = mutableListOf<String>()

                for (inspId in inspectionIds) {
                    val problems = runSpecificInspection(psiFile, document, inspId)
                    allProblems.addAll(problems)
                }

                println("""{"path":"${jsonEscape(path)}","inspections":"${jsonEscape(inspection)}","errors":[${allProblems.joinToString(",")}]}""")
            } else {
                val problems = runAllInspections(psiFile, document)
                println("""{"path":"${jsonEscape(path)}","errors":[${problems.joinToString(",")}]}""")
            }
        }
    }
}

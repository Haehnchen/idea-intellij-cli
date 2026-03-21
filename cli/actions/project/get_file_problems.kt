// Action: Inspect Code - run code inspections to find errors and warnings
// Usage: intellij-cli action get_file_problems path="src/Foo.kt"
//        intellij-cli action get_file_problems path="src/main/kotlin" recursive=true
//        intellij-cli action get_file_problems path="src/**/*.kt"
//        intellij-cli action get_file_problems path="src/Foo.kt" errorsOnly=true
//        intellij-cli action get_file_problems path="src/Foo.kt" inspection="UnusedDeclaration"
//
// Output format (one problem per line, grouped by file):
//   <file>
//     <line>:<col> [<SEVERITY>] (<inspectionId>) <description>
//       > <source line>
//
//   Severity: ERROR | WARNING | WEAK_WARNING | INFO
//   inspectionId: inspection rule name (e.g. UnusedDeclaration, PhpUnused) — empty for syntax/parser errors
//   Use inspectionId with inspection= parameter to re-run or filter a specific rule.

import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import java.nio.file.FileSystems
import java.nio.file.Files
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager
import java.util.concurrent.Callable

// --- Configure ---
val path: String = ""         // file path, directory, or glob pattern (e.g. "src/Foo.kt", "src/main/kotlin", "src/**/*.kt")
val recursive: Boolean = true // recurse into subdirectories when path is a directory
val errorsOnly: Boolean = false // only report ERROR severity, skip warnings
val inspection: String = ""   // run a specific inspection by id, e.g. "UnusedDeclaration" (comma-separated for multiple)
// -----------------

data class Problem(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: String,
    val description: String,
    val lineContent: String,
    val inspectionId: String = ""
)

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

    method.invoke(null, psiFile, context, null, range, false,
        java.util.function.Consumer<Any> { session -> action(session as HighlightingSessionImpl) })
}

fun runSpecificInspection(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    inspectionId: String,
    relativePath: String
): List<Problem> {
    val problems = mutableListOf<Problem>()
    val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
    val inspectionManager = InspectionManager.getInstance(project)

    val toolEntry = profile.getAllEnabledInspectionTools(project)
        .find { it.shortName.equals(inspectionId, ignoreCase = true) } ?: return problems
    val localWrapper = toolEntry.tool as? LocalInspectionToolWrapper ?: return problems

    val descriptors = InspectionEngine.runInspectionOnFile(psiFile, localWrapper, inspectionManager.createNewGlobalContext())
    for (descriptor in descriptors) {
        val element = descriptor.psiElement ?: continue
        if (element.textOffset < 0 || element.textOffset >= document.textLength) continue

        val startLine = document.getLineNumber(element.textOffset)
        val lineStartOffset = document.getLineStartOffset(startLine)
        val lineContent = document.getText(TextRange(lineStartOffset, document.getLineEndOffset(startLine)))
        val column = element.textOffset - lineStartOffset

        val severity = when (descriptor.highlightType) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> "ERROR"
            ProblemHighlightType.WARNING, ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
            ProblemHighlightType.WEAK_WARNING -> "WEAK_WARNING"
            else -> "INFO"
        }

        if (errorsOnly && severity != "ERROR") continue
        problems.add(Problem(relativePath, startLine + 1, column + 1, severity, descriptor.descriptionTemplate ?: "", lineContent, inspectionId))
    }
    return problems
}

fun runAllInspections(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    relativePath: String
): List<Problem> {
    val problems = mutableListOf<Problem>()
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
                    val lineContent = document.getText(TextRange(lineStartOffset, document.getLineEndOffset(startLine)))
                    val column = info.startOffset - lineStartOffset
                    // inspectionToolId is available on HighlightInfo via toolId field (may be null for daemon passes)
                    val inspId = try { info.javaClass.getMethod("getInspectionToolId").invoke(info) as? String ?: "" } catch (_: Exception) { "" }
                    problems.add(Problem(relativePath, startLine + 1, column + 1, info.severity.name, info.description ?: "", lineContent, inspId))
                }
            }
        }
    }, daemonIndicator)

    return problems
}

fun collectFiles(vf: VirtualFile): List<VirtualFile> {
    val result = mutableListOf<VirtualFile>()
    fun collect(v: VirtualFile) {
        if (result.size >= 50) return
        if (!v.isDirectory) result.add(v)
        else if (recursive) v.children?.forEach { collect(it) }
        else v.children?.filter { !it.isDirectory }?.forEach { result.add(it) }
    }
    collect(vf)
    return result
}

fun collectGlobFiles(glob: String): List<VirtualFile> {
    val root = java.nio.file.Paths.get(project.basePath ?: return emptyList())
    val matcher = FileSystems.getDefault().getPathMatcher("glob:${root}/$glob")
    val matched = mutableListOf<VirtualFile>()
    Files.walk(root).use { stream ->
        stream.filter { !Files.isDirectory(it) && matcher.matches(it) }.forEach { path ->
            if (matched.size < 50) {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { matched.add(it) }
            }
        }
    }
    return matched
}

fun printProblems(problems: List<Problem>) {
    if (problems.isEmpty()) {
        println("No problems found.")
        return
    }

    val byFile = problems.groupBy { it.file }
    for ((file, fileProblems) in byFile) {
        println(file)
        for (p in fileProblems.sortedWith(compareBy({ it.line }, { it.column }))) {
            val inspPart = if (p.inspectionId.isNotEmpty()) " (${p.inspectionId})" else ""
            println("  ${p.line}:${p.column} [${p.severity}]$inspPart ${p.description}")
            val trimmed = p.lineContent.trim()
            if (trimmed.isNotEmpty()) println("    > $trimmed")
        }
    }

    val errorCount = problems.count { it.severity == "ERROR" }
    val warnCount = problems.count { it.severity.contains("WARN") }
    val otherCount = problems.size - errorCount - warnCount
    println("---")
    println("${problems.size} problem(s): $errorCount error(s), $warnCount warning(s), $otherCount other")
}

// Sync VFS and PSI before inspecting to avoid stale results from external file changes
val projectRoot = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
if (projectRoot != null) {
    VfsUtil.markDirtyAndRefresh(false, true, true, projectRoot)
}
@Suppress("DEPRECATION")
TransactionGuard.getInstance().submitTransactionAndWait {
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

fun inspectFiles(files: List<VirtualFile>): List<Problem> {
    val psiManager = PsiManager.getInstance(project)
    val allProblems = mutableListOf<Problem>()
    for (vf in files) {
        val relativePath = vf.path.removePrefix(project.basePath ?: "").trimStart('/')
        val fileProblems = ReadAction.nonBlocking(Callable {
            val psiFile = psiManager.findFile(vf) ?: return@Callable emptyList<Problem>()
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@Callable emptyList<Problem>()
            if (inspection.isNotEmpty()) {
                inspection.split(",").flatMap { runSpecificInspection(psiFile, document, it.trim(), relativePath) }
            } else {
                runAllInspections(psiFile, document, relativePath)
            }
        }).inSmartMode(project).executeSynchronously()
        allProblems.addAll(fileProblems)
    }
    return allProblems
}

if (path.isEmpty()) {
    println("Error: path parameter is required")
} else if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is indexing, please wait")
} else {
    val isGlob = path.contains('*') || path.contains('?') || path.contains('[')

    if (isGlob) {
        val files = collectGlobFiles(path)
        when {
            files.isEmpty() -> println("No files matched glob: $path")
            files.size > 50 -> println("Error: Too many files (${files.size}), max 50")
            else -> printProblems(inspectFiles(files))
        }
    } else {
        val fullPath = "${project.basePath}/$path"
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(java.nio.file.Paths.get(fullPath))

        if (virtualFile == null || !virtualFile.isValid) {
            println("Error: Path not found: $path")
        } else if (virtualFile.isDirectory) {
            val files = collectFiles(virtualFile)
            when {
                files.isEmpty() -> println("No files found in directory: $path")
                files.size > 50 -> println("Error: Too many files (${files.size}), max 50")
                else -> printProblems(inspectFiles(files))
            }
        } else {
            val problems = ReadAction.nonBlocking(Callable {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@Callable null
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@Callable null
                if (inspection.isNotEmpty()) {
                    inspection.split(",").flatMap { runSpecificInspection(psiFile, document, it.trim(), path) }
                } else {
                    runAllInspections(psiFile, document, path)
                }
            }).inSmartMode(project).executeSynchronously()

            if (problems == null) {
                println("Error: Cannot parse file: $path")
            } else {
                printProblems(problems)
            }
        }
    }
}

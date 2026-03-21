// Action: Inspect Code - run code inspections to find errors and warnings
// Usage: intellij-cli action get_file_problems path="src/Foo.kt"
//        intellij-cli action get_file_problems path="src/main/kotlin" recursive=true
//        intellij-cli action get_file_problems path="src/**/*.kt"
//        intellij-cli action get_file_problems path="src/Foo.kt" errorsOnly=true
//        intellij-cli action get_file_problems path="src/Foo.kt" inspection="UnusedDeclaration"
//        intellij-cli action get_file_problems path="src/Foo.kt" inspection="UnusedDeclaration,KotlinRedundantDiagnosticSuppress"
//        intellij-cli action get_file_problems path="src/Foo.kt" context=2
//
// Output format (one problem per line, grouped by file):
//   <file>
//     <line>:<col> [<SEVERITY>] (<inspectionId>) <description>
//         | <context line before>   (only if context > 0)
//       > | <source line>
//         | <context line after>    (only if context > 0)
//
//   Severity: ERROR | WARNING | WEAK_WARNING | INFO
//   inspectionId: inspection rule name (e.g. UnusedDeclaration, PhpUnused)
//   Use inspectionId with inspection= parameter to re-run or filter a specific rule.
//   context: number of surrounding lines to show (0 = problem line only, 2 = ±2 lines = 5 total)

import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import java.nio.file.FileSystems
import java.nio.file.Files
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager

// --- Configure ---
val path: String = ""         // file path, directory, or glob pattern (e.g. "src/Foo.kt", "src/main/kotlin", "src/**/*.kt")
val recursive: Boolean = true // recurse into subdirectories when path is a directory
val errorsOnly: Boolean = false // only report ERROR severity to skip INFO/WEAK_WARNING, or inspection=<id> to filter a specific rule
val inspection: String = ""   // run a specific inspection by id, e.g. "UnusedDeclaration"; comma-separated to run multiple, e.g. "UnusedDeclaration,KotlinRedundantDiagnosticSuppress"
val maxFiles: Int = 500       // maximum number of files to inspect
val context: Int = 0         // surrounding lines to show per problem (0 = problem line only, 2 = ±2 lines = 5 total, max 15)
// -----------------

data class Problem(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: String,
    val description: String,
    val lineContent: String,
    val inspectionId: String = "",
    val contextBefore: List<String> = emptyList(),
    val contextAfter: List<String> = emptyList()
)

fun descriptorSeverity(highlightType: ProblemHighlightType): String = when (highlightType) {
    ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> "ERROR"
    ProblemHighlightType.WARNING, ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
    ProblemHighlightType.WEAK_WARNING -> "WEAK_WARNING"
    else -> "INFO"
}

fun runInspection(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    wrapper: LocalInspectionToolWrapper,
    relativePath: String
): List<Problem> {
    val globalContext = InspectionManager.getInstance(project).createNewGlobalContext()
    return InspectionEngine.runInspectionOnFile(psiFile, wrapper, globalContext).mapNotNull { descriptor ->
        val element = descriptor.psiElement ?: return@mapNotNull null
        if (element.textOffset < 0 || element.textOffset >= document.textLength) return@mapNotNull null
        val severity = descriptorSeverity(descriptor.highlightType)
        if (errorsOnly && severity != "ERROR") return@mapNotNull null
        val startLine = document.getLineNumber(element.textOffset)
        val lineStartOffset = document.getLineStartOffset(startLine)
        val contextBefore = if (context > 0) {
            (maxOf(0, startLine - context) until startLine).map { ln ->
                document.getText(TextRange(document.getLineStartOffset(ln), document.getLineEndOffset(ln)))
            }
        } else emptyList()
        val contextAfter = if (context > 0) {
            (startLine + 1..minOf(document.lineCount - 1, startLine + context)).map { ln ->
                document.getText(TextRange(document.getLineStartOffset(ln), document.getLineEndOffset(ln)))
            }
        } else emptyList()
        Problem(
            file = relativePath,
            line = startLine + 1,
            column = element.textOffset - lineStartOffset + 1,
            severity = severity,
            description = descriptor.descriptionTemplate ?: "",
            lineContent = document.getText(TextRange(lineStartOffset, document.getLineEndOffset(startLine))),
            inspectionId = wrapper.shortName,
            contextBefore = contextBefore,
            contextAfter = contextAfter
        )
    }
}

fun getTools(): List<LocalInspectionToolWrapper> {
    val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
    return if (inspection.isNotEmpty()) {
        inspection.split(",").mapNotNull { id ->
            profile.getAllEnabledInspectionTools(project)
                .find { it.shortName.equals(id.trim(), ignoreCase = true) }
                ?.tool as? LocalInspectionToolWrapper
        }
    } else {
        profile.getAllEnabledInspectionTools(project).mapNotNull { it.tool as? LocalInspectionToolWrapper }
    }
}

fun inspectFiles(files: List<VirtualFile>): List<Problem> {
    val psiManager = PsiManager.getInstance(project)
    val docManager = FileDocumentManager.getInstance()
    val tools = getTools()
    // One ReadAction per inspection — read lock is released between each,
    // so the EDT can acquire write-intent in those gaps without freezing.
    return files.flatMap { vf ->
        val relativePath = vf.path.removePrefix(project.basePath ?: "").trimStart('/')
        tools.flatMap { wrapper ->
            ReadAction.compute<List<Problem>, Exception> {
                val psiFile = psiManager.findFile(vf) ?: return@compute emptyList()
                val document = docManager.getDocument(vf) ?: return@compute emptyList()
                runInspection(psiFile, document, wrapper, relativePath)
            }
        }
    }
}

fun collectFiles(vf: VirtualFile): List<VirtualFile> {
    val result = mutableListOf<VirtualFile>()
    fun collect(v: VirtualFile) {
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
            if (matched.size < maxFiles) {
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
            if (context > 0) {
                p.contextBefore.forEachIndexed { idx, content ->
                    val lineNum = p.line - p.contextBefore.size + idx
                    println("    $lineNum | $content")
                }
            }
            val trimmed = p.lineContent.trim()
            if (trimmed.isNotEmpty()) {
                val display = if (trimmed.length > 180) trimmed.take(180) + "... [truncated]" else trimmed
                if (context > 0) println("    > ${p.line} | $display") else println("    > $display")
            }
            if (context > 0) {
                p.contextAfter.forEachIndexed { idx, content ->
                    val lineNum = p.line + 1 + idx
                    println("    $lineNum | $content")
                }
            }
        }
    }

    val errorCount = problems.count { it.severity == "ERROR" }
    val warnCount = problems.count { it.severity == "WARNING" }
    val weakWarnCount = problems.count { it.severity == "WEAK_WARNING" }
    val infoCount = problems.count { it.severity == "INFO" }
    val otherCount = problems.size - errorCount - warnCount - weakWarnCount - infoCount
    println("---")
    val parts = buildList {
        add("$errorCount error(s)")
        add("$warnCount warning(s)")
        if (weakWarnCount > 0) add("$weakWarnCount weak warning(s)")
        if (infoCount > 0) add("$infoCount info")
        if (otherCount > 0) add("$otherCount other")
    }
    println("${problems.size} problem(s): ${parts.joinToString(", ")}")
    if (weakWarnCount + infoCount > 0) println("hint: use errorsOnly=true to skip INFO/WEAK_WARNING, or inspection=<id> to filter a specific rule")
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

if (context < 0 || context > 15) {
    println("Error: context must be between 0 and 15")
} else if (path.isEmpty()) {
    println("Error: path parameter is required")
} else if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is indexing, please wait")
} else {
    val isGlob = path.contains('*') || path.contains('?') || path.contains('[')
    if (isGlob) {
        val files = collectGlobFiles(path)
        when {
            files.isEmpty() -> println("No files matched glob: $path")
            files.size > maxFiles -> println("Error: Too many files (${files.size}), max $maxFiles. Hints: raise the limit with maxFiles=1000, reduce scope with a subdirectory path, or use a glob to filter by extension e.g. path=\"src/**/*.php\"")
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
                files.size > maxFiles -> println("Error: Too many files (${files.size}), max $maxFiles. Hints: raise the limit with maxFiles=1000, reduce scope with a subdirectory path, or use a glob to filter by extension e.g. path=\"src/**/*.php\"")
                else -> printProblems(inspectFiles(files))
            }
        } else {
            printProblems(inspectFiles(listOf(virtualFile)))
        }
    }
}

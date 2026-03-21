// Action: Force-sync IDE virtual file system and PSI cache with external file changes
// Usage: intellij-cli action sync_files
//        intellij-cli action sync_files path="src/main/kotlin/Foo.kt"
//        intellij-cli action sync_files path="src/main/kotlin"
//        intellij-cli action sync_files path="src/**/*.kt"
//
// Use when files are staled or recently created or modified outside the IDE (e.g., by Claude Code).

import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import java.nio.file.FileSystems
import java.nio.file.Files

// --- Configure ---
val path: String = ""
// -----------------

val basePath = project.basePath ?: error("Project base path not available")

fun collectGlobFiles(glob: String): List<VirtualFile> {
    val root = java.nio.file.Paths.get(basePath)
    val matcher = FileSystems.getDefault().getPathMatcher("glob:${root}/$glob")
    val matched = mutableListOf<VirtualFile>()
    Files.walk(root).use { stream ->
        stream.filter { !Files.isDirectory(it) && matcher.matches(it) }.forEach { p ->
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)?.let { matched.add(it) }
        }
    }
    return matched
}

val isGlob = path.contains('*') || path.contains('?') || path.contains('[')

if (isGlob) {
    val files = collectGlobFiles(path)
    if (files.isEmpty()) {
        println("No files matched glob: $path")
    } else {
        VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray())
        @Suppress("DEPRECATION")
        TransactionGuard.getInstance().submitTransactionAndWait {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        println("Synced: $path (${files.size} file(s))")
        println("VFS refreshed and PSI documents committed.")
    }
} else {
    val targetDir = if (path.isNotEmpty()) {
        val fullPath = if (path.startsWith("/")) path else "$basePath/$path"
        LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: error("Path not found: $path")
    } else {
        LocalFileSystem.getInstance().findFileByPath(basePath)
            ?: error("Project root not found: $basePath")
    }

    // Mark dirty and refresh synchronously (recursive)
    VfsUtil.markDirtyAndRefresh(false, true, true, targetDir)

    @Suppress("DEPRECATION")
    TransactionGuard.getInstance().submitTransactionAndWait {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    val label = if (path.isNotEmpty()) path else "(project root)"
    println("Synced: $label")
    println("VFS refreshed and PSI documents committed.")
}

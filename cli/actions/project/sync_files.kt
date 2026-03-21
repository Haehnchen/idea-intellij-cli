// Action: Force-sync IDE virtual file system and PSI cache with external file changes
// Usage: intellij-cli action sync_files
//        intellij-cli action sync_files path="src/main/kotlin/Foo.kt"
//        intellij-cli action sync_files path="src/main/kotlin"
//
// Use when files are staled or recently created or modified outside the IDE (e.g., by Claude Code).

import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager

// --- Configure ---
val path: String = ""
// -----------------

val basePath = project.basePath ?: error("Project base path not available")

val targetDir = if (path.isNotEmpty()) {
    val fullPath = if (path.startsWith("/")) path else "$basePath/$path"
    LocalFileSystem.getInstance().findFileByPath(fullPath)
        ?: error("Path not found: $path")
} else {
    LocalFileSystem.getInstance().findFileByPath(basePath)
        ?: error("Project root not found: $basePath")
}

// 1. Mark dirty and refresh synchronously (recursive)
VfsUtil.markDirtyAndRefresh(false, true, true, targetDir)

// 2. Commit all open documents to PSI
@Suppress("DEPRECATION")
TransactionGuard.getInstance().submitTransactionAndWait {
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

val label = if (path.isNotEmpty()) path else "(project root)"
println("Synced: $label")
println("VFS refreshed and PSI documents committed.")

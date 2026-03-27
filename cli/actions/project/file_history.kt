// Action: Browse IntelliJ Local History — file edits, VCS updates, refactorings, rollbacks (works without git, for files and directories)
// Usage: intellij-cli action file_history path="src/main/kotlin/Foo.kt"
//        intellij-cli action file_history path="src/main/kotlin/Foo.kt" display=diff limit=5
//        intellij-cli action file_history path="src/main/kotlin/Foo.kt" revision=9498,9495
//        intellij-cli action file_history path="src/main/kotlin/" limit=50
//
// Output formats:
//   csv  (default) — table: timestamp,revision,kind,name,label,affected_paths
//   diff           — markdown with one section per revision, ```diff code blocks showing what lines changed between consecutive revisions

import com.intellij.history.LocalHistory
import com.intellij.openapi.vfs.LocalFileSystem
import java.time.Instant

// --- Configure ---
val path: String = ""       // file path relative to project root, e.g. "src/main/kotlin/Foo.kt"
val offset: Int = 0         // skip first N matching entries
val limit: Int = 25         // maximum number of entries to return
val display: String = "csv" // output format: "csv" for CSV list, "diff" for markdown with diffs
val revision: String = ""   // comma-separated revision IDs to filter to, e.g. "9498,9495"
// -----------------

val wantDiff = display.trim().equals("diff", ignoreCase = true)
val revisionFilter = if (revision.isNotEmpty()) revision.split(",").map { it.trim().toLong() }.toSet() else null

fun makeDiff(oldText: String, newText: String, relPath: String, contextLines: Int = 3): String {
    val oldLines = if (oldText.isEmpty()) emptyList<String>() else oldText.split("\n")
    val newLines = if (newText.isEmpty()) emptyList<String>() else newText.split("\n")
    val m = oldLines.size
    val n = newLines.size

    // LCS DP
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) {
        for (j in 1..n) {
            if (oldLines[i - 1] == newLines[j - 1]) dp[i][j] = dp[i - 1][j - 1] + 1
            else dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
        }
    }

    // Backtrack edit script
    val editKinds = mutableListOf<Char>()
    val editLines = mutableListOf<String>()
    var ii = m
    var jj = n
    while (ii > 0 || jj > 0) {
        if (ii > 0 && jj > 0 && oldLines[ii - 1] == newLines[jj - 1]) {
            editKinds.add(' '); editLines.add(oldLines[ii - 1]); ii--; jj--
        } else if (jj > 0 && (ii == 0 || dp[ii][jj - 1] >= dp[ii - 1][jj])) {
            editKinds.add('+'); editLines.add(newLines[jj - 1]); jj--
        } else {
            editKinds.add('-'); editLines.add(oldLines[ii - 1]); ii--
        }
    }
    // Reverse in-place
    for (k in 0 until editKinds.size / 2) {
        val mir = editKinds.size - 1 - k
        val tc = editKinds[k]; editKinds[k] = editKinds[mir]; editKinds[mir] = tc
        val tl = editLines[k]; editLines[k] = editLines[mir]; editLines[mir] = tl
    }
    val len = editKinds.size

    // Find changed positions
    val changedPos = mutableListOf<Int>()
    for (k in 0 until len) {
        if (editKinds[k] != ' ') changedPos.add(k)
    }
    if (changedPos.isEmpty()) return ""

    // Group into hunks with context
    val ctx = contextLines
    val hunkRanges = mutableListOf<IntArray>()
    var hStart = maxOf(0, changedPos[0] - ctx)
    var hEnd = minOf(len - 1, changedPos[0] + ctx)
    for (ci in 1 until changedPos.size) {
        if (changedPos[ci] - hEnd <= ctx * 2) {
            hEnd = minOf(len - 1, changedPos[ci] + ctx)
        } else {
            hunkRanges.add(intArrayOf(hStart, hEnd))
            hStart = maxOf(0, changedPos[ci] - ctx)
            hEnd = minOf(len - 1, changedPos[ci] + ctx)
        }
    }
    hunkRanges.add(intArrayOf(hStart, hEnd))

    val sb = StringBuilder()
    sb.appendLine("--- a/$relPath")
    sb.appendLine("+++ b/$relPath")

    for (range in hunkRanges) {
        val rs = range[0]
        val re = range[1]
        var oldCount = 0
        var newCount = 0
        var oldBefore = 0
        var newBefore = 0
        for (k in 0 until len) {
            val isCtx = editKinds[k] == ' '
            val isOld = isCtx || editKinds[k] == '-'
            val isNew = isCtx || editKinds[k] == '+'
            if (k < rs) {
                if (isOld) oldBefore++
                if (isNew) newBefore++
            }
            if (k >= rs && k <= re) {
                if (isOld) oldCount++
                if (isNew) newCount++
            }
        }
        sb.appendLine("@@ -${oldBefore + 1},$oldCount +${newBefore + 1},$newCount @@")
        for (k in rs..re) {
            sb.appendLine("${editKinds[k]}${editLines[k]}")
        }
    }
    return sb.toString()
}

// Reflection helpers
fun Any.invokeMethod(name: String, vararg args: Any?): Any? {
    val resolvedTypes = args.map { arg ->
        when (arg) {
            is String -> java.lang.String::class.java
            is Long -> java.lang.Long.TYPE
            is Int -> java.lang.Integer.TYPE
            is Boolean -> java.lang.Boolean.TYPE
            else -> arg?.javaClass ?: java.lang.Object::class.java
        }
    }.toTypedArray()
    return this.javaClass.getMethod(name, *resolvedTypes).invoke(this, *args)
}

fun Any.invokeMethodNoArgs(name: String): Any? {
    return this.javaClass.getMethod(name).invoke(this)
}

// --- Main logic ---

if (path.isEmpty()) {
    println("Error: 'path' parameter is required (relative to project root).")
} else {
    val fullPath = "${project.basePath}/$path"
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)

    if (virtualFile == null) {
        println("Error: File not found: $path")
    } else {
        val lh = LocalHistory.getInstance()
        val facade = lh.invokeMethodNoArgs("getFacade")!!
        val allChanges = facade.invokeMethod("getChanges\$intellij_platform_lvcs_impl") as Iterable<*>

        val projectBase = project.basePath ?: ""
        val targetPath = virtualFile.path

        // Single pass: collect entries + diff content simultaneously
        val allEntries = mutableListOf<Any>()
        val revOldContent = linkedMapOf<Long, String>()
        for (change in allChanges) {
            val cs = change!!
            val affectedPaths = cs.invokeMethodNoArgs("getAffectedPaths") as List<*>
            val affectsFile = affectedPaths.any { it.toString() == targetPath }
            val name0 = cs.invokeMethodNoArgs("getName") as? String
            val lbl = cs.invokeMethodNoArgs("getLabel")
            val isLabel = affectedPaths.isEmpty() && (lbl != null || (name0 != null && name0.isNotEmpty()))

            if (affectsFile || isLabel) {
                if (revisionFilter != null) {
                    val csId = cs.invokeMethodNoArgs("getId") as Long
                    if (csId !in revisionFilter) {
                        continue
                    }
                }
                allEntries.add(cs)
            }

            // Collect diff content in same pass
            if (wantDiff && affectsFile) {
                val csId = cs.invokeMethodNoArgs("getId") as Long
                val innerChanges = cs.invokeMethodNoArgs("getChanges") as List<*>
                for (c in innerChanges) {
                    if (!c!!.javaClass.name.contains("ContentChange")) continue
                    val affectsTarget = c.invokeMethod("affectsPath", targetPath) as Boolean
                    if (affectsTarget) {
                        val oldContent = c.invokeMethodNoArgs("getOldContent")
                        revOldContent[csId] = oldContent?.toString() ?: ""
                    }
                }
            }
        }

        val total = allEntries.size
        val paged = allEntries.drop(offset).take(limit)

        val currentContent = if (wantDiff) {
            try { String(virtualFile.contentsToByteArray(), Charsets.UTF_8) } catch (e: Exception) { "" }
        } else ""

        val revIds = revOldContent.keys.toList()

        if (wantDiff) {
            // Markdown output with sections and diff code blocks
            println("# File History: `$path`\n")

            for (cs in paged) {
                val affectedPaths = cs.invokeMethodNoArgs("getAffectedPaths") as List<*>
                val affectsFile = affectedPaths.any { it.toString() == targetPath }
                val timestamp = cs.invokeMethodNoArgs("getTimestamp") as Long
                val id = cs.invokeMethodNoArgs("getId") as Long
                val name = cs.invokeMethodNoArgs("getName") as? String ?: ""
                val label = (cs.invokeMethodNoArgs("getLabel") as? String ?: "")
                val activityId = cs.invokeMethodNoArgs("getActivityId")
                val kind = activityId?.toString()?.substringAfter("kind=")?.substringBefore(")") ?: ""
                val relPaths = affectedPaths.map { it.toString().removePrefix(projectBase).trimStart('/') }.joinToString("; ")

                val ts = Instant.ofEpochMilli(timestamp).toString()
                val title = when {
                    label.isNotEmpty() -> label
                    name.isNotEmpty() -> name
                    else -> kind.ifEmpty { "Change" }
                }

                println("## $title")
                println("rev=$id | $ts" + if (relPaths.isNotEmpty()) " | $relPaths" else "")
                println()

                // Diff for file-affecting entries that have content
                if (affectsFile) {
                    val idx = revIds.indexOf(id)
                    if (idx >= 0) {
                        val oldText = revOldContent[id]!!
                        val newText = if (idx > 0) {
                            revOldContent[revIds[idx - 1]] ?: currentContent
                        } else {
                            currentContent
                        }
                        val diff = makeDiff(oldText, newText, path)
                        if (diff.isNotEmpty()) {
                            println("```diff")
                            for (line in diff.lines()) {
                                println(line)
                            }
                            println("```")
                        } else {
                            println("_(content unchanged)_")
                        }
                        println()
                    }
                } else {
                    println()
                }
            }
        } else {
            // CSV output
            println("timestamp,revision,kind,name,label,affected_paths")

            for (cs in paged) {
                val affectedPaths = cs.invokeMethodNoArgs("getAffectedPaths") as List<*>
                val affectsFile = affectedPaths.any { it.toString() == targetPath }
                val timestamp = cs.invokeMethodNoArgs("getTimestamp") as Long
                val id = cs.invokeMethodNoArgs("getId") as Long
                val name = cs.invokeMethodNoArgs("getName") as? String ?: ""
                val label = (cs.invokeMethodNoArgs("getLabel") as? String ?: "")
                val activityId = cs.invokeMethodNoArgs("getActivityId")
                val kind = activityId?.toString()?.substringAfter("kind=")?.substringBefore(")") ?: ""
                val relPaths = affectedPaths.map { it.toString().removePrefix(projectBase).trimStart('/') }.joinToString(";")

                println("${Instant.ofEpochMilli(timestamp)},$id,$kind,\"${name.replace("\"", "\"\"")}\",\"${label.replace("\"", "\"\"")}\",\"${relPaths.replace("\"", "\"\"")}\"")
            }
        }

        println("# offset=$offset limit=$limit total=$total")
    }
}

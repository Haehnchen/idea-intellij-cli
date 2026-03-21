// Action: Find files by name using the IDE's "Go to File" engine
// Usage: intellij-cli action find_file name="Foo.kt"
// Uses FILE_EP_NAME contributors — same engine as "Go to File" (Ctrl+Shift+N) / Search Everywhere.

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.project.DumbService

// --- Configure ---
val name: String? = null  // filename or partial name to search for (e.g. "Foo.kt" or "Foo")
val limit: Int    = 50    // maximum number of results
// -----------------

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (name.isNullOrBlank()) {
    println("Error: 'name' must be specified (e.g. name=Foo.kt).")
} else {
    val contributors = ChooseByNameContributor.FILE_EP_NAME.extensionList
    val results = mutableListOf<String>()

    readAction {
        for (contributor in contributors) {
            if (results.size >= limit) break
            val names = contributor.getNames(project, false)
                .filter { it.contains(name, ignoreCase = true) }
            for (fileName in names) {
                if (results.size >= limit) break
                for (item in contributor.getItemsByName(fileName, name, project, false)) {
                    if (results.size >= limit) break
                    val location = item.presentation?.locationString ?: continue
                    val full = "$location/$fileName"
                    results.add(full.removePrefix(project.basePath ?: "").trimStart('/'))
                }
            }
        }
    }

    if (results.isEmpty()) {
        println("No files found matching '$name'")
    } else {
        println("Found ${results.size} result(s) for '$name':\n")
        results.sorted().forEach { println("  $it") }
    }
}

// Action: Find classes and interfaces by name with camelCase matching
// Usage: intellij-cli action find_class query=MyClass
// Uses GotoClassContributor — the same engine as "Go to Class" (Ctrl+N) / Search Everywhere.
// Works across all JetBrains IDEs and languages (Java, Kotlin, PHP, Python, etc.)

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.project.DumbService

// --- Configure ---
val query: String? = null  // class/interface name, supports camelCase (e.g. "SC" matches "SomeClass")
val limit: Int     = 50    // maximum number of results
// -----------------

fun buildClassPattern(q: String): Regex {
    if (!q.any { it.isUpperCase() }) {
        return Regex(".*${Regex.escape(q)}.*", RegexOption.IGNORE_CASE)
    }
    val sb = StringBuilder()
    for (i in q.indices) {
        if (q[i].isUpperCase() && i > 0) sb.append("[a-z0-9]*")
        sb.append(Regex.escape(q[i].toString()))
    }
    sb.append("[a-zA-Z0-9]*")
    return Regex(sb.toString())
}

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (query.isNullOrBlank()) {
    println("Error: 'query' must be specified (e.g. query=MyClass or query=SC for SomeClass).")
} else {
    val pattern = buildClassPattern(query)
    val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList

    data class Result(val name: String, val location: String)
    val results = mutableListOf<Result>()

    readAction {
        for (contributor in contributors) {
            if (results.size >= limit) break
            val names = contributor.getNames(project, false)
                .filter { pattern.containsMatchIn(it) }
            for (name in names) {
                if (results.size >= limit) break
                for (item in contributor.getItemsByName(name, query, project, false)) {
                    if (results.size >= limit) break
                    val presentation = item.presentation ?: continue
                    val displayName = presentation.presentableText ?: name
                    val location = presentation.locationString ?: ""
                    results.add(Result(displayName, location))
                }
            }
        }
    }

    if (results.isEmpty()) {
        println("No classes found matching '$query'")
    } else {
        println("Found ${results.size} result(s) for '$query':\n")
        for (r in results.sortedBy { it.name }) {
            println(r.name)
            if (r.location.isNotBlank()) println("  ${r.location}")
        }
    }
}

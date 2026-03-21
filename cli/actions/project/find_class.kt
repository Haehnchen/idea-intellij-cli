// Action: Find classes and interfaces by name with camelCase matching
// Usage: intellij-cli action find_class query="MyClass"
// Usage: intellij-cli action find_class query="SC"     (camelCase: matches SomeClass)
// NOTE: Always quote the query value with double quotes.
// Uses GotoClassContributor — the same engine as "Go to Class" (Ctrl+N) / Search Everywhere.
// Works across all JetBrains IDEs and languages (Java, Kotlin, PHP, Python, etc.)

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.project.DumbService

// --- Configure ---
val query: String? = null  // always quote: query="ClassName" or camelCase abbreviation query="SC"
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
    println("Error: 'query' must be specified (e.g. query=\"MyClass\" or query=\"SC\" for SomeClass).")
} else {
    val pattern = buildClassPattern(query)
    val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList

    data class Result(val name: String, val namespace: String)
    val seen = linkedSetOf<Result>()

    readAction {
        for (contributor in contributors) {
            if (seen.size >= limit) break
            val names = contributor.getNames(project, false)
                .filter { pattern.containsMatchIn(it) }
            for (name in names) {
                if (seen.size >= limit) break
                for (item in contributor.getItemsByName(name, query, project, false)) {
                    if (seen.size >= limit) break
                    val presentation = item.presentation ?: continue
                    val displayName = presentation.presentableText ?: name
                    val namespace = presentation.locationString ?: ""
                    seen.add(Result(displayName, namespace))
                }
            }
        }
    }

    if (seen.isEmpty()) {
        println("No classes found matching '$query'")
    } else {
        val sorted = seen.sortedWith(compareBy({ it.name }, { it.namespace }))
        println("Found ${sorted.size} result(s) for '$query':\n")
        for (r in sorted) {
            if (r.namespace.isNotBlank()) {
                println("${r.name}  [${r.namespace}]")
            } else {
                println(r.name)
            }
        }
    }
}

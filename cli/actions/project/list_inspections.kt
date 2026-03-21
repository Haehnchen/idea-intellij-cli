// Action: List all available code inspections as CSV
// Usage: intellij-cli action list_inspections
//        intellij-cli action list_inspections filter="unused"
//        intellij-cli action list_inspections filter="unused,css,kotlin"

import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import java.util.concurrent.Callable

// --- Configure ---
val filter: String = ""          // optional comma-separated filters (matches id/displayName/category/language)
// -----------------

// CSV escaping: quote fields containing commas, quotes, or newlines; double internal quotes
fun csvEscape(s: String): String {
    val needsQuoting = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
    return if (needsQuoting) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else {
        s
    }
}

val profile = InspectionProjectProfileManager.getInstance(project).currentProfile

data class InspectionInfo(
    val id: String,
    val displayName: String,
    val category: String,
    val language: String
)

val allInspections = ReadAction.nonBlocking(Callable {
    val result = mutableListOf<InspectionInfo>()
    val enabledTools = profile.getAllEnabledInspectionTools(project)

    for (wrapper in enabledTools) {
        val shortName = wrapper.shortName
        val toolWrapper = wrapper.tool as? InspectionToolWrapper<*, *>
        val displayName = toolWrapper?.displayName ?: shortName

        val category = toolWrapper?.let { tw ->
            try {
                tw.groupDisplayName ?: ""
            } catch (_: Exception) {
                ""
            }
        } ?: ""

        // Get language from tool class name (e.g., KotlinUnusedImport -> Kotlin)
        val language = try {
            val tool = toolWrapper?.tool
            val className = tool?.let { it::class.java.simpleName } ?: ""
            if (className.isEmpty()) "" else className
        } catch (_: Exception) {
            ""
        }

        result.add(InspectionInfo(
            id = shortName,
            displayName = displayName,
            category = category,
            language = language
        ))
    }
    result
}).executeSynchronously()

// Parse comma-separated filters
val filterTerms = if (filter.isNotEmpty()) {
    filter.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
} else {
    emptyList()
}

// Apply filter - match if ANY filter term matches ANY field
val filtered = if (filterTerms.isNotEmpty()) {
    allInspections.filter { insp ->
        filterTerms.any { term ->
            insp.id.lowercase().contains(term) ||
            insp.displayName.lowercase().contains(term) ||
            insp.category.lowercase().contains(term) ||
            insp.language.lowercase().contains(term)
        }
    }
} else {
    allInspections
}

// Sort by category then id
val sorted = filtered.sortedWith(compareBy({ it.category }, { it.id }))

// CSV output
println("id,displayName,category,language")
for (insp in sorted) {
    println(buildString {
        append(csvEscape(insp.id))
        append(",")
        append(csvEscape(insp.displayName))
        append(",")
        append(csvEscape(insp.category))
        append(",")
        append(csvEscape(insp.language))
    })
}

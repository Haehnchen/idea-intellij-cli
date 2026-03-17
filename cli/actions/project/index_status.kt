// Action: Check IDE indexing status
// Usage: intellij-cli action index_status

import com.intellij.openapi.project.DumbService

val dumbService = DumbService.getInstance(project)
val isIndexing = dumbService.isDumb

println("Index Status: ${project.name}")
println("=".repeat(40))
println("isDumbMode : $isIndexing")
println("isIndexing : $isIndexing")
println()
if (isIndexing) {
    println("WARNING: IDE is currently indexing.")
    println("Code intelligence tools (find usages, diagnostics) are unavailable.")
    println("Wait for indexing to complete and retry.")
} else {
    println("OK: IDE is ready. Full code intelligence available.")
}

// Action: Show hierarchical structure of a source file (classes, methods, fields)
// Usage: intellij-cli action file_structure file="src/main/kotlin/Foo.kt"

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager

// --- Configure ---
val file: String? = null  // path relative to project root, e.g. "src/main/kotlin/Foo.kt" — required
// -----------------

// ─── Language-agnostic helpers ────────────────────────────────────────────────

fun iconPath(element: StructureViewTreeElement): String {
    val iconStr = element.presentation.getIcon(false)?.toString() ?: return ""
    return Regex("path=([\\w/.-]+\\.svg)").find(iconStr)?.groupValues?.get(1) ?: ""
}

fun elementKind(path: String): String = when {
    "constructor" in path                                            -> "constructor"
    "method"      in path                                            -> "method"
    "function"    in path                                            -> "function"
    "property"    in path || "access" in path || "parameter" in path -> "property"
    "field"       in path                                            -> "field"
    "constant"    in path || "const" in path                        -> "const"
    "interface"   in path                                            -> "interface"
    "trait"       in path                                            -> "trait"
    "enum"        in path                                            -> "enum"
    "class"       in path                                            -> "class"
    else -> ""
}

fun visibilityFromIcon(path: String): String = when {
    "Private"   in path -> "private"
    "Protected" in path -> "protected"
    "Public"    in path -> "public"
    else -> ""
}

fun elementModifiers(element: StructureViewTreeElement, iconPath: String): String {
    val iconVisibility = visibilityFromIcon(iconPath)
    val value = element.value ?: return iconVisibility
    return try {
        val modifier = value.javaClass.getMethod("getModifier").invoke(value) ?: return iconVisibility
        val mc = modifier.javaClass
        fun bool(name: String) = mc.getMethod(name).invoke(modifier) as? Boolean ?: false
        val access = when {
            bool("isPrivate")   -> "private"
            bool("isProtected") -> "protected"
            else                -> "public"
        }
        val tags = mutableListOf(access)
        if (bool("isStatic"))   tags.add("static")
        if (bool("isAbstract")) tags.add("abstract")
        val isReadonly = runCatching { bool("isReadonly") || bool("isReadOnly") }.getOrDefault(false)
            || runCatching { value.javaClass.getMethod("isReadonly").invoke(value) as? Boolean ?: false }.getOrDefault(false)
        if (isReadonly) tags.add("readonly")
        tags.joinToString(" ")
    } catch (_: Exception) { iconVisibility }
}

// Replace the return type in "methodName(params): ReturnType" with the FQN version.
// Only substitutes when the FQN is a richer form of the same leaf types (e.g. Foo → \A\B\Foo).
fun withFqnReturnType(name: String, fqnType: String?): String {
    if (fqnType.isNullOrBlank()) return name
    val sep = "): "
    val idx = name.lastIndexOf(sep)
    if (idx < 0) return name
    val shortType = name.substring(idx + sep.length)
    val fqnLeaves   = fqnType.split("|", "?").map { it.trimStart('\\').substringAfterLast('\\') }
    val shortLeaves = shortType.split("|", "?").map { it.trimStart('\\').substringAfterLast('\\') }
    return if (fqnLeaves == shortLeaves && fqnType != shortType)
        name.substring(0, idx + sep.length) + fqnType
    else name
}

// ─── Language enricher abstraction ───────────────────────────────────────────

abstract class LanguageEnricher {
    /** Called once per element — use to lazily initialise any language-specific index. */
    open fun init(classLoader: ClassLoader) {}

    /** Return a richer display name (e.g. expand short return types to FQN). */
    open fun enrichName(element: StructureViewTreeElement, rawName: String): String = rawName

    /** Return a richer location string (e.g. expand ↑ClassName to ↑\Full\FQN). */
    open fun enrichLocation(location: String): String = location
}

// ─── PHP enricher ─────────────────────────────────────────────────────────────

class PhpEnricher : LanguageEnricher() {
    private var phpIndex: Any? = null
    private var phpIndexClass: Class<*>? = null

    override fun init(classLoader: ClassLoader) {
        if (phpIndex != null) return
        runCatching {
            phpIndexClass = classLoader.loadClass("com.jetbrains.php.PhpIndex")
            phpIndex = phpIndexClass!!
                .getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
                .invoke(null, project)
        }
    }

    /** Resolve a short PHP class/interface/trait name to its fully-qualified name. */
    private fun resolveFqn(shortName: String): String? {
        val index      = phpIndex      ?: return null
        val indexClass = phpIndexClass ?: return null
        return runCatching {
            for (method in listOf("getClassesByName", "getInterfacesByName", "getTraitsByName")) {
                val results = indexClass.getMethod(method, String::class.java).invoke(index, shortName) as? Collection<*>
                val fqn = results?.firstOrNull()?.let { it.javaClass.getMethod("getFQN").invoke(it) as? String }
                if (!fqn.isNullOrBlank()) return fqn
            }
            null
        }.getOrNull()
    }

    /** getDeclaredType() on PHP PSI elements returns the FQN return/field type. */
    private fun declaredTypeFqn(element: StructureViewTreeElement): String? {
        val value = element.value ?: return null
        return runCatching {
            val result = value.javaClass.getMethod("getDeclaredType").invoke(value)?.toString()
            if (result.isNullOrBlank()) null else result
        }.getOrNull()
    }

    override fun enrichName(element: StructureViewTreeElement, rawName: String): String =
        withFqnReturnType(rawName, declaredTypeFqn(element))

    override fun enrichLocation(location: String): String {
        if (!location.startsWith("↑")) return location
        val shortName = location.removePrefix("↑").trim()
        return "↑${resolveFqn(shortName) ?: return location}"
    }
}

// ─── Default (no-op) enricher ─────────────────────────────────────────────────

class DefaultEnricher : LanguageEnricher()

// ─── Enricher registry ────────────────────────────────────────────────────────

fun enricherFor(language: String): LanguageEnricher = when {
    language.equals("PHP", ignoreCase = true) -> PhpEnricher()
    // Add: language.equals("Java", ...) -> JavaEnricher()
    // Add: language.equals("Kotlin", ...) -> KotlinEnricher()
    else -> DefaultEnricher()
}

// ─── Main ─────────────────────────────────────────────────────────────────────

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (file == null) {
    println("Error: 'file' must be specified (relative to project root).")
} else {
    val fullPath = "${project.basePath}/$file"
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)

    if (virtualFile == null) {
        println("Error: File not found: $file")
    } else {
        readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile == null) {
                println("Error: Could not parse file: $file")
            } else {
                @Suppress("DEPRECATION")
                val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
                if (builder == null || builder !is TreeBasedStructureViewBuilder) {
                    println("Error: No structure view available for language: ${psiFile.language.displayName}")
                } else {
                    val model   = builder.createStructureViewModel(null)
                    val enricher = enricherFor(psiFile.language.displayName)
                    try {
                        println("Structure: $file  [${psiFile.language.displayName}]")
                        println("=".repeat(60))

                        fun printNode(element: StructureViewTreeElement, indent: String = "") {
                            val presentation: ItemPresentation = element.presentation
                            val rawName = presentation.presentableText ?: return

                            element.value?.javaClass?.classLoader?.let { enricher.init(it) }

                            val name     = enricher.enrichName(element, rawName)
                            val rawLoc   = presentation.locationString ?: ""
                            val location = if (rawLoc.isNotEmpty()) " : ${enricher.enrichLocation(rawLoc)}" else ""

                            val path      = iconPath(element)
                            val kind      = elementKind(path)
                            val modifiers = elementModifiers(element, path)

                            val prefix = buildString {
                                if (kind.isNotEmpty())      append("[$kind] ")
                                if (modifiers.isNotEmpty()) append("($modifiers) ")
                            }

                            println("$indent$prefix$name$location")
                            for (child in element.children) {
                                if (child is StructureViewTreeElement) {
                                    printNode(child, "$indent  ")
                                }
                            }
                        }

                        val topLevel = model.root.children
                        if (topLevel.isEmpty()) {
                            println("(no structure found)")
                        } else {
                            for (child in topLevel) {
                                if (child is StructureViewTreeElement) printNode(child)
                            }
                        }
                    } finally {
                        model.dispose()
                    }
                }
            }
        }
    }
}

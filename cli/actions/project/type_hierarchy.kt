// Action: Show type hierarchy (supertypes and subtypes) for a class/interface at a given position
// Usage: intellij-cli action type_hierarchy file=src/Foo.kt line=10 column=5
// Language-independent: works for Java, Kotlin, PHP, Python, etc.
// Supertypes via reflection (getSupers/getSuperClass/getImplementedInterfaces).
// Subtypes via DefinitionsScopedSearch — the platform-level implementation search.

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch

// --- Configure ---
val file: String? = null  // path relative to project root, e.g. "src/main/kotlin/Foo.kt" — required
val line: Int     = 0     // 1-based line number — required
val column: Int   = 0     // 1-based column number (0 = start of line)
// -----------------

fun relPath(path: String) = path.removePrefix(project.basePath ?: "").trimStart('/')

fun elementName(el: Any): String {
    if (el is PsiNamedElement) return el.name ?: "?"
    return runCatching { el.javaClass.getMethod("getName").invoke(el) as? String }.getOrNull() ?: "?"
}

fun elementQName(el: Any): String =
    runCatching { el.javaClass.getMethod("getQualifiedName").invoke(el) as? String }.getOrNull()
        ?: elementName(el)

fun elementKind(el: Any): String {
    if (runCatching { el.javaClass.getMethod("isInterface").invoke(el) as? Boolean }.getOrNull() == true) return "interface"
    if (runCatching { el.javaClass.getMethod("isEnum").invoke(el) as? Boolean }.getOrNull() == true) return "enum"
    if (runCatching { el.javaClass.getMethod("isAnnotationType").invoke(el) as? Boolean }.getOrNull() == true) return "annotation"
    if (runCatching { el.javaClass.getMethod("isTrait").invoke(el) as? Boolean }.getOrNull() == true) return "trait"
    return "class"
}

fun getSupertypes(el: com.intellij.psi.PsiElement): List<com.intellij.psi.PsiElement> {
    // getSupers() — PsiClass (Java, KtLightClass facade)
    runCatching {
        val supers = el.javaClass.getMethod("getSupers").invoke(el) as? Array<*>
        if (supers != null) return supers.filterIsInstance<com.intellij.psi.PsiElement>()
    }
    // toLightClass() — KtClass: convert to Java-compatible PsiClass facade, then retry
    runCatching {
        val light = el.javaClass.getMethod("toLightClass").invoke(el) as? com.intellij.psi.PsiElement
        if (light != null) {
            val supers = light.javaClass.getMethod("getSupers").invoke(light) as? Array<*>
            if (supers != null) return supers.filterIsInstance<com.intellij.psi.PsiElement>()
        }
    }
    val results = mutableListOf<com.intellij.psi.PsiElement>()
    // getSuperTypeListEntries() — Kotlin KtClass
    // Chain: entry -> getTypeReference() -> getTypeElement() -> getReferenceExpression() -> references[0].resolve()
    runCatching {
        val entries = el.javaClass.getMethod("getSuperTypeListEntries").invoke(el) as? List<*>
        entries?.forEach { entry ->
            val typeRef = entry?.javaClass?.getMethod("getTypeReference")?.invoke(entry)
            val typeEl = typeRef?.javaClass?.getMethod("getTypeElement")?.invoke(typeRef)
            val refExpr = typeEl?.javaClass?.getMethod("getReferenceExpression")?.invoke(typeEl)
            val refs = (refExpr?.javaClass?.getMethod("getReferences")?.invoke(refExpr) as? Array<*>)
                ?.filterIsInstance<com.intellij.psi.PsiReference>()
            val resolved = refs?.firstOrNull()?.resolve()
            if (resolved is com.intellij.psi.PsiElement) results.add(resolved)
        }
        if (results.isNotEmpty()) return results
    }
    // getSuperClass() — PHP and others
    runCatching {
        val sc = el.javaClass.getMethod("getSuperClass").invoke(el)
        if (sc is com.intellij.psi.PsiElement) results.add(sc)
    }
    // getImplementedInterfaces() — PHP
    runCatching {
        val ifaces = el.javaClass.getMethod("getImplementedInterfaces").invoke(el) as? Array<*>
        ifaces?.filterIsInstance<com.intellij.psi.PsiElement>()?.let { results.addAll(it) }
    }
    // getMixins() — PHP traits and similar
    runCatching {
        val mixins = el.javaClass.getMethod("getMixins").invoke(el) as? Array<*>
        mixins?.filterIsInstance<com.intellij.psi.PsiElement>()?.let { results.addAll(it) }
    }
    // getSuperClasses() — Python
    runCatching {
        val sc = el.javaClass.getMethod("getSuperClasses").invoke(el) as? Array<*>
        sc?.filterIsInstance<com.intellij.psi.PsiElement>()?.let { results.addAll(it) }
    }
    return results
}

// Resolve a class-like element at the cursor: direct, via reference, or via parent traversal
fun resolveClassElement(element: com.intellij.psi.PsiElement): com.intellij.psi.PsiElement? {
    fun isClassLike(el: com.intellij.psi.PsiElement): Boolean =
        runCatching { el.javaClass.getMethod("getSupers") != null }.getOrDefault(false) ||
        runCatching { el.javaClass.getMethod("getSuperClass") != null }.getOrDefault(false) ||
        runCatching { el.javaClass.getMethod("toLightClass") != null }.getOrDefault(false) ||
        runCatching { el.javaClass.getMethod("getSuperTypeListEntries") != null }.getOrDefault(false) ||
        runCatching { el.javaClass.getMethod("getSuperClasses") != null }.getOrDefault(false)

    // Walk up the tree — cursor is on the class name or inside the class body
    val fromTree = generateSequence(element) { it.parent }.take(6).firstOrNull { isClassLike(it) }
    if (fromTree != null) return fromTree

    // Cursor on a type reference — resolve to declaration
    return generateSequence(element) { it.parent }
        .take(4)
        .flatMap { it.references.asSequence() }
        .mapNotNull { it.resolve() }
        .firstOrNull { isClassLike(it) }
}

fun printSupertypes(el: com.intellij.psi.PsiElement, indent: String = "  ", visited: MutableSet<String> = mutableSetOf()) {
    val key = elementQName(el)
    if (key in visited || key == "java.lang.Object") return
    visited.add(key)
    for (superEl in getSupertypes(el)) {
        val superKey = elementQName(superEl)
        if (superKey == "java.lang.Object") continue
        val vFile = runCatching {
            superEl.javaClass.getMethod("getContainingFile").invoke(superEl)
                ?.let { it.javaClass.getMethod("getVirtualFile").invoke(it) }
                ?.let { it.javaClass.getMethod("getPath").invoke(it) as? String }
        }.getOrNull()
        val loc = if (vFile != null) "  (${relPath(vFile)})" else "  (library)"
        println("$indent${elementKind(superEl)} $superKey$loc")
        printSupertypes(superEl, "$indent  ", visited)
    }
}

if (DumbService.getInstance(project).isDumb) {
    println("Error: IDE is currently indexing. Wait for indexing to complete.")
} else if (file == null) {
    println("Error: 'file' must be specified (relative to project root).")
} else if (line <= 0) {
    println("Error: 'line' must be a positive 1-based line number.")
} else {
    val fullPath = "${project.basePath}/$file"
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)

    if (virtualFile == null) {
        println("Error: File not found: $file")
    } else {
        readAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

            if (psiFile == null || document == null) {
                println("Error: Could not parse file.")
            } else {
                val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
                val offset = document.getLineStartOffset(lineIdx) + (column - 1).coerceAtLeast(0)
                val element = psiFile.findElementAt(offset)

                if (element == null) {
                    println("No element at $file:$line:$column")
                } else {
                    val classEl = resolveClassElement(element)

                    if (classEl == null) {
                        println("No class/interface found at $file:$line:$column")
                        println("Tip: Position the cursor on a class or interface name.")
                    } else {
                        val name = elementQName(classEl)
                        val kind = elementKind(classEl)
                        println("Type hierarchy for $kind $name")
                        println("=".repeat(60))

                        // Supertypes (upward)
                        val supers = getSupertypes(classEl).filter { elementQName(it) != "java.lang.Object" }
                        if (supers.isEmpty()) {
                            println("\nSupertypes: (none)")
                        } else {
                            println("\nSupertypes:")
                            printSupertypes(classEl, "  ")
                        }

                        // Subtypes (downward) — DefinitionsScopedSearch is language-independent
                        println("\nSubtypes:")
                        val scope = GlobalSearchScope.projectScope(project)
                        val subtypes = mutableListOf<String>()
                        DefinitionsScopedSearch.search(classEl, scope).forEach { sub ->
                            val subName = elementQName(sub)
                            val vFile = runCatching {
                                sub.javaClass.getMethod("getContainingFile").invoke(sub)
                                    ?.let { it.javaClass.getMethod("getVirtualFile").invoke(it) }
                                    ?.let { it.javaClass.getMethod("getPath").invoke(it) as? String }
                            }.getOrNull()
                            val loc = if (vFile != null) "  (${relPath(vFile)})" else ""
                            subtypes.add("  ${elementKind(sub)} $subName$loc")
                            subtypes.size < 50
                        }

                        if (subtypes.isEmpty()) {
                            println("  (none in project)")
                        } else {
                            subtypes.forEach { println(it) }
                        }
                    }
                }
            }
        }
    }
}

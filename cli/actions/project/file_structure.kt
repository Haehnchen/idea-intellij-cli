// Action: Show hierarchical structure of a source file (classes, methods, fields)
// Usage: intellij-cli action file_structure file=src/main/kotlin/Foo.kt

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager

// --- Configure ---
val file: String? = null  // path relative to project root, e.g. "src/main/kotlin/Foo.kt" — required
// -----------------

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
                val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
                if (builder == null || builder !is TreeBasedStructureViewBuilder) {
                    println("Error: No structure view available for language: ${psiFile.language.displayName}")
                } else {
                    val model = builder.createStructureViewModel(null)
                    try {
                        println("Structure: $file  [${psiFile.language.displayName}]")
                        println("=".repeat(60))

                        fun printNode(element: com.intellij.ide.structureView.StructureViewTreeElement, indent: String = "") {
                            val presentation: ItemPresentation = element.presentation
                            val name = presentation.presentableText ?: return
                            val location = presentation.locationString?.let { " : $it" } ?: ""
                            println("$indent$name$location")
                            for (child in element.children) {
                                if (child is com.intellij.ide.structureView.StructureViewTreeElement) {
                                    printNode(child, "$indent  ")
                                }
                            }
                        }

                        // Skip the root (it's the file itself), print its children
                        val root = model.root
                        val topLevel = root.children
                        if (topLevel.isEmpty()) {
                            println("(no structure found)")
                        } else {
                            for (child in topLevel) {
                                if (child is com.intellij.ide.structureView.StructureViewTreeElement) {
                                    printNode(child)
                                }
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

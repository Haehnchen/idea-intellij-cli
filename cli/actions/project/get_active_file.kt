// Action: Get currently active file(s) in the editor
// Usage: intellij-cli action get_active_file

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor

val editorManager = FileEditorManager.getInstance(project)
val selectedEditors = editorManager.selectedEditors

if (selectedEditors.isEmpty()) {
    println("No files currently open in editor.")
} else {
    println("Active File(s) in: ${project.name}")
    println("=".repeat(50))

    for (fileEditor in selectedEditors) {
        val virtualFile = fileEditor.file ?: continue
        val relativePath = virtualFile.path.removePrefix(project.basePath ?: "").trimStart('/')

        println("\nFile    : $relativePath")
        println("Language: ${virtualFile.fileType.name}")

        val textEditor = fileEditor as? TextEditor
        val editor = textEditor?.editor
        if (editor != null) {
            readAction {
                val caret = editor.caretModel.primaryCaret
                println("Line    : ${caret.logicalPosition.line + 1}")
                println("Column  : ${caret.logicalPosition.column + 1}")

                val selectionModel = editor.selectionModel
                if (selectionModel.hasSelection()) {
                    val selected = selectionModel.selectedText ?: ""
                    val preview = if (selected.length > 80) selected.take(80) + "..." else selected
                    println("Selected: $preview")
                }
            }
        }
    }
}

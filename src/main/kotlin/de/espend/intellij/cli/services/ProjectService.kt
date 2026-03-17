package de.espend.intellij.cli.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import kotlinx.serialization.Serializable

/**
 * Service for managing and querying IntelliJ projects
 */
class ProjectService {

    /**
     * Information about a project
     */
    @Serializable
    data class ProjectInfo(
        val name: String,
        val basePath: String?,
        val isOpen: Boolean,
        val hasFocus: Boolean
    )

    /**
     * Get all open projects
     */
    fun getOpenProjects(): List<ProjectInfo> {
        val projectManager = ProjectManager.getInstance()

        return projectManager.openProjects.map { project ->
            ProjectInfo(
                name = project.name,
                basePath = project.basePath,
                isOpen = project.isOpen,
                hasFocus = isProjectFocused(project)
            )
        }
    }

    /**
     * Check if a project window is focused
     */
    private fun isProjectFocused(project: Project): Boolean {
        val windowManager = WindowManager.getInstance()
        val frame = windowManager.getIdeFrame(project) ?: return false
        val component = frame.component ?: return false
        return component.isShowing && component.isFocusOwner
    }

    /**
     * Resolve a project by name or path.
     *
     * The identifier can be:
     * - A project name (exact match)
     * - An absolute path to the project base directory
     * - Any absolute path that lies inside a project (prefix match)
     *
     * Best practice for callers: pass the project base directory path.
     */
    fun resolveProject(identifier: String): Project? {
        val projectManager = ProjectManager.getInstance()
        val openProjects = projectManager.openProjects

        // 1. Exact name match
        openProjects.find { it.name == identifier }?.let { return it }

        // 2. Exact base path match
        openProjects.find { it.basePath == identifier }?.let { return it }

        // 3. The identifier is a path that starts with a project's base path
        //    (i.e. a file/subdir inside the project)
        if (identifier.startsWith("/")) {
            val normalized = identifier.trimEnd('/')
            openProjects
                .filter { it.basePath != null }
                .sortedByDescending { it.basePath!!.length } // longest prefix wins
                .find { normalized.startsWith(it.basePath!!) }
                ?.let { return it }
        }

        return null
    }

    /**
     * Get a project by name (kept for backwards compatibility).
     */
    fun getProjectByName(name: String): Project? = resolveProject(name)

    /**
     * Get the active (focused) project
     */
    fun getActiveProject(): Project? {
        val projectManager = ProjectManager.getInstance()

        return projectManager.openProjects.firstOrNull { project ->
            isProjectFocused(project)
        } ?: projectManager.openProjects.firstOrNull()
    }

    /**
     * Find files in a project matching a pattern
     */
    fun findFiles(project: Project, pattern: String, maxResults: Int = 100): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        @Suppress("DEPRECATION")
        val baseDir = project.baseDir ?: return result

        fun searchRecursive(dir: VirtualFile) {
            if (result.size >= maxResults) return

            dir.children.forEach { file ->
                if (result.size >= maxResults) return

                if (file.isDirectory) {
                    // Skip hidden and system directories
                    if (!file.name.startsWith(".") && file.name != "node_modules") {
                        searchRecursive(file)
                    }
                } else if (file.name.contains(pattern, ignoreCase = true)) {
                    result.add(file)
                }
            }
        }

        ApplicationManager.getApplication().runReadAction {
            searchRecursive(baseDir)
        }

        return result
    }

    companion object {
        val instance: ProjectService
            get() = ApplicationManager.getApplication().getService(ProjectService::class.java)
    }
}

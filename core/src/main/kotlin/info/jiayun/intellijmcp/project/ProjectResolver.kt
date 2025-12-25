package info.jiayun.intellijmcp.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import info.jiayun.intellijmcp.api.ProjectInfo

class ProjectResolver {

    /**
     * Resolve the project to operate on
     */
    fun resolve(projectPath: String?): Project {
        if (projectPath != null) {
            return findProjectByPath(projectPath)
                ?: throw ProjectNotFoundException("Project not found: $projectPath")
        }
        return getActiveProject()
            ?: throw NoActiveProjectException("No active project")
    }

    /**
     * Get the currently active project
     */
    fun getActiveProject(): Project? {
        val windowManager = WindowManager.getInstance()
        val projects = ProjectManager.getInstance().openProjects

        // Find the project corresponding to the currently focused window
        for (project in projects) {
            if (project.isDisposed) continue
            val frame = windowManager.getFrame(project)
            if (frame != null && frame.isActive) {
                return project
            }
        }

        // If no active window, return the first open project
        return projects.firstOrNull { !it.isDisposed }
    }

    /**
     * Find project by path
     */
    fun findProjectByPath(path: String): Project? {
        val normalizedPath = path.trimEnd('/', '\\')
        return ProjectManager.getInstance().openProjects.find { project ->
            !project.isDisposed &&
                    project.basePath?.trimEnd('/', '\\') == normalizedPath
        }
    }

    /**
     * List all open projects
     */
    fun listProjects(): List<ProjectInfo> {
        val activeProject = getActiveProject()
        return ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .map { project ->
                ProjectInfo(
                    name = project.name,
                    basePath = project.basePath ?: "",
                    isActive = project == activeProject
                )
            }
    }
}

class ProjectNotFoundException(message: String) : Exception(message)
class NoActiveProjectException(message: String) : Exception(message)

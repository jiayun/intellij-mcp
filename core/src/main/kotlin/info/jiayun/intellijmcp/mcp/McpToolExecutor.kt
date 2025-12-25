package info.jiayun.intellijmcp.mcp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import info.jiayun.intellijmcp.api.*
import info.jiayun.intellijmcp.project.ProjectResolver

class McpToolExecutor {

    private val projectResolver = ProjectResolver()
    private val registry = LanguageAdapterRegistry.getInstance()

    fun listProjects(): List<ProjectInfo> {
        return projectResolver.listProjects()
    }

    fun getSupportedLanguages(): List<Map<String, Any>> {
        return registry.getAllAdapters().map { adapter ->
            mapOf(
                "id" to adapter.languageId,
                "name" to adapter.languageDisplayName,
                "extensions" to adapter.supportedExtensions.toList()
            )
        }
    }

    fun findSymbol(
        name: String,
        kind: String?,
        language: String?,
        projectPath: String?
    ): List<SymbolInfo> {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)

        val symbolKind = kind?.let { parseSymbolKind(it) }

        val adapters = if (language != null) {
            listOfNotNull(registry.getAdapterByLanguage(language))
        } else {
            registry.getAllAdapters()
        }

        if (adapters.isEmpty()) {
            throw UnsupportedLanguageException(
                language ?: "No language adapters available"
            )
        }

        return ReadAction.compute<List<SymbolInfo>, Exception> {
            adapters.flatMap { adapter ->
                adapter.findSymbol(project, name, symbolKind)
            }
        }
    }

    fun findReferences(
        filePath: String,
        line: Int,
        column: Int,
        projectPath: String?
    ): List<LocationInfo> {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)

        val adapter = getAdapterForFile(filePath)

        return ReadAction.compute<List<LocationInfo>, Exception> {
            val offset = adapter.getOffset(project, filePath, line, column)
                ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")

            adapter.findReferences(project, filePath, offset)
        }
    }

    fun getSymbolInfo(
        filePath: String,
        line: Int,
        column: Int,
        projectPath: String?
    ): SymbolInfo {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)

        val adapter = getAdapterForFile(filePath)

        return ReadAction.compute<SymbolInfo, Exception> {
            val offset = adapter.getOffset(project, filePath, line, column)
                ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")

            adapter.getSymbolInfo(project, filePath, offset)
                ?: throw SymbolNotFoundException("No symbol found at $filePath:$line:$column")
        }
    }

    fun getFileSymbols(
        filePath: String,
        projectPath: String?
    ): FileSymbols {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)

        val adapter = getAdapterForFile(filePath)

        return ReadAction.compute<FileSymbols, Exception> {
            adapter.getFileSymbols(project, filePath)
        }
    }

    fun getTypeHierarchy(
        typeName: String,
        language: String?,
        projectPath: String?
    ): TypeHierarchy {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)

        val adapters = if (language != null) {
            listOfNotNull(registry.getAdapterByLanguage(language))
        } else {
            registry.getAllAdapters()
        }

        return ReadAction.compute<TypeHierarchy, Exception> {
            adapters.firstNotNullOfOrNull { adapter ->
                adapter.getTypeHierarchy(project, typeName)
            } ?: throw SymbolNotFoundException("Type not found: $typeName")
        }
    }

    private fun getAdapterForFile(filePath: String): LanguageAdapter {
        val extension = filePath.substringAfterLast('.', "")
        return registry.getAdapterByExtension(extension)
            ?: throw UnsupportedLanguageException("Unsupported file type: .$extension")
    }

    private fun parseSymbolKind(kind: String): SymbolKind {
        return when (kind.lowercase()) {
            "class" -> SymbolKind.CLASS
            "interface" -> SymbolKind.INTERFACE
            "function", "func" -> SymbolKind.FUNCTION
            "method" -> SymbolKind.METHOD
            "variable", "var" -> SymbolKind.VARIABLE
            "field" -> SymbolKind.FIELD
            "property" -> SymbolKind.PROPERTY
            "constant", "const" -> SymbolKind.CONSTANT
            else -> throw IllegalArgumentException("Unknown symbol kind: $kind")
        }
    }

    private fun checkIndexReady(project: Project) {
        if (DumbService.getInstance(project).isDumb) {
            throw IndexNotReadyException("IDE is still indexing. Please wait.")
        }
    }
}

class UnsupportedLanguageException(message: String) : Exception(message)
class InvalidPositionException(message: String) : Exception(message)
class SymbolNotFoundException(message: String) : Exception(message)
class IndexNotReadyException(message: String) : Exception(message)

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
            }.map { it.toOneBased() }  // Convert to 1-based
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

        // Convert from 1-based (MCP API) to 0-based (internal)
        val line0 = line - 1
        val column0 = column - 1

        return ReadAction.compute<List<LocationInfo>, Exception> {
            val offset = adapter.getOffset(project, filePath, line0, column0)
                ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")

            adapter.findReferences(project, filePath, offset)
                .map { it.toOneBased() }  // Convert back to 1-based
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

        // Convert from 1-based (MCP API) to 0-based (internal)
        val line0 = line - 1
        val column0 = column - 1

        return ReadAction.compute<SymbolInfo, Exception> {
            val offset = adapter.getOffset(project, filePath, line0, column0)
                ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")

            (adapter.getSymbolInfo(project, filePath, offset)
                ?: throw SymbolNotFoundException("No symbol found at $filePath:$line:$column"))
                .toOneBased()  // Convert back to 1-based
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
                .toOneBased()  // Convert to 1-based
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
            (adapters.firstNotNullOfOrNull { adapter ->
                adapter.getTypeHierarchy(project, typeName)
            } ?: throw SymbolNotFoundException("Type not found: $typeName"))
                .toOneBased()  // Convert to 1-based
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

// Extension functions for 0-based to 1-based conversion (MCP API uses 1-based)
private fun LocationInfo.toOneBased() = copy(
    line = line + 1,
    column = column + 1,
    endLine = endLine?.let { it + 1 },
    endColumn = endColumn?.let { it + 1 }
)

private fun SymbolInfo.toOneBased() = copy(
    location = location?.toOneBased(),
    nameLocation = nameLocation?.toOneBased()
)

private fun SymbolNode.toOneBased(): SymbolNode = copy(
    symbol = symbol.toOneBased(),
    children = children.map { it.toOneBased() }
)

private fun FileSymbols.toOneBased() = copy(
    imports = imports.map { it.copy(location = it.location.toOneBased()) },
    symbols = symbols.map { it.toOneBased() }
)

private fun TypeRef.toOneBased() = copy(
    location = location?.toOneBased()
)

private fun TypeHierarchy.toOneBased() = copy(
    superTypes = superTypes.map { it.toOneBased() },
    subTypes = subTypes.map { it.toOneBased() }
)

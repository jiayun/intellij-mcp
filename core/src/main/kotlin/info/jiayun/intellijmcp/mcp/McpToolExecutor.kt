package info.jiayun.intellijmcp.mcp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import info.jiayun.intellijmcp.api.*
import info.jiayun.intellijmcp.execution.RunConfigurationService
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
        projectPath: String?,
        includeLibraries: Boolean = false
    ): List<SymbolInfo> {
        val project = projectResolver.resolve(projectPath)

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

        return adapters.flatMap { adapter ->
            if (adapter.requiresReadAction && DumbService.getInstance(project).isDumb) {
                return@flatMap emptyList()
            }
            withReadActionIf(adapter.requiresReadAction) {
                adapter.findSymbol(project, name, symbolKind, includeLibraries)
            }
        }.map { it.toOneBased() }
    }

    fun findReferences(
        filePath: String,
        line: Int,
        column: Int,
        projectPath: String?,
        includeLibraries: Boolean = false
    ): List<LocationInfo> {
        val project = projectResolver.resolve(projectPath)

        val adapter = getAdapterForFile(filePath)
        if (adapter.requiresReadAction) checkIndexReady(project)

        // Convert from 1-based (MCP API) to 0-based (internal)
        val line0 = line - 1
        val column0 = column - 1

        // getOffset always needs ReadAction (PSI operation)
        val offset = ReadAction.compute<Int?, Exception> {
            adapter.getOffset(project, filePath, line0, column0)
        } ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")

        return withReadActionIf(adapter.requiresReadAction) {
            adapter.findReferences(project, filePath, offset, includeLibraries)
        }.map { it.toOneBased() }
    }

    fun getSymbolInfo(
        filePath: String,
        line: Int,
        column: Int,
        projectPath: String?
    ): SymbolInfo {
        val project = projectResolver.resolve(projectPath)

        val adapter = getAdapterForFile(filePath)
        if (adapter.requiresReadAction) checkIndexReady(project)

        // Convert from 1-based (MCP API) to 0-based (internal)
        val line0 = line - 1
        val column0 = column - 1

        // getOffset always needs ReadAction (PSI operation)
        val offset = ReadAction.compute<Int?, Exception> {
            adapter.getOffset(project, filePath, line0, column0)
        } ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")

        return (withReadActionIf(adapter.requiresReadAction) {
            adapter.getSymbolInfo(project, filePath, offset)
        } ?: throw SymbolNotFoundException("No symbol found at $filePath:$line:$column"))
            .toOneBased()
    }

    fun getFileSymbols(
        filePath: String,
        projectPath: String?
    ): FileSymbols {
        val project = projectResolver.resolve(projectPath)

        val adapter = getAdapterForFile(filePath)
        if (adapter.requiresReadAction) checkIndexReady(project)

        return withReadActionIf(adapter.requiresReadAction) {
            adapter.getFileSymbols(project, filePath)
        }.toOneBased()
    }

    fun getTypeHierarchy(
        typeName: String,
        language: String?,
        projectPath: String?,
        includeLibraries: Boolean = false
    ): TypeHierarchy {
        val project = projectResolver.resolve(projectPath)

        val adapters = if (language != null) {
            listOfNotNull(registry.getAdapterByLanguage(language))
        } else {
            registry.getAllAdapters()
        }

        return (adapters.firstNotNullOfOrNull { adapter ->
            if (adapter.requiresReadAction && DumbService.getInstance(project).isDumb) {
                return@firstNotNullOfOrNull null
            }
            withReadActionIf(adapter.requiresReadAction) {
                adapter.getTypeHierarchy(project, typeName, includeLibraries)
            }
        } ?: throw SymbolNotFoundException("Type not found: $typeName"))
            .toOneBased()
    }

    /**
     * Conditionally wrap a block in ReadAction.
     * PSI-based adapters need ReadAction; LSP-based adapters don't.
     */
    private fun <T> withReadActionIf(needed: Boolean, block: () -> T): T {
        return if (needed) {
            ReadAction.compute<T, Exception> { block() }
        } else {
            block()
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

    // ===== Run Configuration & Test Results =====

    fun listRunConfigurations(
        projectPath: String?,
        type: String? = null
    ): List<RunConfigurationInfo> {
        val project = projectResolver.resolve(projectPath)
        return RunConfigurationService.getInstance(project).listConfigurations(type)
    }

    fun runConfiguration(
        name: String,
        projectPath: String?,
        timeout: Long = 60_000
    ): ExecutionResult {
        val project = projectResolver.resolve(projectPath)
        return RunConfigurationService.getInstance(project).runConfiguration(name, timeout)
    }

    fun getTestResults(
        projectPath: String?,
        executionId: String? = null,
        includeOutput: Boolean = false,
        failedOnly: Boolean = false
    ): TestResults {
        val project = projectResolver.resolve(projectPath)
        return RunConfigurationService.getInstance(project).getTestResults(executionId, includeOutput, failedOnly)
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

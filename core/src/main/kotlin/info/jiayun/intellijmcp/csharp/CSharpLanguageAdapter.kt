package info.jiayun.intellijmcp.csharp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import info.jiayun.intellijmcp.api.*
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind

/**
 * C# language adapter using LSP (csharp-ls or OmniSharp)
 *
 * Unlike PSI-based adapters, this communicates with an external C# Language Server.
 *
 * IMPORTANT: All line/column values returned are 0-based.
 * McpToolExecutor.toOneBased() handles the conversion to 1-based for the MCP API.
 */
class CSharpLanguageAdapter : LanguageAdapter {

    private val logger = Logger.getInstance(CSharpLanguageAdapter::class.java)

    override val requiresReadAction = false
    override val languageId = "csharp"
    override val languageDisplayName = "C#"
    override val supportedExtensions = setOf("cs")

    private val clients = ConcurrentHashMap<String, CSharpLspClient>()

    companion object {
        private const val LSP_TIMEOUT_SECONDS = 30L
        private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true

        fun uriToPathStatic(uri: String): String {
            val path = uri.removePrefix("file://")
            return if (isWindows && path.length >= 3 && path[0] == '/' && path[2] == ':') {
                path.substring(1)
            } else {
                path
            }
        }
    }

    override fun supports(file: VirtualFile): Boolean {
        if (!CSharpLspClient.isAvailable()) return false
        return super.supports(file)
    }

    override fun supports(file: PsiFile): Boolean {
        if (!CSharpLspClient.isAvailable()) return false
        return super.supports(file)
    }

    private fun getClient(project: Project): CSharpLspClient {
        val key = project.basePath
            ?: throw IllegalStateException("Project has no base path")
        return clients.computeIfAbsent(key) { CSharpLspClient(project) }
    }

    // ===== Find Symbol =====
    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?,
        includeLibraries: Boolean
    ): List<SymbolInfo> {
        if (!CSharpLspClient.isAvailable()) {
            logger.info("C# find_symbol skipped: csharp-ls/OmniSharp not found in PATH or ~/.dotnet/tools")
            return emptyList()
        }
        if (name.isBlank()) return emptyList()
        if (!hasCSharpFiles(project)) {
            logger.info("C# find_symbol skipped: no .cs/.csproj/.sln files found in ${project.basePath}")
            return emptyList()
        }

        val client = getClient(project)
        val retryInterval = 3000L

        // maxRetries is determined inside the loop because isRecentlyInitialized()
        // may change after the first call triggers ensureInitialized()
        var maxRetries = 3
        for (attempt in 1..10) { // hard cap
            if (attempt > maxRetries) break

            try {
                val result = client.workspaceSymbol(name)
                    .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                // Re-evaluate after workspaceSymbol() (which calls ensureInitialized())
                val recentlyInitialized = client.isRecentlyInitialized()
                if (attempt == 1 && recentlyInitialized) {
                    maxRetries = 10
                }

                val symbols: List<SymbolInfo> = when {
                    result.isLeft -> result.left.map { symbolInfoFromLsp(it) }
                    result.isRight -> result.right.map { workspaceSymbolToSymbolInfo(it, client) }
                    else -> emptyList()
                }

                val filtered = symbols.filter { symbol ->
                    symbol.name.contains(name, ignoreCase = true) &&
                        (kind == null || symbol.kind == kind)
                }

                logger.debug("C# find_symbol '$name' attempt $attempt/$maxRetries: ${filtered.size} matches (${symbols.size} total from LSP)")

                if (filtered.isNotEmpty()) return filtered

                // If the LSP server reported errors (e.g. MSBuild failure), stop retrying
                val serverErrors = client.getServerErrors()
                if (serverErrors.isNotEmpty()) {
                    logger.warn("C# LSP server reported errors — symbol lookup will not work:\n" +
                        serverErrors.joinToString("\n") + "\n" +
                        "Possible fix: upgrade csharp-ls (dotnet tool update -g csharp-ls) or use a compatible .NET SDK version.")
                    return emptyList()
                }

                if (attempt < maxRetries && recentlyInitialized) {
                    logger.info("C# find_symbol returned empty, retrying... ($attempt/$maxRetries)")
                    Thread.sleep(retryInterval)
                }
            } catch (e: Exception) {
                logger.warn("Failed to find symbol: $name", e)
                return emptyList()
            }
        }

        if (client.isRecentlyInitialized()) {
            logger.info("C# find_symbol returned empty after $maxRetries retries. LSP may still be indexing.")
        }
        return emptyList()
    }

    private fun hasCSharpFiles(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        return File(basePath).walkTopDown()
            .take(500)
            .any { it.isFile && (it.extension == "cs" || it.extension == "csproj" || it.extension == "sln") }
    }

    // ===== Find References =====
    override fun findReferences(
        project: Project,
        filePath: String,
        offset: Int,
        includeLibraries: Boolean
    ): List<LocationInfo> {
        if (!CSharpLspClient.isAvailable()) return emptyList()

        val client = getClient(project)
        val (line, column) = offsetToLineColumn(project, filePath, offset)
            ?: throw IllegalArgumentException("Invalid offset: $offset for file: $filePath")

        return try {
            val locations = client.references(filePath, line, column)
                .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            locations.map { locationToLocationInfo(it, client) }
        } catch (e: Exception) {
            logger.warn("Failed to find references at $filePath:$offset", e)
            throw IllegalArgumentException("Failed to find references: ${e.message}", e)
        }
    }

    // ===== Get Symbol Info =====
    override fun getSymbolInfo(
        project: Project,
        filePath: String,
        offset: Int
    ): SymbolInfo? {
        if (!CSharpLspClient.isAvailable()) return null

        val client = getClient(project)
        val (line, column) = offsetToLineColumn(project, filePath, offset)
            ?: return null

        return try {
            val hoverFuture = client.hover(filePath, line, column)
            val definitionFuture = client.definition(filePath, line, column)

            val hover = hoverFuture.get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val definitionResult = definitionFuture.get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val definitions: List<Location> = when {
                definitionResult?.isLeft == true -> definitionResult.left
                definitionResult?.isRight == true -> definitionResult.right.map { link ->
                    Location(link.targetUri, link.targetRange)
                }
                else -> emptyList()
            }

            if (hover == null && definitions.isEmpty()) return null

            val documentation = extractDocumentation(hover)
            val location = definitions.firstOrNull()?.let { locationToLocationInfo(it, client) }
            val symbolName = extractSymbolName(documentation, project, filePath, offset) ?: "unknown"
            val symbolKind = inferSymbolKind(documentation)

            SymbolInfo(
                name = symbolName,
                kind = symbolKind,
                language = languageId,
                documentation = documentation,
                location = location,
                signature = documentation?.lines()?.firstOrNull()
            )
        } catch (e: Exception) {
            logger.warn("Failed to get symbol info at $filePath:$offset", e)
            null
        }
    }

    // ===== Get File Symbols =====
    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        if (!CSharpLspClient.isAvailable()) {
            return FileSymbols(filePath = filePath, language = languageId, symbols = emptyList())
        }

        val client = getClient(project)

        return try {
            val result = client.documentSymbol(filePath)
                .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val symbols = (result ?: emptyList()).mapNotNull { either ->
                when {
                    either == null -> null
                    either.isRight -> convertDocumentSymbol(either.right, filePath)
                    either.isLeft -> convertSymbolInformation(either.left, client)
                    else -> null
                }
            }

            FileSymbols(
                filePath = filePath,
                language = languageId,
                moduleName = File(filePath).nameWithoutExtension,
                imports = emptyList(),
                symbols = symbols
            )
        } catch (e: Exception) {
            logger.warn("Failed to get file symbols for $filePath", e)
            throw IllegalArgumentException("Failed to get file symbols: ${e.message}", e)
        }
    }

    // ===== Get Type Hierarchy =====
    override fun getTypeHierarchy(
        project: Project,
        typeName: String,
        includeLibraries: Boolean
    ): TypeHierarchy? {
        if (!CSharpLspClient.isAvailable()) return null

        val client = getClient(project)

        // Step 1: Find the type via workspace/symbol
        val symbolResult = try {
            client.workspaceSymbol(typeName).get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Failed to search for type: $typeName", e)
            return null
        }

        // Find a class/interface/struct matching the name
        val targetLocation = findTypeLocation(symbolResult, typeName, client) ?: return null

        // Step 2: Prepare type hierarchy at the symbol's location
        val items = try {
            client.prepareTypeHierarchy(
                targetLocation.filePath,
                targetLocation.line,
                targetLocation.column
            ).get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Type hierarchy not supported by C# LSP server", e)
            return null
        }

        val item = items?.firstOrNull() ?: return null

        // Step 3: Get supertypes and subtypes
        val superTypes = try {
            client.supertypes(item).get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.debug("Failed to get supertypes for $typeName", e)
            null
        }

        val subTypes = try {
            client.subtypes(item).get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.debug("Failed to get subtypes for $typeName", e)
            null
        }

        return TypeHierarchy(
            typeName = item.name,
            qualifiedName = item.detail,
            kind = mapLspSymbolKind(item.kind),
            superTypes = superTypes?.map { typeHierarchyItemToTypeRef(it, client) } ?: emptyList(),
            subTypes = subTypes?.map { typeHierarchyItemToTypeRef(it, client) } ?: emptyList()
        )
    }

    private fun findTypeLocation(
        result: org.eclipse.lsp4j.jsonrpc.messages.Either<List<SymbolInformation>, List<WorkspaceSymbol>>,
        typeName: String,
        client: CSharpLspClient
    ): LocationInfo? {
        val typeKinds = setOf(
            LspSymbolKind.Class, LspSymbolKind.Interface, LspSymbolKind.Struct, LspSymbolKind.Enum
        )

        when {
            result.isLeft -> {
                val match = result.left
                    .filter { it.kind in typeKinds }
                    .find { it.name.equals(typeName, ignoreCase = true) }
                    ?: result.left
                        .filter { it.kind in typeKinds }
                        .find { it.name.contains(typeName, ignoreCase = true) }
                    ?: return null
                return locationToLocationInfo(match.location, client)
            }
            result.isRight -> {
                val match = result.right
                    .filter { it.kind in typeKinds }
                    .find { it.name.equals(typeName, ignoreCase = true) }
                    ?: result.right
                        .filter { it.kind in typeKinds }
                        .find { it.name.contains(typeName, ignoreCase = true) }
                    ?: return null

                return when {
                    match.location.isLeft -> locationToLocationInfo(match.location.left, client)
                    match.location.isRight -> {
                        val wsLoc = match.location.right
                        val filePath = client.uriToPath(wsLoc.uri)
                        LocationInfo(filePath = filePath, line = 0, column = 0)
                    }
                    else -> null
                }
            }
            else -> return null
        }
    }

    private fun typeHierarchyItemToTypeRef(item: org.eclipse.lsp4j.TypeHierarchyItem, client: CSharpLspClient): TypeRef {
        val filePath = client.uriToPath(item.uri)
        return TypeRef(
            name = item.name,
            qualifiedName = item.detail,
            location = LocationInfo(
                filePath = filePath,
                line = item.range.start.line,       // 0-based
                column = item.range.start.character, // 0-based
                endLine = item.range.end.line,
                endColumn = item.range.end.character
            )
        )
    }

    // ===== Get Offset =====
    override fun getOffset(
        project: Project,
        filePath: String,
        line: Int,    // 0-based (McpToolExecutor already converts from 1-based)
        column: Int   // 0-based
    ): Int? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun offsetToLineColumn(project: Project, filePath: String, offset: Int): Pair<Int, Int>? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        if (offset < 0 || offset > document.textLength) return null

        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line)
        return Pair(line, column) // 0-based for LSP
    }

    private fun mapLspSymbolKind(lspKind: LspSymbolKind?): SymbolKind {
        return when (lspKind) {
            LspSymbolKind.Class -> SymbolKind.CLASS
            LspSymbolKind.Interface -> SymbolKind.INTERFACE
            LspSymbolKind.Enum -> SymbolKind.ENUM
            LspSymbolKind.Struct -> SymbolKind.CLASS
            LspSymbolKind.Function -> SymbolKind.FUNCTION
            LspSymbolKind.Method -> SymbolKind.METHOD
            LspSymbolKind.Property -> SymbolKind.PROPERTY
            LspSymbolKind.Field -> SymbolKind.FIELD
            LspSymbolKind.Variable -> SymbolKind.VARIABLE
            LspSymbolKind.Constant -> SymbolKind.CONSTANT
            LspSymbolKind.Module, LspSymbolKind.Package, LspSymbolKind.Namespace -> SymbolKind.MODULE
            LspSymbolKind.TypeParameter -> SymbolKind.PARAMETER
            else -> SymbolKind.VARIABLE
        }
    }

    /**
     * Convert LSP Location to LocationInfo.
     * LSP returns 0-based; we keep 0-based here.
     * McpToolExecutor.toOneBased() adds +1 later.
     */
    private fun locationToLocationInfo(location: Location, client: CSharpLspClient): LocationInfo {
        val filePath = client.uriToPath(location.uri)
        return LocationInfo(
            filePath = filePath,
            line = location.range.start.line,           // 0-based
            column = location.range.start.character,     // 0-based
            endLine = location.range.end.line,
            endColumn = location.range.end.character
        )
    }

    private fun symbolInfoFromLsp(lspSymbol: SymbolInformation): SymbolInfo {
        val path = uriToPathStatic(lspSymbol.location.uri)
        return SymbolInfo(
            name = lspSymbol.name,
            kind = mapLspSymbolKind(lspSymbol.kind),
            language = languageId,
            qualifiedName = lspSymbol.containerName?.let { "$it.${lspSymbol.name}" },
            location = LocationInfo(
                filePath = path,
                line = lspSymbol.location.range.start.line,
                column = lspSymbol.location.range.start.character,
                endLine = lspSymbol.location.range.end.line,
                endColumn = lspSymbol.location.range.end.character
            )
        )
    }

    private fun workspaceSymbolToSymbolInfo(lspSymbol: WorkspaceSymbol, client: CSharpLspClient): SymbolInfo {
        val location = when {
            lspSymbol.location.isLeft -> locationToLocationInfo(lspSymbol.location.left, client)
            lspSymbol.location.isRight -> {
                val wsLocation = lspSymbol.location.right
                val filePath = client.uriToPath(wsLocation.uri)
                LocationInfo(filePath = filePath, line = 0, column = 0)
            }
            else -> null
        }

        return SymbolInfo(
            name = lspSymbol.name,
            kind = mapLspSymbolKind(lspSymbol.kind),
            language = languageId,
            qualifiedName = lspSymbol.containerName?.let { "$it.${lspSymbol.name}" },
            location = location
        )
    }

    private fun convertDocumentSymbol(symbol: DocumentSymbol, filePath: String): SymbolNode {
        val symbolInfo = SymbolInfo(
            name = symbol.name,
            kind = mapLspSymbolKind(symbol.kind),
            language = languageId,
            qualifiedName = symbol.name,
            documentation = symbol.detail,
            location = LocationInfo(
                filePath = filePath,
                line = symbol.range.start.line,              // 0-based
                column = symbol.range.start.character,
                endLine = symbol.range.end.line,
                endColumn = symbol.range.end.character
            ),
            nameLocation = LocationInfo(
                filePath = filePath,
                line = symbol.selectionRange.start.line,     // 0-based
                column = symbol.selectionRange.start.character,
                endLine = symbol.selectionRange.end.line,
                endColumn = symbol.selectionRange.end.character
            )
        )

        val children = symbol.children?.map { convertDocumentSymbol(it, filePath) } ?: emptyList()
        return SymbolNode(symbol = symbolInfo, children = children)
    }

    private fun convertSymbolInformation(symbol: SymbolInformation, client: CSharpLspClient): SymbolNode {
        val filePath = client.uriToPath(symbol.location.uri)
        return SymbolNode(
            symbol = SymbolInfo(
                name = symbol.name,
                kind = mapLspSymbolKind(symbol.kind),
                language = languageId,
                qualifiedName = symbol.containerName?.let { "$it.${symbol.name}" },
                location = LocationInfo(
                    filePath = filePath,
                    line = symbol.location.range.start.line,
                    column = symbol.location.range.start.character,
                    endLine = symbol.location.range.end.line,
                    endColumn = symbol.location.range.end.character
                )
            ),
            children = emptyList()
        )
    }

    private fun extractDocumentation(hover: org.eclipse.lsp4j.Hover?): String? {
        if (hover == null) return null
        return when {
            hover.contents.isRight -> hover.contents.right.value
            hover.contents.isLeft -> {
                hover.contents.left.joinToString("\n") { markedString ->
                    when {
                        markedString.isLeft -> markedString.left
                        markedString.isRight -> markedString.right.value
                        else -> ""
                    }
                }
            }
            else -> null
        }
    }

    private fun extractSymbolName(documentation: String?, project: Project, filePath: String, offset: Int): String? {
        documentation?.let { doc ->
            val patterns = listOf(
                Regex("""(?:class|struct|enum|interface|record)\s+(\w+)"""),
                Regex("""(?:void|int|string|bool|var|object|dynamic)\s+(\w+)\s*\("""),
                Regex("""(\w+)\s*\("""),
                Regex("""(?:public|private|protected|internal|static|readonly|const)\s+\S+\s+(\w+)""")
            )
            for (pattern in patterns) {
                val match = pattern.find(doc)
                if (match != null) return match.groupValues[1]
            }
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val text = document.text
        if (offset >= text.length) return null

        var start = offset
        var end = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++

        return if (start < end) text.substring(start, end) else null
    }

    private fun inferSymbolKind(documentation: String?): SymbolKind {
        if (documentation == null) return SymbolKind.VARIABLE
        val doc = documentation.lowercase()
        return when {
            doc.startsWith("class ") || doc.startsWith("struct ") || doc.startsWith("record ") -> SymbolKind.CLASS
            doc.startsWith("interface ") -> SymbolKind.INTERFACE
            doc.startsWith("enum ") -> SymbolKind.ENUM
            doc.startsWith("namespace ") -> SymbolKind.MODULE
            doc.contains("(") && doc.contains(")") -> SymbolKind.METHOD
            else -> SymbolKind.VARIABLE
        }
    }

}

package info.jiayun.intellijmcp.swift

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import info.jiayun.intellijmcp.api.*
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind

/**
 * Swift language adapter using SourceKit-LSP
 *
 * Unlike other language adapters that use PSI, this adapter communicates with
 * SourceKit-LSP via the Language Server Protocol.
 */
class SwiftLanguageAdapter : LanguageAdapter {

    private val logger = Logger.getInstance(SwiftLanguageAdapter::class.java)

    override val languageId = "swift"
    override val languageDisplayName = "Swift"
    override val supportedExtensions = setOf("swift")

    // Cache of LSP clients per project (by project base path)
    private val clients = ConcurrentHashMap<String, SwiftLspClient>()

    companion object {
        private const val LSP_TIMEOUT_SECONDS = 30L
    }

    override fun supports(file: VirtualFile): Boolean {
        // Only support Swift files on macOS with SourceKit-LSP available
        if (!SwiftLspClient.isAvailable()) return false
        return super.supports(file)
    }

    override fun supports(file: PsiFile): Boolean {
        if (!SwiftLspClient.isAvailable()) return false
        return super.supports(file)
    }

    private fun getClient(project: Project): SwiftLspClient {
        val key = project.basePath
            ?: throw IllegalStateException("Project has no base path")

        return clients.computeIfAbsent(key) {
            SwiftLspClient(project)
        }
    }

    // ===== Find Symbol =====
    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        if (!SwiftLspClient.isAvailable()) return emptyList()
        if (name.isBlank()) return emptyList()

        // Only start LSP for projects that have Swift files
        if (!hasSwiftFiles(project)) return emptyList()

        val client = getClient(project)

        // Retry logic: always retry at least once if empty, more if recently initialized
        val maxRetries = if (client.isRecentlyInitialized()) 5 else 2
        val retryInterval = 3000L  // 3 seconds between retries

        for (attempt in 1..maxRetries) {
            try {
                val result = client.workspaceSymbol(name)
                    .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val symbols: List<SymbolInfo> = when {
                    result.isLeft -> result.left.map { info: SymbolInformation -> symbolInfoToSymbolInfo(info) }
                    result.isRight -> result.right.map { ws: WorkspaceSymbol -> workspaceSymbolToSymbolInfo(ws) }
                    else -> emptyList()
                }

                val filtered = symbols.filter { symbol ->
                    symbol.name.contains(name, ignoreCase = true) &&
                            (kind == null || symbol.kind == kind)
                }

                if (filtered.isNotEmpty()) {
                    return filtered
                }

                // If still in initialization period and not the last attempt, wait and retry
                if (attempt < maxRetries && client.isRecentlyInitialized()) {
                    logger.info("Swift find_symbol returned empty, retrying... ($attempt/$maxRetries)")
                    Thread.sleep(retryInterval)
                }
            } catch (e: Exception) {
                logger.warn("Failed to find symbol: $name", e)
                return emptyList()
            }
        }

        // Log hint if still empty after retries and recently initialized
        if (client.isRecentlyInitialized()) {
            logger.info("Swift find_symbol returned empty after retries. LSP may still be indexing.")
        }

        return emptyList()
    }

    /**
     * Check if project contains Swift files
     */
    private fun hasSwiftFiles(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        return File(basePath).walkTopDown()
            .take(500)
            .any { it.isFile && it.extension == "swift" }
    }

    // ===== Find References =====
    override fun findReferences(
        project: Project,
        filePath: String,
        offset: Int
    ): List<LocationInfo> {
        if (!SwiftLspClient.isAvailable()) return emptyList()

        val client = getClient(project)
        val (line, column) = offsetToLineColumn(project, filePath, offset)
            ?: throw IllegalArgumentException("Invalid offset: $offset for file: $filePath")

        return try {
            val locations = client.references(filePath, line, column)
                .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            locations.map { locationToLocationInfo(it) }
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
        if (!SwiftLspClient.isAvailable()) return null

        val client = getClient(project)
        val (line, column) = offsetToLineColumn(project, filePath, offset)
            ?: return null

        return try {
            // Use hover for documentation and definition for location
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

            // Extract documentation from hover
            val documentation = extractDocumentation(hover)

            // Get location from definition
            val location = definitions.firstOrNull()?.let { locationToLocationInfo(it) }

            // Extract symbol name from hover content or file
            val symbolName = extractSymbolName(documentation, project, filePath, offset) ?: "unknown"

            // Try to infer symbol kind from documentation
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
        if (!SwiftLspClient.isAvailable()) {
            return FileSymbols(
                filePath = filePath,
                language = languageId,
                symbols = emptyList()
            )
        }

        val client = getClient(project)

        return try {
            val result = client.documentSymbol(filePath)
                .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val symbols = result.mapNotNull { either ->
                when {
                    either.isRight -> convertDocumentSymbol(either.right, filePath)
                    either.isLeft -> convertSymbolInformation(either.left, filePath)
                    else -> null
                }
            }

            FileSymbols(
                filePath = filePath,
                language = languageId,
                moduleName = extractModuleName(filePath),
                imports = emptyList(), // LSP doesn't provide import info directly
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
        typeName: String
    ): TypeHierarchy? {
        // SourceKit-LSP doesn't fully support LSP 3.17 type hierarchy yet
        // Return null to indicate this feature is not available
        logger.info("Type hierarchy not supported for Swift (SourceKit-LSP limitation)")
        return null
    }

    // ===== Get Offset =====
    override fun getOffset(
        project: Project,
        filePath: String,
        line: Int,    // 1-based
        column: Int   // 1-based
    ): Int? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return null

        // Convert 1-based to 0-based
        val lineIndex = line - 1
        val columnIndex = column - 1

        if (lineIndex < 0 || lineIndex >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineEndOffset = document.getLineEndOffset(lineIndex)
        val offset = lineStartOffset + columnIndex

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    /**
     * Convert offset to 0-based line and column for LSP
     */
    private fun offsetToLineColumn(project: Project, filePath: String, offset: Int): Pair<Int, Int>? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return null

        if (offset < 0 || offset > document.textLength) return null

        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line)
        return Pair(line, column) // 0-based for LSP
    }

    /**
     * Map LSP SymbolKind to our SymbolKind
     */
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
     * Convert LSP Location to LocationInfo (with 0-based to 1-based conversion)
     */
    private fun locationToLocationInfo(location: Location): LocationInfo {
        val uri = location.uri
        val filePath = if (uri.startsWith("file://")) {
            uri.removePrefix("file://")
        } else {
            uri
        }

        return LocationInfo(
            filePath = filePath,
            line = location.range.start.line + 1,     // Convert 0-based to 1-based
            column = location.range.start.character + 1,
            endLine = location.range.end.line + 1,
            endColumn = location.range.end.character + 1
        )
    }

    /**
     * Convert LSP SymbolInformation to our SymbolInfo
     */
    private fun symbolInfoToSymbolInfo(lspSymbol: SymbolInformation): SymbolInfo {
        return SymbolInfo(
            name = lspSymbol.name,
            kind = mapLspSymbolKind(lspSymbol.kind),
            language = languageId,
            qualifiedName = lspSymbol.containerName?.let { "$it.${lspSymbol.name}" },
            location = locationToLocationInfo(lspSymbol.location)
        )
    }

    /**
     * Convert LSP WorkspaceSymbol to our SymbolInfo
     */
    private fun workspaceSymbolToSymbolInfo(lspSymbol: WorkspaceSymbol): SymbolInfo {
        val location = when {
            lspSymbol.location.isLeft -> locationToLocationInfo(lspSymbol.location.left)
            lspSymbol.location.isRight -> {
                val wsLocation = lspSymbol.location.right
                val uri = wsLocation.uri
                val filePath = if (uri.startsWith("file://")) uri.removePrefix("file://") else uri
                LocationInfo(
                    filePath = filePath,
                    line = 1,
                    column = 1
                )
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

    /**
     * Convert LSP DocumentSymbol to our SymbolNode (hierarchical)
     */
    private fun convertDocumentSymbol(symbol: DocumentSymbol, filePath: String): SymbolNode {
        val symbolInfo = SymbolInfo(
            name = symbol.name,
            kind = mapLspSymbolKind(symbol.kind),
            language = languageId,
            qualifiedName = symbol.name,
            documentation = symbol.detail,
            location = LocationInfo(
                filePath = filePath,
                line = symbol.range.start.line + 1,
                column = symbol.range.start.character + 1,
                endLine = symbol.range.end.line + 1,
                endColumn = symbol.range.end.character + 1
            ),
            nameLocation = LocationInfo(
                filePath = filePath,
                line = symbol.selectionRange.start.line + 1,
                column = symbol.selectionRange.start.character + 1,
                endLine = symbol.selectionRange.end.line + 1,
                endColumn = symbol.selectionRange.end.character + 1
            )
        )

        val children = symbol.children?.map { convertDocumentSymbol(it, filePath) } ?: emptyList()

        return SymbolNode(symbol = symbolInfo, children = children)
    }

    /**
     * Convert LSP SymbolInformation to SymbolNode (flat)
     */
    private fun convertSymbolInformation(symbol: SymbolInformation, filePath: String): SymbolNode {
        return SymbolNode(
            symbol = symbolInfoToSymbolInfo(symbol),
            children = emptyList()
        )
    }

    /**
     * Extract module name from file path
     */
    private fun extractModuleName(filePath: String): String? {
        return File(filePath).nameWithoutExtension
    }

    /**
     * Extract documentation from hover response
     */
    private fun extractDocumentation(hover: org.eclipse.lsp4j.Hover?): String? {
        if (hover == null) return null

        return when {
            hover.contents.isRight -> {
                // MarkupContent
                hover.contents.right.value
            }
            hover.contents.isLeft -> {
                // List of MarkedString or String
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

    /**
     * Extract symbol name from documentation or file content
     */
    private fun extractSymbolName(documentation: String?, project: Project, filePath: String, offset: Int): String? {
        // Try to extract from documentation
        documentation?.let { doc ->
            // Swift docs often show: "func name(...)", "class Name", "struct Name"
            val patterns = listOf(
                Regex("""(?:func|init)\s+(\w+)"""),
                Regex("""(?:class|struct|enum|protocol|extension)\s+(\w+)"""),
                Regex("""(?:var|let)\s+(\w+)"""),
                Regex("""(\w+)\s*\(""")  // Function call pattern
            )
            for (pattern in patterns) {
                val match = pattern.find(doc)
                if (match != null) return match.groupValues[1]
            }
        }

        // Fallback: read word at offset from file
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val text = document.text
        if (offset >= text.length) return null

        // Find word boundaries
        var start = offset
        var end = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++

        return if (start < end) text.substring(start, end) else null
    }

    /**
     * Infer symbol kind from documentation content
     */
    private fun inferSymbolKind(documentation: String?): SymbolKind {
        if (documentation == null) return SymbolKind.VARIABLE

        val doc = documentation.lowercase()
        return when {
            doc.startsWith("class ") -> SymbolKind.CLASS
            doc.startsWith("struct ") -> SymbolKind.CLASS
            doc.startsWith("enum ") -> SymbolKind.ENUM
            doc.startsWith("protocol ") -> SymbolKind.INTERFACE
            doc.startsWith("func ") || doc.startsWith("init") -> SymbolKind.FUNCTION
            doc.startsWith("var ") || doc.startsWith("let ") -> SymbolKind.PROPERTY
            doc.contains("->") -> SymbolKind.FUNCTION  // Has return type, likely a function
            else -> SymbolKind.VARIABLE
        }
    }
}

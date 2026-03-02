package info.jiayun.intellijmcp.csharp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import info.jiayun.intellijmcp.api.*

/**
 * C# language adapter using text-based parsing.
 *
 * Rider uses ReSharper's .NET engine for C# — the IntelliJ frontend has only a minimal
 * PSI facade. Generic IntelliJ PSI types (PsiNamedElement, PsiTreeUtil) don't match
 * Rider's C# PSI elements, so all PSI-based symbol resolution fails.
 *
 * This adapter uses [CSharpTextParser] to extract symbols from source text via regex
 * patterns, similar to how the Swift adapter uses LSP as an alternative to PSI.
 */
class CSharpLanguageAdapter : LanguageAdapter {

    override val languageId = "csharp"
    override val languageDisplayName = "C#"
    override val supportedExtensions = setOf("cs")

    private val parser = CSharpTextParser()

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        FilenameIndex.getAllFilesByExt(project, "cs", scope).forEach { virtualFile ->
            val text = try {
                String(virtualFile.contentsToByteArray(), virtualFile.charset)
            } catch (_: Exception) {
                return@forEach
            }

            val parsed = parser.parse(text)
            val filePath = virtualFile.path

            val matching = parser.findSymbolsByName(parsed, name)
            for (symbol in matching) {
                val symbolKind = mapKind(symbol.kind)
                if (kind == null || kind == symbolKind) {
                    results.add(toSymbolInfo(symbol, filePath, parsed.namespaceName))
                }
            }
        }

        return results
    }

    // ===== Find References =====

    override fun findReferences(
        project: Project,
        filePath: String,
        offset: Int
    ): List<LocationInfo> {
        // Get symbol name at position
        val text = readFileText(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val symbolName = extractWordAtOffset(text, offset)
            ?: throw IllegalArgumentException("No symbol at offset")

        // Search all .cs files for whole-word occurrences
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<LocationInfo>()
        val wordRegex = Regex("""\b${Regex.escape(symbolName)}\b""")

        FilenameIndex.getAllFilesByExt(project, "cs", scope).forEach { virtualFile ->
            val fileText = try {
                String(virtualFile.contentsToByteArray(), virtualFile.charset)
            } catch (_: Exception) {
                return@forEach
            }

            val lines = fileText.lines()
            for ((lineIdx, line) in lines.withIndex()) {
                for (match in wordRegex.findAll(line)) {
                    // Skip matches inside comments
                    val beforeMatch = line.substring(0, match.range.first)
                    if (beforeMatch.contains("//") || beforeMatch.contains("/*")) continue

                    // Skip matches inside string literals (basic heuristic)
                    val quoteCount = beforeMatch.count { it == '"' }
                    if (quoteCount % 2 != 0) continue

                    results.add(LocationInfo(
                        filePath = virtualFile.path,
                        line = lineIdx + 1,
                        column = match.range.first + 1,
                        endLine = lineIdx + 1,
                        endColumn = match.range.last + 2,
                        preview = line.trim()
                    ))
                }
            }
        }

        return results
    }

    // ===== Get Symbol Info =====

    override fun getSymbolInfo(
        project: Project,
        filePath: String,
        offset: Int
    ): SymbolInfo? {
        val text = readFileText(project, filePath) ?: return null
        val (line, column) = offsetToLineColumn(text, offset) ?: return null

        val parsed = parser.parse(text)
        val symbol = parser.findSymbolAt(parsed, line, column) ?: return null

        return toSymbolInfo(symbol, filePath, parsed.namespaceName)
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val text = readFileText(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val parsed = parser.parse(text)

        val imports = parsed.usings.map { using ->
            ImportInfo(
                module = using.module,
                alias = using.alias,
                names = null,
                location = LocationInfo(
                    filePath = filePath,
                    line = using.line,
                    column = using.column
                )
            )
        }

        val symbols = parsed.symbols.map { toSymbolNode(it, filePath, parsed.namespaceName) }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = parsed.namespaceName,
            imports = imports,
            symbols = symbols
        )
    }

    // ===== Get Type Hierarchy =====

    override fun getTypeHierarchy(
        project: Project,
        typeName: String
    ): TypeHierarchy? {
        val scope = GlobalSearchScope.projectScope(project)

        FilenameIndex.getAllFilesByExt(project, "cs", scope).forEach { virtualFile ->
            val text = try {
                String(virtualFile.contentsToByteArray(), virtualFile.charset)
            } catch (_: Exception) {
                return@forEach
            }

            val parsed = parser.parse(text)
            val matching = parser.findSymbolsByName(parsed, typeName)
            val typeSymbol = matching.firstOrNull { isTypeKind(it.kind) } ?: return@forEach

            val superTypes = typeSymbol.baseTypes.map { baseType ->
                TypeRef(name = baseType, qualifiedName = null, location = null)
            }

            return TypeHierarchy(
                typeName = typeSymbol.name,
                qualifiedName = parsed.namespaceName?.let { "$it.${typeSymbol.name}" },
                kind = mapKind(typeSymbol.kind),
                superTypes = superTypes,
                subTypes = emptyList()
            )
        }

        return null
    }

    // ===== Get Offset =====

    override fun getOffset(
        project: Project,
        filePath: String,
        line: Int,
        column: Int
    ): Int? {
        val psiFile = getCsFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

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

    private fun getCsFile(project: Project, filePath: String): PsiFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    private fun readFileText(project: Project, filePath: String): String? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        return try {
            String(virtualFile.contentsToByteArray(), virtualFile.charset)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Convert a 0-based offset to 1-based line and column.
     */
    private fun offsetToLineColumn(text: String, offset: Int): Pair<Int, Int>? {
        if (offset < 0 || offset >= text.length) return null

        var line = 1
        var col = 1
        for (i in 0 until offset) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return Pair(line, col)
    }

    /**
     * Extract the word (identifier) at a given 0-based offset.
     */
    private fun extractWordAtOffset(text: String, offset: Int): String? {
        if (offset < 0 || offset >= text.length) return null
        if (!text[offset].isLetterOrDigit() && text[offset] != '_') return null

        var start = offset
        var end = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++

        return if (start < end) text.substring(start, end) else null
    }

    /**
     * Map parser symbol kind to API SymbolKind.
     */
    private fun mapKind(kind: CSharpTextParser.ParsedSymbolKind): SymbolKind {
        return when (kind) {
            CSharpTextParser.ParsedSymbolKind.NAMESPACE -> SymbolKind.MODULE
            CSharpTextParser.ParsedSymbolKind.CLASS -> SymbolKind.CLASS
            CSharpTextParser.ParsedSymbolKind.STRUCT -> SymbolKind.CLASS
            CSharpTextParser.ParsedSymbolKind.ENUM -> SymbolKind.ENUM
            CSharpTextParser.ParsedSymbolKind.INTERFACE -> SymbolKind.INTERFACE
            CSharpTextParser.ParsedSymbolKind.RECORD -> SymbolKind.CLASS
            CSharpTextParser.ParsedSymbolKind.METHOD -> SymbolKind.METHOD
            CSharpTextParser.ParsedSymbolKind.CONSTRUCTOR -> SymbolKind.METHOD
            CSharpTextParser.ParsedSymbolKind.PROPERTY -> SymbolKind.PROPERTY
            CSharpTextParser.ParsedSymbolKind.FIELD -> SymbolKind.FIELD
            CSharpTextParser.ParsedSymbolKind.EVENT -> SymbolKind.FIELD
            CSharpTextParser.ParsedSymbolKind.ENUM_MEMBER -> SymbolKind.CONSTANT
        }
    }

    private fun isTypeKind(kind: CSharpTextParser.ParsedSymbolKind): Boolean {
        return kind in setOf(
            CSharpTextParser.ParsedSymbolKind.CLASS,
            CSharpTextParser.ParsedSymbolKind.STRUCT,
            CSharpTextParser.ParsedSymbolKind.ENUM,
            CSharpTextParser.ParsedSymbolKind.INTERFACE,
            CSharpTextParser.ParsedSymbolKind.RECORD
        )
    }

    /**
     * Convert a parsed symbol to SymbolInfo.
     */
    private fun toSymbolInfo(
        symbol: CSharpTextParser.ParsedSymbol,
        filePath: String,
        namespaceName: String?
    ): SymbolInfo {
        val kind = mapKind(symbol.kind)
        val qualifiedName = namespaceName?.let { "$it.${symbol.name}" }

        return SymbolInfo(
            name = symbol.name,
            kind = kind,
            language = languageId,
            qualifiedName = qualifiedName,
            signature = symbol.signature,
            documentation = symbol.documentation,
            location = LocationInfo(
                filePath = filePath,
                line = symbol.line,
                column = symbol.column,
                endLine = symbol.endLine,
                endColumn = symbol.endColumn,
                preview = symbol.signature?.take(100)
            ),
            nameLocation = LocationInfo(
                filePath = filePath,
                line = symbol.line,
                column = symbol.nameColumn
            ),
            returnType = symbol.returnType,
            parameters = symbol.parameters?.map { p ->
                ParameterInfo(
                    name = p.name,
                    type = p.type,
                    defaultValue = p.defaultValue,
                    isOptional = p.isOptional
                )
            },
            modifiers = symbol.modifiers.takeIf { it.isNotEmpty() },
            annotations = symbol.attributes.takeIf { it.isNotEmpty() },
            superTypes = symbol.baseTypes.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Convert a parsed symbol to a SymbolNode (with children).
     */
    private fun toSymbolNode(
        symbol: CSharpTextParser.ParsedSymbol,
        filePath: String,
        namespaceName: String?
    ): SymbolNode {
        val info = toSymbolInfo(symbol, filePath, namespaceName)
        val children = symbol.children.map { toSymbolNode(it, filePath, namespaceName) }
        return SymbolNode(symbol = info, children = children)
    }
}
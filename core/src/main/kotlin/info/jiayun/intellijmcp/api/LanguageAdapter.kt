package info.jiayun.intellijmcp.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Language adapter interface
 *
 * Each language needs to implement this interface to provide PSI operation capabilities
 */
interface LanguageAdapter {

    companion object {
        val EP_NAME: ExtensionPointName<LanguageAdapter> =
            ExtensionPointName.create("info.jiayun.intellij-mcp.languageAdapter")
    }

    /**
     * Language identifier (e.g., "python", "java", "kotlin")
     */
    val languageId: String

    /**
     * Language display name
     */
    val languageDisplayName: String

    /**
     * Supported file extensions
     */
    val supportedExtensions: Set<String>

    /**
     * Check if the file is supported
     */
    fun supports(file: VirtualFile): Boolean {
        return file.extension?.lowercase() in supportedExtensions
    }

    /**
     * Check if the PsiFile is supported
     */
    fun supports(file: PsiFile): Boolean {
        return file.virtualFile?.let { supports(it) } ?: false
    }

    /**
     * Find symbol by name
     *
     * @param project Project
     * @param name Symbol name
     * @param kind Optional symbol kind filter
     * @return List of matching symbols
     */
    fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind? = null
    ): List<SymbolInfo>

    /**
     * Find all references to a symbol
     *
     * @param project Project
     * @param filePath File path
     * @param offset Cursor position
     * @return List of reference locations
     */
    fun findReferences(
        project: Project,
        filePath: String,
        offset: Int
    ): List<LocationInfo>

    /**
     * Get symbol info at the given position
     *
     * @param project Project
     * @param filePath File path
     * @param offset Cursor position
     * @return Symbol info, or null if no symbol at position
     */
    fun getSymbolInfo(
        project: Project,
        filePath: String,
        offset: Int
    ): SymbolInfo?

    /**
     * Get all symbols in a file
     *
     * @param project Project
     * @param filePath File path
     * @return File symbol structure
     */
    fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols

    /**
     * Get type hierarchy
     *
     * @param project Project
     * @param typeName Type name (can be qualified name)
     * @return Type hierarchy info, or null if not found
     */
    fun getTypeHierarchy(
        project: Project,
        typeName: String
    ): TypeHierarchy?

    /**
     * Convert line and column to offset
     */
    fun getOffset(
        project: Project,
        filePath: String,
        line: Int,
        column: Int
    ): Int?
}

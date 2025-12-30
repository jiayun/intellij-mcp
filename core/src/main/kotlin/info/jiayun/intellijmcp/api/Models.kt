package info.jiayun.intellijmcp.api

/**
 * Location information
 */
data class LocationInfo(
    val filePath: String,
    val line: Int,              // 0-based
    val column: Int,            // 0-based
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val preview: String? = null
)

/**
 * Project information
 */
data class ProjectInfo(
    val name: String,
    val basePath: String,
    val isActive: Boolean
)

/**
 * Symbol kind
 */
enum class SymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    FUNCTION,
    METHOD,
    PROPERTY,
    FIELD,
    VARIABLE,
    CONSTANT,
    PARAMETER,
    MODULE,
    PACKAGE
}

/**
 * Symbol information
 */
data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val language: String,           // "python", "java", "kotlin", etc.
    val qualifiedName: String? = null,
    val signature: String? = null,
    val documentation: String? = null,
    val location: LocationInfo? = null,
    val nameLocation: LocationInfo? = null, // Precise location of the symbol name (for find_references)
    val returnType: String? = null,
    val parameters: List<ParameterInfo>? = null,
    val modifiers: List<String>? = null,
    val decorators: List<String>? = null,  // Python decorators
    val annotations: List<String>? = null, // Java/Kotlin annotations
    val superTypes: List<String>? = null
)

data class ParameterInfo(
    val name: String,
    val type: String? = null,
    val defaultValue: String? = null,
    val isOptional: Boolean = false
)

/**
 * File symbols
 */
data class FileSymbols(
    val filePath: String,
    val language: String,
    val packageName: String? = null,
    val moduleName: String? = null,
    val imports: List<ImportInfo> = emptyList(),
    val symbols: List<SymbolNode> = emptyList()
)

data class ImportInfo(
    val module: String,
    val names: List<String>? = null,
    val alias: String? = null,
    val location: LocationInfo
)

/**
 * Symbol tree node (supports nested structure)
 */
data class SymbolNode(
    val symbol: SymbolInfo,
    val children: List<SymbolNode> = emptyList()
)

/**
 * Type hierarchy
 */
data class TypeHierarchy(
    val typeName: String,
    val qualifiedName: String?,
    val kind: SymbolKind,
    val superTypes: List<TypeRef> = emptyList(),
    val subTypes: List<TypeRef> = emptyList()
)

data class TypeRef(
    val name: String,
    val qualifiedName: String?,
    val location: LocationInfo?
)

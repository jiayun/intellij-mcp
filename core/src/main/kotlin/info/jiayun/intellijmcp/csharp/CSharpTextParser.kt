package info.jiayun.intellijmcp.csharp

/**
 * Text-based C# parser that extracts symbols from source files using regex patterns.
 *
 * This parser is used instead of PSI because Rider's C# PSI elements are backed by
 * ReSharper's .NET engine and are not accessible from a pure IntelliJ frontend plugin.
 * The text-based approach has no external dependencies and works immediately.
 */
class CSharpTextParser {

    data class ParsedFile(
        val usings: List<UsingDirective>,
        val namespaceName: String?,
        val symbols: List<ParsedSymbol>
    )

    data class UsingDirective(
        val module: String,
        val alias: String?,
        val line: Int,       // 1-based
        val column: Int      // 1-based
    )

    data class ParsedSymbol(
        val name: String,
        val kind: ParsedSymbolKind,
        val modifiers: List<String>,
        val attributes: List<String>,
        val signature: String?,
        val returnType: String?,
        val parameters: List<ParsedParameter>?,
        val baseTypes: List<String>,
        val documentation: String?,
        val line: Int,           // 1-based, line of the declaration keyword/name
        val column: Int,         // 1-based
        val nameColumn: Int,     // 1-based, column where the name starts
        val endLine: Int,        // 1-based, end of the symbol's block
        val endColumn: Int,      // 1-based
        val children: List<ParsedSymbol>
    )

    data class ParsedParameter(
        val name: String,
        val type: String,
        val defaultValue: String?,
        val isOptional: Boolean
    )

    enum class ParsedSymbolKind {
        NAMESPACE, CLASS, STRUCT, ENUM, INTERFACE, RECORD,
        METHOD, CONSTRUCTOR, PROPERTY, FIELD, EVENT, ENUM_MEMBER
    }

    companion object {
        private val MODIFIER_KEYWORDS = setOf(
            "public", "private", "protected", "internal",
            "static", "abstract", "virtual", "override",
            "async", "readonly", "sealed", "new", "partial",
            "extern", "volatile", "unsafe", "const"
        )

        private val TYPE_KEYWORDS = setOf("class", "struct", "enum", "interface", "record")

        // Regex for using directives
        private val USING_REGEX = Regex(
            """^\s*using\s+(?:static\s+)?(?:(\w+)\s*=\s*)?([^;]+)\s*;"""
        )

        // Regex for namespace (both block and file-scoped)
        private val NAMESPACE_REGEX = Regex(
            """^\s*namespace\s+([\w.]+)\s*[;{]"""
        )

        // Regex for type declarations
        private val TYPE_DECL_REGEX = Regex(
            """^(\s*(?:(?:${MODIFIER_KEYWORDS.joinToString("|")})\s+)*)""" +
            """(class|struct|enum|interface|record)\s+""" +
            """(\w+)""" +
            """(?:<[^>]+>)?""" +   // optional generic params
            """(?:\s*:\s*([^{;]+))?""" +  // optional base types
            """(?:\s*where\b[^{;]*)?"""   // optional generic constraints
        )

        // Regex for method/constructor declarations
        private val METHOD_REGEX = Regex(
            """^(\s*(?:(?:${MODIFIER_KEYWORDS.joinToString("|")})\s+)*)""" +
            """(?:(\S+(?:<[^>]+>)?)\s+)?""" +  // return type (optional for constructors)
            """(\w+)\s*""" +
            """(?:<[^>]+>)?\s*""" +  // optional generic params
            """\(([^)]*)\)\s*""" +   // parameters
            """(?::\s*(?:base|this)\s*\([^)]*\)\s*)?""" +  // optional base/this call
            """(?:\{|=>|;)"""        // body start
        )

        // Regex for property declarations (block body with { get; set; })
        private val PROPERTY_REGEX = Regex(
            """^(\s*(?:(?:${MODIFIER_KEYWORDS.joinToString("|")})\s+)*)""" +
            """(\S+(?:<[^>]+>)?(?:\?|\[\])?)\s+""" +  // type
            """(\w+)\s*""" +
            """\{"""                   // opening brace (property body)
        )

        // Regex for expression-bodied property: Type Name => expr;
        private val EXPR_PROPERTY_REGEX = Regex(
            """^(\s*(?:(?:${MODIFIER_KEYWORDS.joinToString("|")})\s+)*)""" +
            """(\S+(?:<[^>]+>)?(?:\?|\[\])?)\s+""" +  // type
            """(\w+)\s*""" +
            """=>\s*[^;]+;"""          // expression body ending with semicolon
        )

        // Regex for field/event declarations
        private val FIELD_REGEX = Regex(
            """^(\s*(?:(?:${MODIFIER_KEYWORDS.joinToString("|")})\s+)*)""" +
            """(event\s+)?""" +        // optional event keyword
            """(\S+(?:<[^>]+>)?(?:\?|\[\])?)\s+""" +  // type
            """(\w+)\s*""" +
            """(?:=\s*[^;]+)?;"""      // optional initializer, ending with semicolon
        )

        // Regex for XML doc comments
        private val DOC_COMMENT_REGEX = Regex("""^\s*///\s?(.*)""")

        // Regex for attribute
        private val ATTRIBUTE_REGEX = Regex("""^\s*\[([^\]]+)\]""")
    }

    fun parse(text: String): ParsedFile {
        val lines = text.lines()
        val usings = mutableListOf<UsingDirective>()
        var namespaceName: String? = null
        val topLevelSymbols = mutableListOf<ParsedSymbol>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Skip blank lines and comments (non-doc)
            if (trimmed.isEmpty() || trimmed.startsWith("//") && !trimmed.startsWith("///")) {
                i++
                continue
            }

            // Using directives
            val usingMatch = USING_REGEX.find(line)
            if (usingMatch != null) {
                val alias = usingMatch.groupValues[1].takeIf { it.isNotEmpty() }
                val module = usingMatch.groupValues[2].trim()
                usings.add(UsingDirective(
                    module = module,
                    alias = alias,
                    line = i + 1,
                    column = line.indexOf("using") + 1
                ))
                i++
                continue
            }

            // Namespace
            val nsMatch = NAMESPACE_REGEX.find(line)
            if (nsMatch != null) {
                namespaceName = nsMatch.groupValues[1]
                // If file-scoped namespace (ends with ;), just continue
                if (trimmed.endsWith(";")) {
                    i++
                    continue
                }
                // Block namespace - parse its contents
                val blockStart = i
                val blockEnd = findMatchingBrace(lines, i)
                val innerSymbols = parseBlock(lines, blockStart + 1, blockEnd - 1)
                topLevelSymbols.addAll(innerSymbols)
                i = blockEnd + 1
                continue
            }

            // Try to parse a type or member declaration
            val result = tryParseDeclaration(lines, i)
            if (result != null) {
                topLevelSymbols.add(result.first)
                i = result.second + 1
                continue
            }

            i++
        }

        return ParsedFile(
            usings = usings,
            namespaceName = namespaceName,
            symbols = topLevelSymbols
        )
    }

    /**
     * Parse symbols within a block (between braces).
     */
    private fun parseBlock(lines: List<String>, startLine: Int, endLine: Int): List<ParsedSymbol> {
        val symbols = mutableListOf<ParsedSymbol>()
        var i = startLine

        while (i <= endLine && i < lines.size) {
            val trimmed = lines[i].trim()

            if (trimmed.isEmpty() || (trimmed.startsWith("//") && !trimmed.startsWith("///"))) {
                i++
                continue
            }

            val result = tryParseDeclaration(lines, i)
            if (result != null) {
                symbols.add(result.first)
                i = result.second + 1
                continue
            }

            i++
        }

        return symbols
    }

    /**
     * Try to parse a declaration starting at the given line.
     * Returns (ParsedSymbol, lastLineIndex) or null if not a recognized declaration.
     */
    private fun tryParseDeclaration(lines: List<String>, startLine: Int): Pair<ParsedSymbol, Int>? {
        // Collect doc comments and attributes above the declaration
        val docLines = mutableListOf<String>()
        val attributes = mutableListOf<String>()

        var declLine = startLine

        // Scan forward: collect doc comments and attributes before the declaration
        while (declLine < lines.size) {
            val trimmed = lines[declLine].trim()
            val docMatch = DOC_COMMENT_REGEX.find(trimmed)

            when {
                docMatch != null -> {
                    docLines.add(docMatch.groupValues[1])
                    declLine++
                }
                trimmed.isEmpty() -> {
                    declLine++
                }
                else -> {
                    // Collect any attributes, including inline ones (e.g., "[SerializeField] private int x;")
                    // Attributes may appear at the start of the line before the declaration
                    var remaining = trimmed
                    while (true) {
                        val attrMatch = ATTRIBUTE_REGEX.find(remaining) ?: break
                        attributes.add("[${attrMatch.groupValues[1]}]")
                        remaining = remaining.substring(attrMatch.range.last + 1).trim()
                        if (remaining.isEmpty()) {
                            // Attribute was the entire line, advance to next line
                            declLine++
                            break
                        }
                    }
                    break
                }
            }
        }

        if (declLine >= lines.size) return null

        val line = lines[declLine]
        val trimmed = line.trim()

        // Skip preprocessor directives
        if (trimmed.startsWith("#")) return Pair(createSkipSymbol(declLine), declLine)

        // Build a multi-line declaration string for matching (join continuation lines)
        // Strip any leading inline attributes so regex patterns can match the declaration itself
        val declText = stripLeadingAttributes(buildDeclText(lines, declLine))
        val documentation = docLines.joinToString("\n").takeIf { it.isNotEmpty() }

        // Try type declaration
        val typeResult = tryParseType(lines, declLine, declText, attributes, documentation)
        if (typeResult != null) return typeResult

        // Try method/constructor
        val methodResult = tryParseMethod(lines, declLine, declText, attributes, documentation)
        if (methodResult != null) return methodResult

        // Try property — block body (must come before field since both start with type + name)
        val propResult = tryParseProperty(lines, declLine, declText, attributes, documentation)
        if (propResult != null) return propResult

        // Try expression-bodied property (Type Name => expr;)
        val exprPropResult = tryParseExprProperty(lines, declLine, declText, attributes, documentation)
        if (exprPropResult != null) return exprPropResult

        // Try field/event
        val fieldResult = tryParseField(lines, declLine, declText, attributes, documentation)
        if (fieldResult != null) return fieldResult

        // Try enum member (simple identifier, possibly with value, ending with comma)
        val enumMemberResult = tryParseEnumMember(lines, declLine, trimmed, attributes, documentation)
        if (enumMemberResult != null) return enumMemberResult

        // If we collected doc/attributes but couldn't parse, still advance past them
        if (declLine > startLine) {
            return null  // Let caller advance by 1
        }

        return null
    }

    private fun createSkipSymbol(line: Int): ParsedSymbol {
        return ParsedSymbol(
            name = "",
            kind = ParsedSymbolKind.FIELD,
            modifiers = emptyList(),
            attributes = emptyList(),
            signature = null,
            returnType = null,
            parameters = null,
            baseTypes = emptyList(),
            documentation = null,
            line = line + 1,
            column = 1,
            nameColumn = 1,
            endLine = line + 1,
            endColumn = 1,
            children = emptyList()
        )
    }

    private fun tryParseType(
        lines: List<String>,
        declLine: Int,
        declText: String,
        attributes: List<String>,
        documentation: String?
    ): Pair<ParsedSymbol, Int>? {
        val match = TYPE_DECL_REGEX.find(declText) ?: return null

        val modifierStr = match.groupValues[1].trim()
        val typeKeyword = match.groupValues[2]
        val name = match.groupValues[3]
        val baseTypesStr = match.groupValues[4].trim()

        val modifiers = extractModifiers(modifierStr)
        val baseTypes = if (baseTypesStr.isNotEmpty()) {
            baseTypesStr.split(",").map { it.trim().split("<").first().trim() }.filter { it.isNotEmpty() }
        } else emptyList()

        val kind = when (typeKeyword) {
            "class" -> ParsedSymbolKind.CLASS
            "struct" -> ParsedSymbolKind.STRUCT
            "enum" -> ParsedSymbolKind.ENUM
            "interface" -> ParsedSymbolKind.INTERFACE
            "record" -> ParsedSymbolKind.RECORD
            else -> ParsedSymbolKind.CLASS
        }

        val line = lines[declLine]
        val nameCol = line.indexOf(name, line.indexOf(typeKeyword)) + 1

        // Find the block end
        val blockEnd = findMatchingBrace(lines, declLine)
        val children = if (blockEnd > declLine) {
            parseBlock(lines, declLine + 1, blockEnd - 1)
        } else emptyList()

        // Build signature (first line of declaration)
        val sigEnd = declText.indexOf('{').let { if (it >= 0) it else declText.length }
        val signature = declText.substring(0, sigEnd).trim()
            .replace(Regex("""\s+"""), " ")

        val symbol = ParsedSymbol(
            name = name,
            kind = kind,
            modifiers = modifiers,
            attributes = attributes,
            signature = signature,
            returnType = null,
            parameters = null,
            baseTypes = baseTypes,
            documentation = documentation,
            line = declLine + 1,
            column = (line.length - line.trimStart().length) + 1,
            nameColumn = nameCol,
            endLine = blockEnd + 1,
            endColumn = if (blockEnd < lines.size) lines[blockEnd].length + 1 else 1,
            children = children.filter { it.name.isNotEmpty() }
        )

        return Pair(symbol, blockEnd)
    }

    private fun tryParseMethod(
        lines: List<String>,
        declLine: Int,
        declText: String,
        attributes: List<String>,
        documentation: String?
    ): Pair<ParsedSymbol, Int>? {
        val match = METHOD_REGEX.find(declText) ?: return null

        val modifierStr = match.groupValues[1].trim()
        val returnType = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
        val name = match.groupValues[3]
        val paramStr = match.groupValues[4].trim()

        // Skip if name is a C# keyword (likely a control flow statement)
        if (name in setOf("if", "else", "for", "foreach", "while", "do", "switch", "try", "catch", "finally",
                "using", "lock", "return", "throw", "yield", "await", "new", "typeof", "sizeof", "nameof")) {
            return null
        }

        val modifiers = extractModifiers(modifierStr)
        val parameters = parseParameters(paramStr)
        val isConstructor = returnType == null || name == returnType

        val kind = if (isConstructor) ParsedSymbolKind.CONSTRUCTOR else ParsedSymbolKind.METHOD

        val line = lines[declLine]
        val nameCol = line.indexOf(name) + 1

        // Find end: either matching brace or semicolon (for abstract/extern methods)
        val endLine = if (declText.trimEnd().endsWith(";")) {
            declLine
        } else {
            findMatchingBrace(lines, declLine)
        }

        val sigEnd = declText.indexOf('{').let {
            if (it >= 0) it else declText.indexOf("=>").let { a ->
                if (a >= 0) a else declText.indexOf(';').let { b ->
                    if (b >= 0) b else declText.length
                }
            }
        }
        val signature = declText.substring(0, sigEnd).trim().replace(Regex("""\s+"""), " ")

        val symbol = ParsedSymbol(
            name = name,
            kind = kind,
            modifiers = modifiers,
            attributes = attributes,
            signature = signature,
            returnType = if (isConstructor) null else returnType,
            parameters = parameters,
            baseTypes = emptyList(),
            documentation = documentation,
            line = declLine + 1,
            column = (line.length - line.trimStart().length) + 1,
            nameColumn = nameCol,
            endLine = endLine + 1,
            endColumn = if (endLine < lines.size) lines[endLine].length + 1 else 1,
            children = emptyList()
        )

        return Pair(symbol, endLine)
    }

    private fun tryParseProperty(
        lines: List<String>,
        declLine: Int,
        declText: String,
        attributes: List<String>,
        documentation: String?
    ): Pair<ParsedSymbol, Int>? {
        val match = PROPERTY_REGEX.find(declText) ?: return null

        // Verify it's actually a property (has get/set inside braces)
        val braceStart = declText.indexOf('{')
        if (braceStart < 0) return null

        // Quick check: look for get/set within a reasonable range
        val afterBrace = declText.substring(braceStart)
        if (!afterBrace.contains("get") && !afterBrace.contains("set") &&
            !afterBrace.contains("init") && !afterBrace.contains("=>")) {
            return null
        }

        val modifierStr = match.groupValues[1].trim()
        val type = match.groupValues[2].trim()
        val name = match.groupValues[3]

        // Skip if type looks like a keyword
        if (type in TYPE_KEYWORDS || type in setOf("return", "if", "else", "new")) return null

        val modifiers = extractModifiers(modifierStr)

        val line = lines[declLine]
        val nameCol = line.indexOf(name) + 1

        val endLine = findMatchingBrace(lines, declLine)

        val sigEnd = declText.indexOf('{')
        val signature = declText.substring(0, sigEnd).trim().replace(Regex("""\s+"""), " ")

        val symbol = ParsedSymbol(
            name = name,
            kind = ParsedSymbolKind.PROPERTY,
            modifiers = modifiers,
            attributes = attributes,
            signature = signature,
            returnType = type,
            parameters = null,
            baseTypes = emptyList(),
            documentation = documentation,
            line = declLine + 1,
            column = (line.length - line.trimStart().length) + 1,
            nameColumn = nameCol,
            endLine = endLine + 1,
            endColumn = if (endLine < lines.size) lines[endLine].length + 1 else 1,
            children = emptyList()
        )

        return Pair(symbol, endLine)
    }

    private fun tryParseExprProperty(
        lines: List<String>,
        declLine: Int,
        declText: String,
        attributes: List<String>,
        documentation: String?
    ): Pair<ParsedSymbol, Int>? {
        val match = EXPR_PROPERTY_REGEX.find(declText) ?: return null

        val modifierStr = match.groupValues[1].trim()
        val type = match.groupValues[2].trim()
        val name = match.groupValues[3]

        if (type in TYPE_KEYWORDS || type in setOf("return", "if", "else", "new")) return null

        val modifiers = extractModifiers(modifierStr)

        val line = lines[declLine]
        val nameCol = line.indexOf(name) + 1
        val endLine = findSemicolon(lines, declLine)

        val signature = declText.substringBefore(';').trim().replace(Regex("""\s+"""), " ") + ";"

        val symbol = ParsedSymbol(
            name = name,
            kind = ParsedSymbolKind.PROPERTY,
            modifiers = modifiers,
            attributes = attributes,
            signature = signature,
            returnType = type,
            parameters = null,
            baseTypes = emptyList(),
            documentation = documentation,
            line = declLine + 1,
            column = (line.length - line.trimStart().length) + 1,
            nameColumn = nameCol,
            endLine = endLine + 1,
            endColumn = if (endLine < lines.size) lines[endLine].length + 1 else 1,
            children = emptyList()
        )

        return Pair(symbol, endLine)
    }

    private fun tryParseField(
        lines: List<String>,
        declLine: Int,
        declText: String,
        attributes: List<String>,
        documentation: String?
    ): Pair<ParsedSymbol, Int>? {
        val match = FIELD_REGEX.find(declText) ?: return null

        val modifierStr = match.groupValues[1].trim()
        val eventKeyword = match.groupValues[2].trim()
        val type = match.groupValues[3].trim()
        val name = match.groupValues[4]

        // Skip if type looks like a keyword
        if (type in TYPE_KEYWORDS || type in setOf("return", "if", "else", "new", "using", "namespace")) return null

        val modifiers = extractModifiers(modifierStr)
        val isEvent = eventKeyword.isNotEmpty()

        val kind = when {
            isEvent -> ParsedSymbolKind.EVENT
            else -> ParsedSymbolKind.FIELD
        }

        val line = lines[declLine]
        val nameCol = line.indexOf(name) + 1

        // Fields end at the semicolon on this line (or nearby)
        val endLine = findSemicolon(lines, declLine)

        val signature = declText.substringBefore(';').trim().replace(Regex("""\s+"""), " ") + ";"

        val symbol = ParsedSymbol(
            name = name,
            kind = kind,
            modifiers = modifiers,
            attributes = attributes,
            signature = signature,
            returnType = type,
            parameters = null,
            baseTypes = emptyList(),
            documentation = documentation,
            line = declLine + 1,
            column = (line.length - line.trimStart().length) + 1,
            nameColumn = nameCol,
            endLine = endLine + 1,
            endColumn = if (endLine < lines.size) lines[endLine].length + 1 else 1,
            children = emptyList()
        )

        return Pair(symbol, endLine)
    }

    private fun tryParseEnumMember(
        lines: List<String>,
        declLine: Int,
        trimmed: String,
        attributes: List<String>,
        documentation: String?
    ): Pair<ParsedSymbol, Int>? {
        // Enum members: Name, or Name = value,
        val match = Regex("""^(\w+)\s*(?:=\s*[^,}]+)?\s*[,}]?$""").find(trimmed) ?: return null
        val name = match.groupValues[1]

        // Skip if it looks like a keyword
        if (name in MODIFIER_KEYWORDS || name in TYPE_KEYWORDS || name in setOf(
                "return", "if", "else", "new", "get", "set", "value")) return null

        val line = lines[declLine]
        val nameCol = line.indexOf(name) + 1

        val symbol = ParsedSymbol(
            name = name,
            kind = ParsedSymbolKind.ENUM_MEMBER,
            modifiers = emptyList(),
            attributes = attributes,
            signature = trimmed.trimEnd(',').trim(),
            returnType = null,
            parameters = null,
            baseTypes = emptyList(),
            documentation = documentation,
            line = declLine + 1,
            column = (line.length - line.trimStart().length) + 1,
            nameColumn = nameCol,
            endLine = declLine + 1,
            endColumn = line.length + 1,
            children = emptyList()
        )

        return Pair(symbol, declLine)
    }

    private fun extractModifiers(text: String): List<String> {
        return text.split(Regex("""\s+""")).filter { it in MODIFIER_KEYWORDS }
    }

    private fun parseParameters(paramStr: String): List<ParsedParameter> {
        if (paramStr.isBlank()) return emptyList()

        return paramStr.split(",").mapNotNull { param ->
            val p = param.trim()
            if (p.isEmpty()) return@mapNotNull null

            // Handle params keyword, ref, out, in
            val cleaned = p.replace(Regex("""^(params|ref|out|in|this)\s+"""), "")

            val parts = cleaned.split(Regex("""\s+"""))
            if (parts.size >= 2) {
                val defaultMatch = Regex("""=\s*(.+)$""").find(cleaned)
                val namePart = parts.last().split("=").first().trim()
                val typePart = parts.dropLast(1).joinToString(" ").split("=").first().trim()

                ParsedParameter(
                    name = namePart,
                    type = typePart,
                    defaultValue = defaultMatch?.groupValues?.get(1)?.trim(),
                    isOptional = defaultMatch != null
                )
            } else null
        }
    }

    /**
     * Strip leading attribute annotations from a declaration string.
     * E.g., "[SerializeField] private int x;" -> "private int x;"
     */
    private fun stripLeadingAttributes(text: String): String {
        var result = text.trimStart()
        while (result.startsWith("[")) {
            val closeBracket = result.indexOf(']')
            if (closeBracket < 0) break
            result = result.substring(closeBracket + 1).trimStart()
        }
        return result
    }

    /**
     * Build a multi-line declaration string by joining lines until we hit a brace, semicolon, or arrow.
     */
    private fun buildDeclText(lines: List<String>, startLine: Int): String {
        val sb = StringBuilder()
        var i = startLine
        var braceDepth = 0
        while (i < lines.size && i < startLine + 10) {  // Look at most 10 lines
            val line = lines[i]
            sb.append(if (i == startLine) line else " $line")

            for (ch in line) {
                when (ch) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                }
            }

            val current = sb.toString().trim()
            if (current.contains('{') || current.endsWith(";") || current.contains("=>")) {
                break
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Find the line index of the matching closing brace for an opening brace
     * starting at or after the given line.
     */
    private fun findMatchingBrace(lines: List<String>, startLine: Int): Int {
        var depth = 0
        var foundOpen = false
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var inVerbatimString = false

        for (i in startLine until lines.size) {
            val line = lines[i]
            inLineComment = false
            var j = 0
            while (j < line.length) {
                val ch = line[j]
                val next = if (j + 1 < line.length) line[j + 1] else '\u0000'

                when {
                    inBlockComment -> {
                        if (ch == '*' && next == '/') {
                            inBlockComment = false
                            j++
                        }
                    }
                    inLineComment -> { /* skip rest of line */ }
                    inVerbatimString -> {
                        if (ch == '"' && next == '"') {
                            j++  // skip escaped quote
                        } else if (ch == '"') {
                            inVerbatimString = false
                        }
                    }
                    inString -> {
                        if (ch == '\\') j++  // skip escape
                        else if (ch == '"') inString = false
                    }
                    inChar -> {
                        if (ch == '\\') j++
                        else if (ch == '\'') inChar = false
                    }
                    ch == '/' && next == '/' -> { inLineComment = true }
                    ch == '/' && next == '*' -> { inBlockComment = true; j++ }
                    ch == '@' && next == '"' -> { inVerbatimString = true; j++ }
                    ch == '"' -> { inString = true }
                    ch == '\'' -> { inChar = true }
                    ch == '{' -> { depth++; foundOpen = true }
                    ch == '}' -> {
                        depth--
                        if (foundOpen && depth == 0) return i
                    }
                }
                j++
            }
        }

        return lines.size - 1
    }

    /**
     * Find the line containing the semicolon that ends a statement.
     */
    private fun findSemicolon(lines: List<String>, startLine: Int): Int {
        for (i in startLine until minOf(startLine + 5, lines.size)) {
            if (lines[i].contains(';')) return i
        }
        return startLine
    }

    /**
     * Find the symbol at a given 1-based line and column.
     */
    fun findSymbolAt(parsedFile: ParsedFile, line: Int, column: Int): ParsedSymbol? {
        return findSymbolAtRecursive(parsedFile.symbols, line, column)
    }

    private fun findSymbolAtRecursive(symbols: List<ParsedSymbol>, line: Int, column: Int): ParsedSymbol? {
        // Find the most specific (deepest) symbol containing the position
        for (symbol in symbols) {
            if (line >= symbol.line && line <= symbol.endLine) {
                // Check children first for more specific match
                val childMatch = findSymbolAtRecursive(symbol.children, line, column)
                if (childMatch != null) return childMatch
                return symbol
            }
        }
        return null
    }

    /**
     * Find all symbols with a given name.
     */
    fun findSymbolsByName(parsedFile: ParsedFile, name: String): List<ParsedSymbol> {
        val results = mutableListOf<ParsedSymbol>()
        collectByName(parsedFile.symbols, name, results)
        return results
    }

    private fun collectByName(symbols: List<ParsedSymbol>, name: String, results: MutableList<ParsedSymbol>) {
        for (symbol in symbols) {
            if (symbol.name == name) results.add(symbol)
            collectByName(symbol.children, name, results)
        }
    }
}
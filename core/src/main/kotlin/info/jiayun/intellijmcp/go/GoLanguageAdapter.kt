package info.jiayun.intellijmcp.go

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import info.jiayun.intellijmcp.api.*

// Go PSI API imports
import com.goide.psi.*

class GoLanguageAdapter : LanguageAdapter {

    override val languageId = "go"
    override val languageDisplayName = "Go"
    override val supportedExtensions = setOf("go")

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?,
        includeLibraries: Boolean
    ): List<SymbolInfo> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        FilenameIndex.getAllFilesByExt(project, "go", scope).forEach { virtualFile ->
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? GoFile ?: return@forEach

            PsiTreeUtil.findChildrenOfType(psiFile, GoNamedElement::class.java)
                .filter { it.name == name }
                .forEach { element ->
                    val symbolKind = getSymbolKind(element)
                    if (kind == null || kind == symbolKind) {
                        buildSymbolInfo(project, element, symbolKind)?.let { results.add(it) }
                    }
                }
        }

        return results
    }

    private fun getSymbolKind(element: GoNamedElement): SymbolKind {
        return when (element) {
            is GoTypeSpec -> {
                val type = element.specType?.type
                when (type) {
                    is GoInterfaceType -> SymbolKind.INTERFACE
                    else -> SymbolKind.CLASS
                }
            }
            is GoFunctionDeclaration -> SymbolKind.FUNCTION
            is GoMethodDeclaration -> SymbolKind.METHOD
            is GoFieldDefinition -> SymbolKind.FIELD
            is GoConstDefinition -> SymbolKind.CONSTANT
            is GoVarDefinition -> SymbolKind.VARIABLE
            else -> SymbolKind.VARIABLE
        }
    }

    // ===== Find References =====

    override fun findReferences(
        project: Project,
        filePath: String,
        offset: Int,
        includeLibraries: Boolean
    ): List<LocationInfo> {
        val goFile = getGoFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = goFile.findElementAt(offset)
            ?: throw IllegalArgumentException("No element at offset")

        val targetElement = findMeaningfulElement(element)
            ?: throw IllegalArgumentException("No symbol at position")

        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        return ReferencesSearch.search(targetElement, scope)
            .findAll()
            .mapNotNull { ref -> getLocation(project, ref.element) }
    }

    // ===== Get Symbol Info =====

    override fun getSymbolInfo(
        project: Project,
        filePath: String,
        offset: Int
    ): SymbolInfo? {
        val goFile = getGoFile(project, filePath) ?: return null
        val element = goFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        return when (targetElement) {
            is GoTypeSpec -> {
                val kind = when (targetElement.specType?.type) {
                    is GoInterfaceType -> SymbolKind.INTERFACE
                    else -> SymbolKind.CLASS
                }
                buildSymbolInfo(project, targetElement, kind)
            }
            is GoFunctionDeclaration -> buildSymbolInfo(project, targetElement, SymbolKind.FUNCTION)
            is GoMethodDeclaration -> buildSymbolInfo(project, targetElement, SymbolKind.METHOD)
            is GoFieldDefinition -> buildSymbolInfo(project, targetElement, SymbolKind.FIELD)
            is GoConstDefinition -> buildSymbolInfo(project, targetElement, SymbolKind.CONSTANT)
            is GoVarDefinition -> buildSymbolInfo(project, targetElement, SymbolKind.VARIABLE)
            else -> null
        }
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val goFile = getGoFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()

        // Package name
        val packageName = goFile.packageName

        // Extract imports
        goFile.imports.forEach { importSpec ->
            getLocation(project, importSpec)?.let { loc ->
                imports.add(ImportInfo(
                    module = importSpec.path,
                    names = null,
                    alias = importSpec.alias?.let { if (it == ".") null else it },
                    location = loc
                ))
            }
        }

        // Extract top-level declarations
        goFile.children.forEach { child ->
            when (child) {
                is GoTypeDeclaration -> {
                    child.typeSpecList.forEach { typeSpec ->
                        buildTypeNode(project, typeSpec)?.let { symbols.add(it) }
                    }
                }
                is GoFunctionDeclaration -> {
                    buildSymbolInfo(project, child, SymbolKind.FUNCTION)?.let {
                        symbols.add(SymbolNode(it))
                    }
                }
                is GoMethodDeclaration -> {
                    buildSymbolInfo(project, child, SymbolKind.METHOD)?.let {
                        symbols.add(SymbolNode(it))
                    }
                }
                is GoConstDeclaration -> {
                    child.constSpecList.forEach { spec ->
                        spec.constDefinitionList.forEach { constDef ->
                            buildSymbolInfo(project, constDef, SymbolKind.CONSTANT)?.let {
                                symbols.add(SymbolNode(it))
                            }
                        }
                    }
                }
                is GoVarDeclaration -> {
                    child.varSpecList.forEach { spec ->
                        spec.varDefinitionList.forEach { varDef ->
                            buildSymbolInfo(project, varDef, SymbolKind.VARIABLE)?.let {
                                symbols.add(SymbolNode(it))
                            }
                        }
                    }
                }
            }
        }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = packageName,
            imports = imports,
            symbols = symbols
        )
    }

    // ===== Get Type Hierarchy =====

    override fun getTypeHierarchy(
        project: Project,
        typeName: String,
        includeLibraries: Boolean
    ): TypeHierarchy? {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)

        var targetType: GoTypeSpec? = null

        FilenameIndex.getAllFilesByExt(project, "go", scope).forEach fileLoop@{ virtualFile ->
            if (targetType != null) return@fileLoop
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? GoFile ?: return@fileLoop

            PsiTreeUtil.findChildrenOfType(psiFile, GoTypeSpec::class.java)
                .find { it.name == typeName }
                ?.let { targetType = it }
        }

        val target = targetType ?: return null

        val specType = target.specType?.type
        val kind = when (specType) {
            is GoInterfaceType -> SymbolKind.INTERFACE
            else -> SymbolKind.CLASS
        }

        // Use DefinitionsScopedSearch — Go plugin registers executors that handle:
        // - GoInheritorsSearch: interface → implementing structs (sub types)
        // - GoSuperSearch: struct → implemented interfaces (super types)
        val definitions = DefinitionsScopedSearch.search(target, scope).findAll()
            .filterIsInstance<GoTypeSpec>()

        val superTypes = mutableListOf<TypeRef>()
        val subTypes = mutableListOf<TypeRef>()

        when (specType) {
            is GoInterfaceType -> {
                // Embedded interfaces = super types
                specType.baseTypesReferences.forEach { typeRef ->
                    val resolved = typeRef.reference?.resolve() as? GoTypeSpec
                    if (resolved != null) {
                        superTypes.add(TypeRef(
                            name = resolved.name ?: "",
                            qualifiedName = resolved.qualifiedName,
                            location = getLocation(project, resolved)
                        ))
                    }
                }
                // DefinitionsScopedSearch results = implementing structs (sub types)
                definitions.forEach { sub ->
                    val name = sub.name ?: return@forEach
                    subTypes.add(TypeRef(
                        name = name,
                        qualifiedName = sub.qualifiedName,
                        location = getLocation(project, sub)
                    ))
                }
            }
            is GoStructType -> {
                // Embedded structs = super types (embedding)
                specType.fieldDeclarationList.forEach { fieldDecl ->
                    val anonField = fieldDecl.anonymousFieldDefinition
                    if (anonField != null) {
                        val typeRefExpr = anonField.typeReferenceExpression
                        val resolved = typeRefExpr?.reference?.resolve() as? GoTypeSpec
                        if (resolved != null) {
                            superTypes.add(TypeRef(
                                name = resolved.name ?: "",
                                qualifiedName = resolved.qualifiedName,
                                location = getLocation(project, resolved)
                            ))
                        }
                    }
                }
                // Reverse lookup: find all project interfaces and check if this struct implements them
                // DefinitionsScopedSearch only works interface→implementors, so we check each interface
                FilenameIndex.getAllFilesByExt(project, "go", scope).forEach { virtualFile ->
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? GoFile ?: return@forEach
                    PsiTreeUtil.findChildrenOfType(psiFile, GoTypeSpec::class.java)
                        .filter { it.specType?.type is GoInterfaceType }
                        .forEach { iface ->
                            val implementors = DefinitionsScopedSearch.search(iface, scope).findAll()
                                .filterIsInstance<GoTypeSpec>()
                            if (implementors.any { it.name == target.name && it.qualifiedName == target.qualifiedName }) {
                                val name = iface.name ?: return@forEach
                                superTypes.add(TypeRef(
                                    name = name,
                                    qualifiedName = iface.qualifiedName,
                                    location = getLocation(project, iface)
                                ))
                            }
                        }
                }
            }
        }

        return TypeHierarchy(
            typeName = target.name ?: "",
            qualifiedName = target.qualifiedName,
            kind = kind,
            superTypes = superTypes,
            subTypes = subTypes
        )
    }

    // ===== Get Offset =====

    override fun getOffset(
        project: Project,
        filePath: String,
        line: Int,
        column: Int
    ): Int? {
        val goFile = getGoFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(goFile) ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getGoFile(project: Project, filePath: String): GoFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? GoFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is GoTypeSpec,
                is GoFunctionDeclaration,
                is GoMethodDeclaration,
                is GoFieldDefinition,
                is GoConstDefinition,
                is GoVarDefinition,
                is GoNamedElement -> return current
            }
            current = current.parent
        }
        return null
    }

    private fun getLocation(project: Project, element: PsiElement): LocationInfo? {
        val file = element.containingFile?.virtualFile?.path ?: return null
        val document = PsiDocumentManager.getInstance(project)
            .getDocument(element.containingFile) ?: return null

        val startOffset = element.textRange.startOffset
        val endOffset = element.textRange.endOffset

        val startLine = document.getLineNumber(startOffset)
        val startColumn = startOffset - document.getLineStartOffset(startLine)
        val endLine = document.getLineNumber(endOffset)
        val endColumn = endOffset - document.getLineStartOffset(endLine)

        val preview = element.text.take(100).let {
            if (element.text.length > 100) "$it..." else it
        }

        return LocationInfo(
            filePath = file,
            line = startLine,
            column = startColumn,
            endLine = endLine,
            endColumn = endColumn,
            preview = preview
        )
    }

    private fun buildSymbolInfo(project: Project, element: GoNamedElement, kind: SymbolKind): SymbolInfo? {
        val name = element.name ?: return null

        return SymbolInfo(
            name = name,
            kind = kind,
            language = languageId,
            qualifiedName = element.qualifiedName,
            signature = buildSignature(element),
            documentation = getDocumentation(element),
            location = getLocation(project, element),
            nameLocation = element.identifier?.let { getLocation(project, it) },
            returnType = getReturnType(element),
            parameters = getParameters(element),
            modifiers = getModifiers(element),
            annotations = null
        )
    }

    private fun buildSignature(element: GoNamedElement): String? {
        return when (element) {
            is GoFunctionDeclaration -> {
                val name = element.name ?: ""
                val params = element.signature?.parameters?.text ?: "()"
                val result = element.signature?.resultType?.text
                    ?: element.signature?.result?.text?.let { " $it" }
                    ?: ""
                "func $name$params$result"
            }
            is GoMethodDeclaration -> {
                val receiver = element.receiver?.text ?: ""
                val name = element.name ?: ""
                val params = element.signature?.parameters?.text ?: "()"
                val result = element.signature?.resultType?.text
                    ?: element.signature?.result?.text?.let { " $it" }
                    ?: ""
                "func ($receiver) $name$params$result"
            }
            is GoTypeSpec -> {
                val typeName = element.name ?: ""
                val type = element.specType?.type
                when (type) {
                    is GoStructType -> "type $typeName struct"
                    is GoInterfaceType -> "type $typeName interface"
                    else -> "type $typeName ${type?.text?.take(50) ?: ""}"
                }
            }
            is GoConstDefinition -> {
                val name = element.name ?: ""
                val type = element.findSiblingType()?.text?.let { " $it" } ?: ""
                "const $name$type"
            }
            is GoVarDefinition -> {
                val name = element.name ?: ""
                val type = element.findSiblingType()?.text?.let { " $it" } ?: ""
                "var $name$type"
            }
            is GoFieldDefinition -> {
                val name = element.name ?: ""
                val type = element.findSiblingType()?.text?.let { " $it" } ?: ""
                "$name$type"
            }
            else -> null
        }
    }

    private fun getReturnType(element: GoNamedElement): String? {
        return when (element) {
            is GoFunctionDeclaration -> element.signature?.resultType?.text
                ?: element.signature?.result?.text
            is GoMethodDeclaration -> element.signature?.resultType?.text
                ?: element.signature?.result?.text
            is GoConstDefinition -> element.findSiblingType()?.text
            is GoVarDefinition -> element.findSiblingType()?.text
            is GoFieldDefinition -> element.findSiblingType()?.text
            else -> null
        }
    }

    private fun getParameters(element: GoNamedElement): List<ParameterInfo>? {
        val signature = when (element) {
            is GoFunctionDeclaration -> element.signature
            is GoMethodDeclaration -> element.signature
            else -> null
        } ?: return null

        return signature.parameters?.parameterDeclarationList?.flatMap { paramDecl ->
            val type = paramDecl.type?.text
            val isVariadic = paramDecl.isVariadic
            val paramNames = paramDecl.paramDefinitionList
            if (paramNames.isEmpty()) {
                listOf(ParameterInfo(
                    name = "",
                    type = if (isVariadic) "...$type" else type,
                    defaultValue = null,
                    isOptional = false
                ))
            } else {
                paramNames.map { param ->
                    ParameterInfo(
                        name = param.name ?: "",
                        type = if (isVariadic) "...$type" else type,
                        defaultValue = null,
                        isOptional = false
                    )
                }
            }
        }
    }

    private fun getModifiers(element: GoNamedElement): List<String>? {
        val modifiers = mutableListOf<String>()

        if (element.isPublic) {
            modifiers.add("exported")
        }

        return modifiers.takeIf { it.isNotEmpty() }
    }

    private fun getDocumentation(element: PsiElement): String? {
        val docLines = mutableListOf<String>()
        var sibling = element.prevSibling
        while (sibling != null) {
            val text = sibling.text
            when {
                text.startsWith("//") -> docLines.add(0, text.removePrefix("//").trim())
                text.isBlank() || sibling is com.intellij.psi.PsiWhiteSpace -> {
                    if (docLines.isNotEmpty()) break
                }
                else -> break
            }
            sibling = sibling.prevSibling
        }
        return docLines.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun buildTypeNode(project: Project, typeSpec: GoTypeSpec): SymbolNode? {
        val type = typeSpec.specType?.type
        val kind = when (type) {
            is GoInterfaceType -> SymbolKind.INTERFACE
            else -> SymbolKind.CLASS
        }
        val info = buildSymbolInfo(project, typeSpec, kind) ?: return null
        val children = mutableListOf<SymbolNode>()

        when (type) {
            is GoStructType -> {
                type.fieldDeclarationList.forEach { fieldDecl ->
                    fieldDecl.fieldDefinitionList.forEach { field ->
                        buildSymbolInfo(project, field, SymbolKind.FIELD)?.let {
                            children.add(SymbolNode(it))
                        }
                    }
                }
            }
            is GoInterfaceType -> {
                type.methodSpecList.forEach { methodSpec ->
                    val methodName = methodSpec.name ?: return@forEach
                    val sig = methodSpec.signature
                    val params = sig?.parameters?.text ?: "()"
                    val result = sig?.resultType?.text
                        ?: sig?.result?.text?.let { " $it" }
                        ?: ""
                    val methodInfo = SymbolInfo(
                        name = methodName,
                        kind = SymbolKind.METHOD,
                        language = languageId,
                        signature = "func $methodName$params$result",
                        location = getLocation(project, methodSpec)
                    )
                    children.add(SymbolNode(methodInfo))
                }
            }
        }

        return SymbolNode(info, children)
    }
}

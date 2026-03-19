package info.jiayun.intellijmcp.dart

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import info.jiayun.intellijmcp.api.*

// Dart PSI API imports
import com.jetbrains.lang.dart.DartTokenTypes
import com.jetbrains.lang.dart.psi.*
import com.jetbrains.lang.dart.ide.index.DartComponentIndex

class DartLanguageAdapter : LanguageAdapter {

    override val languageId = "dart"
    override val languageDisplayName = "Dart"
    override val supportedExtensions = setOf("dart")

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        val files = DartComponentIndex.getAllFiles(name, scope)
        files.forEach { virtualFile ->
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? DartFile ?: return@forEach

            PsiTreeUtil.findChildrenOfType(psiFile, DartComponent::class.java)
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

    private fun getSymbolKind(element: PsiElement): SymbolKind {
        return when (element) {
            is DartEnumDefinition -> SymbolKind.ENUM
            is DartMixinDeclaration -> SymbolKind.TRAIT
            is DartClassDefinition -> SymbolKind.CLASS
            is DartExtensionDeclaration -> SymbolKind.CLASS
            is DartExtensionTypeDeclaration -> SymbolKind.CLASS
            is DartFunctionDeclarationWithBody -> SymbolKind.FUNCTION
            is DartMethodDeclaration -> SymbolKind.METHOD
            is DartGetterDeclaration -> SymbolKind.PROPERTY
            is DartSetterDeclaration -> SymbolKind.PROPERTY
            is DartFactoryConstructorDeclaration -> SymbolKind.METHOD
            is DartNamedConstructorDeclaration -> SymbolKind.METHOD
            is DartEnumConstantDeclaration -> SymbolKind.CONSTANT
            is DartFunctionTypeAlias -> SymbolKind.CLASS
            is DartVarAccessDeclaration -> {
                val parent = element.parent
                if (parent is DartVarDeclarationList && parent.parent is DartClassMembers) {
                    SymbolKind.FIELD
                } else {
                    SymbolKind.VARIABLE
                }
            }
            is DartVarDeclarationListPart -> {
                val grandParent = element.parent?.parent
                if (grandParent is DartClassMembers) {
                    SymbolKind.FIELD
                } else {
                    SymbolKind.VARIABLE
                }
            }
            is DartSimpleFormalParameter -> SymbolKind.PARAMETER
            else -> SymbolKind.VARIABLE
        }
    }

    // ===== Find References =====

    override fun findReferences(
        project: Project,
        filePath: String,
        offset: Int
    ): List<LocationInfo> {
        val dartFile = getDartFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = dartFile.findElementAt(offset)
            ?: throw IllegalArgumentException("No element at offset")

        val targetElement = findMeaningfulElement(element)
            ?: throw IllegalArgumentException("No symbol at position")

        val scope = GlobalSearchScope.projectScope(project)
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
        val dartFile = getDartFile(project, filePath) ?: return null
        val element = dartFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        val kind = getSymbolKind(targetElement)
        return buildSymbolInfo(project, targetElement, kind)
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val dartFile = getDartFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()

        // Extract imports
        PsiTreeUtil.findChildrenOfType(dartFile, DartImportStatement::class.java).forEach { importStmt ->
            getLocation(project, importStmt)?.let { loc ->
                val alias = importStmt.importPrefix?.text
                val showNames = importStmt.showCombinatorList.flatMap { combinator ->
                    PsiTreeUtil.findChildrenOfType(combinator, DartComponentName::class.java).mapNotNull { it.name }
                }.takeIf { it.isNotEmpty() }

                imports.add(ImportInfo(
                    module = importStmt.uriString,
                    names = showNames,
                    alias = alias,
                    location = loc
                ))
            }
        }

        // Extract top-level declarations
        dartFile.children.forEach { child ->
            when (child) {
                is DartClassDefinition -> buildClassNode(project, child)?.let { symbols.add(it) }
                is DartEnumDefinition -> buildEnumNode(project, child)?.let { symbols.add(it) }
                is DartMixinDeclaration -> buildClassLikeNode(project, child, SymbolKind.TRAIT)?.let { symbols.add(it) }
                is DartExtensionDeclaration -> buildExtensionNode(project, child)?.let { symbols.add(it) }
                is DartExtensionTypeDeclaration -> buildExtensionTypeNode(project, child)?.let { symbols.add(it) }
                is DartFunctionDeclarationWithBody -> {
                    buildSymbolInfo(project, child, SymbolKind.FUNCTION)?.let {
                        symbols.add(SymbolNode(it))
                    }
                }
                is DartGetterDeclaration -> {
                    buildSymbolInfo(project, child, SymbolKind.PROPERTY)?.let {
                        symbols.add(SymbolNode(it))
                    }
                }
                is DartSetterDeclaration -> {
                    buildSymbolInfo(project, child, SymbolKind.PROPERTY)?.let {
                        symbols.add(SymbolNode(it))
                    }
                }
                is DartVarDeclarationList -> {
                    buildVarSymbols(project, child, SymbolKind.VARIABLE).forEach { info ->
                        symbols.add(SymbolNode(info))
                    }
                }
                is DartFunctionTypeAlias -> {
                    buildSymbolInfo(project, child, SymbolKind.CLASS)?.let {
                        symbols.add(SymbolNode(it))
                    }
                }
            }
        }

        // Extract library name from library directive if present
        val libraryName = PsiTreeUtil.findChildOfType(dartFile, DartLibraryStatement::class.java)
            ?.let { PsiTreeUtil.findChildOfType(it, DartComponentName::class.java)?.name }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = libraryName,
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

        var targetClass: DartClass? = null

        val files = DartComponentIndex.getAllFiles(typeName, scope)
        for (virtualFile in files) {
            if (targetClass != null) break
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? DartFile ?: continue

            PsiTreeUtil.findChildrenOfType(psiFile, DartClass::class.java)
                .find { it.name == typeName }
                ?.let { targetClass = it }
        }

        val target = targetClass ?: return null
        val kind = getSymbolKind(target)

        val superTypes = mutableListOf<TypeRef>()
        val subTypes = mutableListOf<TypeRef>()

        // Super types: superclass, implements, mixins
        target.superClass?.let { superType ->
            val resolved = superType.resolveReference()
            val resolvedName = resolved?.let { (it as? DartComponent)?.name } ?: superType.text
            superTypes.add(TypeRef(
                name = resolvedName ?: superType.text,
                qualifiedName = null,
                location = resolved?.let { getLocation(project, it) }
            ))
        }

        target.implementsList.forEach { implType ->
            val resolved = implType.resolveReference()
            val resolvedName = resolved?.let { (it as? DartComponent)?.name } ?: implType.text
            superTypes.add(TypeRef(
                name = resolvedName ?: implType.text,
                qualifiedName = null,
                location = resolved?.let { getLocation(project, it) }
            ))
        }

        target.mixinsList.forEach { mixinType ->
            val resolved = mixinType.resolveReference()
            val resolvedName = resolved?.let { (it as? DartComponent)?.name } ?: mixinType.text
            superTypes.add(TypeRef(
                name = resolvedName ?: mixinType.text,
                qualifiedName = null,
                location = resolved?.let { getLocation(project, it) }
            ))
        }

        // Sub types: search all Dart files for classes that extend/implement/mixin this type
        val allDartFiles = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, "dart", scope)
        allDartFiles.forEach { virtualFile ->
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? DartFile ?: return@forEach

            PsiTreeUtil.findChildrenOfType(psiFile, DartClass::class.java)
                .filter { it.name != typeName }
                .forEach { dartClass ->
                    val isSubType = dartClass.superClass?.text?.contains(typeName) == true ||
                        dartClass.implementsList.any { it.text.contains(typeName) } ||
                        dartClass.mixinsList.any { it.text.contains(typeName) }

                    if (isSubType) {
                        val name = dartClass.name ?: return@forEach
                        subTypes.add(TypeRef(
                            name = name,
                            qualifiedName = null,
                            location = getLocation(project, dartClass)
                        ))
                    }
                }
        }

        return TypeHierarchy(
            typeName = target.name ?: "",
            qualifiedName = null,
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
        val dartFile = getDartFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(dartFile) ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getDartFile(project: Project, filePath: String): DartFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? DartFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is DartClassDefinition,
                is DartEnumDefinition,
                is DartMixinDeclaration,
                is DartFunctionDeclarationWithBody,
                is DartMethodDeclaration,
                is DartGetterDeclaration,
                is DartSetterDeclaration,
                is DartFactoryConstructorDeclaration,
                is DartNamedConstructorDeclaration,
                is DartEnumConstantDeclaration,
                is DartFunctionTypeAlias,
                is DartVarAccessDeclaration,
                is DartVarDeclarationListPart,
                is DartSimpleFormalParameter -> return current
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

    private fun buildSymbolInfo(project: Project, element: PsiElement, kind: SymbolKind): SymbolInfo? {
        val component = element as? DartComponent
        val name = component?.name ?: return null

        return SymbolInfo(
            name = name,
            kind = kind,
            language = languageId,
            signature = buildSignature(element, kind),
            documentation = getDocumentation(element),
            location = getLocation(project, element),
            nameLocation = component.componentName?.let { getLocation(project, it) },
            returnType = getReturnType(element),
            parameters = getParameters(element),
            modifiers = getModifiers(component),
            annotations = getAnnotations(component)
        )
    }

    private fun buildSignature(element: PsiElement, kind: SymbolKind): String? {
        return when (element) {
            is DartClassDefinition -> {
                val name = element.name ?: ""
                val typeParams = element.typeParameters?.text ?: ""
                val superclass = element.superclass?.text?.let { " extends $it" } ?: ""
                val mixins = element.mixins?.text?.let { " with $it" } ?: ""
                val interfaces = element.interfaces?.text?.let { " implements $it" } ?: ""
                "class $name$typeParams$superclass$mixins$interfaces"
            }
            is DartEnumDefinition -> {
                val name = element.name ?: ""
                val typeParams = element.typeParameters?.text ?: ""
                val mixins = element.mixins?.text?.let { " with $it" } ?: ""
                val interfaces = element.interfaces?.text?.let { " implements $it" } ?: ""
                "enum $name$typeParams$mixins$interfaces"
            }
            is DartMixinDeclaration -> {
                val name = element.name ?: ""
                val typeParams = element.typeParameters?.text ?: ""
                val onMixins = element.onMixins?.text?.let { " on $it" } ?: ""
                val interfaces = element.interfaces?.text?.let { " implements $it" } ?: ""
                "mixin $name$typeParams$onMixins$interfaces"
            }
            is DartFunctionDeclarationWithBody -> {
                val name = element.name ?: ""
                val typeParams = element.typeParameters?.text ?: ""
                val params = element.formalParameterList?.text ?: "()"
                val returnType = element.returnType?.text?.let { "$it " } ?: ""
                "$returnType$name$typeParams$params"
            }
            is DartMethodDeclaration -> {
                val name = element.name ?: ""
                val typeParams = element.typeParameters?.text ?: ""
                val params = element.formalParameterList?.text ?: "()"
                val returnType = element.returnType?.text?.let { "$it " } ?: ""
                val static = if (element.isStatic) "static " else ""
                "$static$returnType$name$typeParams$params"
            }
            is DartGetterDeclaration -> {
                val name = element.name ?: ""
                val returnType = element.returnType?.text?.let { "$it " } ?: ""
                "${returnType}get $name"
            }
            is DartSetterDeclaration -> {
                val name = element.name ?: ""
                val params = element.formalParameterList?.text ?: ""
                "set $name$params"
            }
            is DartFactoryConstructorDeclaration -> {
                val names = element.componentNameList.mapNotNull { it.name }
                val fullName = names.joinToString(".")
                val params = element.formalParameterList?.text ?: "()"
                "factory $fullName$params"
            }
            is DartNamedConstructorDeclaration -> {
                val names = element.componentNameList.mapNotNull { it.name }
                val fullName = names.joinToString(".")
                val params = element.formalParameterList.text
                "$fullName$params"
            }
            is DartFunctionTypeAlias -> {
                val name = element.name ?: ""
                val typeParams = element.typeParameters?.text ?: ""
                val params = element.formalParameterList?.text ?: ""
                val returnType = element.returnType?.text?.let { "$it " } ?: ""
                "typedef $returnType$name$typeParams$params"
            }
            is DartVarAccessDeclaration -> {
                val name = element.name ?: ""
                val type = element.type?.text?.let { "$it " } ?: ""
                val modifier = when {
                    element.isFinal -> "final "
                    element.node.findChildByType(DartTokenTypes.CONST) != null -> "const "
                    else -> "var "
                }
                "$modifier$type$name"
            }
            else -> null
        }
    }

    private fun getReturnType(element: PsiElement): String? {
        return when (element) {
            is DartFunctionDeclarationWithBody -> element.returnType?.text
            is DartMethodDeclaration -> element.returnType?.text
            is DartGetterDeclaration -> element.returnType?.text
            is DartSetterDeclaration -> element.returnType?.text
            is DartFunctionTypeAlias -> element.returnType?.text
            is DartVarAccessDeclaration -> element.type?.text
            else -> null
        }
    }

    private fun getParameters(element: PsiElement): List<ParameterInfo>? {
        val paramList = when (element) {
            is DartFunctionDeclarationWithBody -> element.formalParameterList
            is DartMethodDeclaration -> element.formalParameterList
            is DartSetterDeclaration -> element.formalParameterList
            is DartFactoryConstructorDeclaration -> element.formalParameterList
            is DartNamedConstructorDeclaration -> element.formalParameterList
            is DartFunctionTypeAlias -> element.formalParameterList
            else -> null
        } ?: return null

        val params = mutableListOf<ParameterInfo>()

        // Required positional parameters
        paramList.normalFormalParameterList.forEach { normalParam ->
            extractParameterInfo(normalParam, isOptional = false)?.let { params.add(it) }
        }

        // Optional parameters (named or positional)
        paramList.optionalFormalParameters?.let { optionalParams ->
            PsiTreeUtil.findChildrenOfType(optionalParams, DartNormalFormalParameter::class.java).forEach { normalParam ->
                extractParameterInfo(normalParam, isOptional = true)?.let { params.add(it) }
            }
            PsiTreeUtil.findChildrenOfType(optionalParams, DartDefaultFormalNamedParameter::class.java).forEach { namedParam ->
                val innerParam = namedParam.normalFormalParameter
                if (innerParam != null) {
                    extractParameterInfo(innerParam, isOptional = true, defaultValue = namedParam.expression?.text)?.let { params.add(it) }
                }
            }
        }

        return params.takeIf { it.isNotEmpty() }
    }

    private fun extractParameterInfo(
        normalParam: DartNormalFormalParameter,
        isOptional: Boolean,
        defaultValue: String? = null
    ): ParameterInfo? {
        val simple = normalParam.simpleFormalParameter
        if (simple != null) {
            return ParameterInfo(
                name = simple.name ?: "",
                type = simple.type?.text,
                defaultValue = defaultValue,
                isOptional = isOptional
            )
        }

        val fieldParam = normalParam.fieldFormalParameter
        if (fieldParam != null) {
            val name = fieldParam.referenceExpression.text
            return ParameterInfo(
                name = "this.$name",
                type = fieldParam.type?.text,
                defaultValue = defaultValue,
                isOptional = isOptional
            )
        }

        val funcParam = normalParam.functionFormalParameter
        if (funcParam != null) {
            val name = funcParam.componentName?.name ?: ""
            return ParameterInfo(
                name = name,
                type = "Function",
                defaultValue = defaultValue,
                isOptional = isOptional
            )
        }

        return null
    }

    private fun getModifiers(component: DartComponent): List<String>? {
        val modifiers = mutableListOf<String>()

        if (component.isAbstract) modifiers.add("abstract")
        if (component.isStatic) modifiers.add("static")
        if (component.isFinal) modifiers.add("final")
        if (component.node.findChildByType(DartTokenTypes.CONST) != null) modifiers.add("const")
        if (!component.isPublic) modifiers.add("private")

        return modifiers.takeIf { it.isNotEmpty() }
    }

    private fun getAnnotations(component: DartComponent): List<String>? {
        return component.metadataList.mapNotNull { it.text }.takeIf { it.isNotEmpty() }
    }

    private fun getDocumentation(element: PsiElement): String? {
        val docLines = mutableListOf<String>()
        var sibling = element.prevSibling
        while (sibling != null) {
            val text = sibling.text
            when {
                sibling is DartDocComment || text.startsWith("///") -> {
                    // Handle /// doc comments
                    text.lines().reversed().forEach { line ->
                        docLines.add(0, line.removePrefix("///").trim())
                    }
                }
                text.startsWith("/**") -> {
                    // Handle /** */ doc comments
                    val cleaned = text.removePrefix("/**").removeSuffix("*/").trim()
                    cleaned.lines().forEach { line ->
                        docLines.add(line.trim().removePrefix("*").trim())
                    }
                }
                text.isBlank() || sibling is com.intellij.psi.PsiWhiteSpace -> {
                    if (docLines.isNotEmpty()) break
                }
                else -> break
            }
            sibling = sibling.prevSibling
        }
        return docLines.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    // ===== Node builders for getFileSymbols =====

    private fun buildClassNode(project: Project, classDef: DartClassDefinition): SymbolNode? {
        val kind = SymbolKind.CLASS
        val info = buildSymbolInfo(project, classDef, kind) ?: return null
        val children = buildClassMembers(project, classDef)
        return SymbolNode(info, children)
    }

    private fun buildEnumNode(project: Project, enumDef: DartEnumDefinition): SymbolNode? {
        val info = buildSymbolInfo(project, enumDef, SymbolKind.ENUM) ?: return null
        val children = mutableListOf<SymbolNode>()

        // Enum constants
        enumDef.enumConstantDeclarationList.forEach { constant ->
            buildSymbolInfo(project, constant, SymbolKind.CONSTANT)?.let {
                children.add(SymbolNode(it))
            }
        }

        // Enum members (Dart 2.17+ enhanced enums)
        children.addAll(buildClassMembers(project, enumDef))

        return SymbolNode(info, children)
    }

    private fun buildClassLikeNode(project: Project, dartClass: DartClass, kind: SymbolKind): SymbolNode? {
        val info = buildSymbolInfo(project, dartClass, kind) ?: return null
        val children = buildClassMembers(project, dartClass)
        return SymbolNode(info, children)
    }

    private fun buildExtensionNode(project: Project, extension: DartExtensionDeclaration): SymbolNode? {
        val extType = extension.type?.text ?: "unknown"
        val name = PsiTreeUtil.findChildOfType(extension, DartComponentName::class.java)?.name
            ?: "extension on $extType"

        val info = SymbolInfo(
            name = name,
            kind = SymbolKind.CLASS,
            language = languageId,
            signature = "extension $name on $extType",
            location = getLocation(project, extension)
        )

        val children = mutableListOf<SymbolNode>()
        val classBody = extension.classBody
        val members = classBody?.classMembers
        if (members != null) {
            children.addAll(buildMemberSymbols(project, members))
        }

        return SymbolNode(info, children)
    }

    private fun buildExtensionTypeNode(project: Project, extType: DartExtensionTypeDeclaration): SymbolNode? {
        val name = extType.componentNameList.firstOrNull()?.name ?: return null
        val representationType = extType.type?.text

        val info = SymbolInfo(
            name = name,
            kind = SymbolKind.CLASS,
            language = languageId,
            signature = "extension type $name${if (representationType != null) "($representationType)" else ""}",
            location = getLocation(project, extType),
            nameLocation = extType.componentNameList.firstOrNull()?.let { getLocation(project, it) }
        )

        val children = mutableListOf<SymbolNode>()
        val classBody = extType.classBody
        val members = classBody?.classMembers
        if (members != null) {
            children.addAll(buildMemberSymbols(project, members))
        }

        return SymbolNode(info, children)
    }

    private fun buildClassMembers(project: Project, dartClass: DartClass): List<SymbolNode> {
        val children = mutableListOf<SymbolNode>()

        dartClass.methods.forEach { method ->
            buildSymbolInfo(project, method, SymbolKind.METHOD)?.let {
                children.add(SymbolNode(it))
            }
        }

        dartClass.fields.forEach { field ->
            buildSymbolInfo(project, field, SymbolKind.FIELD)?.let {
                children.add(SymbolNode(it))
            }
        }

        dartClass.constructors.forEach { constructor ->
            val kind = SymbolKind.METHOD
            buildSymbolInfo(project, constructor, kind)?.let {
                children.add(SymbolNode(it))
            }
        }

        return children
    }

    private fun buildMemberSymbols(project: Project, members: DartClassMembers): List<SymbolNode> {
        val children = mutableListOf<SymbolNode>()

        members.methodDeclarationList.forEach { method ->
            buildSymbolInfo(project, method, SymbolKind.METHOD)?.let {
                children.add(SymbolNode(it))
            }
        }

        members.getterDeclarationList.forEach { getter ->
            buildSymbolInfo(project, getter, SymbolKind.PROPERTY)?.let {
                children.add(SymbolNode(it))
            }
        }

        members.setterDeclarationList.forEach { setter ->
            buildSymbolInfo(project, setter, SymbolKind.PROPERTY)?.let {
                children.add(SymbolNode(it))
            }
        }

        members.namedConstructorDeclarationList.forEach { constructor ->
            buildSymbolInfo(project, constructor, SymbolKind.METHOD)?.let {
                children.add(SymbolNode(it))
            }
        }

        members.factoryConstructorDeclarationList.forEach { factory ->
            buildSymbolInfo(project, factory, SymbolKind.METHOD)?.let {
                children.add(SymbolNode(it))
            }
        }

        members.varDeclarationListList.forEach { varList ->
            buildVarSymbols(project, varList, SymbolKind.FIELD).forEach { info ->
                children.add(SymbolNode(info))
            }
        }

        return children
    }

    private fun buildVarSymbols(project: Project, varList: DartVarDeclarationList, kind: SymbolKind): List<SymbolInfo> {
        val results = mutableListOf<SymbolInfo>()

        // First variable in the declaration
        val firstVar = varList.varAccessDeclaration
        buildSymbolInfo(project, firstVar, kind)?.let { results.add(it) }

        // Additional variables (var a, b, c;)
        varList.varDeclarationListPartList.forEach { part ->
            buildSymbolInfo(project, part, kind)?.let { results.add(it) }
        }

        return results
    }
}

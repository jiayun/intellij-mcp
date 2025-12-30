package info.jiayun.intellijmcp.kotlin

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import info.jiayun.intellijmcp.api.*
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

class KotlinLanguageAdapter : LanguageAdapter {

    override val languageId = "kotlin"
    override val languageDisplayName = "Kotlin"
    override val supportedExtensions = setOf("kt", "kts")

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        // Find Class/Interface/Object/Enum
        if (kind == null || kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE || kind == SymbolKind.ENUM) {
            KotlinClassShortNameIndex.get(name, project, scope).forEach { ktClassOrObject ->
                val symbolKind = getClassKind(ktClassOrObject)
                if (kind == null || kind == symbolKind) {
                    results.add(buildClassInfo(project, ktClassOrObject))
                }
            }
        }

        // Find Function/Method
        if (kind == null || kind == SymbolKind.FUNCTION || kind == SymbolKind.METHOD) {
            KotlinFunctionShortNameIndex.get(name, project, scope).forEach { ktFunction ->
                results.add(buildFunctionInfo(project, ktFunction))
            }
        }

        // Find Property/Variable
        if (kind == null || kind == SymbolKind.PROPERTY || kind == SymbolKind.VARIABLE || kind == SymbolKind.FIELD) {
            KotlinPropertyShortNameIndex.get(name, project, scope)
                .filterIsInstance<KtProperty>()
                .forEach { ktProperty ->
                    results.add(buildPropertyInfo(project, ktProperty))
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
        val ktFile = getKtFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = ktFile.findElementAt(offset)
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
        val ktFile = getKtFile(project, filePath) ?: return null
        val element = ktFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        return when (targetElement) {
            is KtClass -> buildClassInfo(project, targetElement)
            is KtObjectDeclaration -> buildObjectInfo(project, targetElement)
            is KtNamedFunction -> buildFunctionInfo(project, targetElement)
            is KtProperty -> buildPropertyInfo(project, targetElement)
            is KtParameter -> buildParameterInfo(project, targetElement)
            else -> null
        }
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val ktFile = getKtFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()

        // Extract imports
        ktFile.importDirectives.forEach { importDirective ->
            getLocation(project, importDirective)?.let { loc ->
                imports.add(ImportInfo(
                    module = importDirective.importedFqName?.asString() ?: "",
                    names = null,
                    alias = importDirective.aliasName,
                    location = loc
                ))
            }
        }

        // Extract top-level declarations
        ktFile.declarations.forEach { declaration ->
            when (declaration) {
                is KtClass -> symbols.add(buildClassNode(project, declaration))
                is KtObjectDeclaration -> symbols.add(buildObjectNode(project, declaration))
                is KtNamedFunction -> symbols.add(SymbolNode(buildFunctionInfo(project, declaration)))
                is KtProperty -> symbols.add(SymbolNode(buildPropertyInfo(project, declaration)))
            }
        }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = ktFile.packageFqName.asString().takeIf { it.isNotEmpty() },
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

        // Find the class by name
        val shortName = typeName.substringAfterLast('.')
        val classes = KotlinClassShortNameIndex.get(shortName, project, scope)
        val ktClass = classes.find { it.fqName?.asString() == typeName }
            ?: classes.firstOrNull()
            ?: return null

        // Get super types from superTypeListEntries
        val superTypes = ktClass.superTypeListEntries.mapNotNull { entry ->
            val typeRef = entry.typeReference
            TypeRef(
                name = typeRef?.text?.substringBefore('<') ?: return@mapNotNull null,
                qualifiedName = null, // Would need type resolution for FQN
                location = getLocation(project, entry)
            )
        }

        // Get sub types by searching all classes
        val subTypes = mutableListOf<TypeRef>()
        val targetFqn = ktClass.fqName?.asString()
        if (targetFqn != null) {
            // Search through all Kotlin classes for inheritors
            KotlinClassShortNameIndex.getAllKeys(project).forEach { className ->
                KotlinClassShortNameIndex.get(className, project, scope).forEach { candidateClass ->
                    val isSubtype = candidateClass.superTypeListEntries.any { entry ->
                        val superTypeName = entry.typeReference?.text?.substringBefore('<')
                        superTypeName == shortName || superTypeName == targetFqn
                    }
                    if (isSubtype && candidateClass != ktClass) {
                        subTypes.add(TypeRef(
                            name = candidateClass.name ?: return@forEach,
                            qualifiedName = candidateClass.fqName?.asString(),
                            location = getLocation(project, candidateClass)
                        ))
                    }
                }
            }
        }

        return TypeHierarchy(
            typeName = ktClass.name ?: "",
            qualifiedName = ktClass.fqName?.asString(),
            kind = getClassKind(ktClass),
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
        val ktFile = getKtFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(ktFile)
            ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getKtFile(project: Project, filePath: String): KtFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is KtClass,
                is KtObjectDeclaration,
                is KtNamedFunction,
                is KtProperty,
                is KtParameter,
                is KtReferenceExpression -> return current
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

    private fun getClassKind(ktClassOrObject: KtClassOrObject): SymbolKind {
        return when {
            ktClassOrObject is KtObjectDeclaration -> SymbolKind.CLASS
            (ktClassOrObject as? KtClass)?.isInterface() == true -> SymbolKind.INTERFACE
            (ktClassOrObject as? KtClass)?.isEnum() == true -> SymbolKind.ENUM
            else -> SymbolKind.CLASS
        }
    }

    private fun buildClassInfo(project: Project, ktClassOrObject: KtClassOrObject): SymbolInfo {
        val kind = getClassKind(ktClassOrObject)

        return SymbolInfo(
            name = ktClassOrObject.name ?: "",
            kind = kind,
            language = languageId,
            qualifiedName = ktClassOrObject.fqName?.asString(),
            documentation = ktClassOrObject.docComment?.text,
            location = getLocation(project, ktClassOrObject),
            nameLocation = ktClassOrObject.nameIdentifier?.let { getLocation(project, it) },
            modifiers = getModifiers(ktClassOrObject),
            annotations = getAnnotations(ktClassOrObject),
            superTypes = ktClassOrObject.superTypeListEntries.mapNotNull { it.typeReference?.text }
        )
    }

    private fun buildObjectInfo(project: Project, ktObject: KtObjectDeclaration): SymbolInfo {
        val modifiers = getModifiers(ktObject)?.toMutableList() ?: mutableListOf()
        if (ktObject.isCompanion()) {
            modifiers.add("companion")
        }

        return SymbolInfo(
            name = ktObject.name ?: "companion",
            kind = SymbolKind.CLASS,
            language = languageId,
            qualifiedName = ktObject.fqName?.asString(),
            documentation = ktObject.docComment?.text,
            location = getLocation(project, ktObject),
            nameLocation = ktObject.nameIdentifier?.let { getLocation(project, it) },
            modifiers = modifiers.takeIf { it.isNotEmpty() },
            annotations = getAnnotations(ktObject),
            superTypes = ktObject.superTypeListEntries.mapNotNull { it.typeReference?.text }
        )
    }

    private fun buildFunctionInfo(project: Project, ktFunction: KtNamedFunction): SymbolInfo {
        val isMethod = ktFunction.parent?.parent is KtClassOrObject
        val isExtension = ktFunction.receiverTypeReference != null

        val modifiers = getModifiers(ktFunction)?.toMutableList() ?: mutableListOf()
        if (isExtension) {
            modifiers.add("extension")
        }

        return SymbolInfo(
            name = ktFunction.name ?: "",
            kind = if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION,
            language = languageId,
            qualifiedName = ktFunction.fqName?.asString(),
            signature = buildSignature(ktFunction),
            documentation = ktFunction.docComment?.text,
            location = getLocation(project, ktFunction),
            nameLocation = ktFunction.nameIdentifier?.let { getLocation(project, it) },
            returnType = ktFunction.typeReference?.text,
            parameters = ktFunction.valueParameters.map { param ->
                ParameterInfo(
                    name = param.name ?: "",
                    type = param.typeReference?.text,
                    defaultValue = param.defaultValue?.text,
                    isOptional = param.hasDefaultValue()
                )
            },
            modifiers = modifiers.takeIf { it.isNotEmpty() },
            annotations = getAnnotations(ktFunction)
        )
    }

    private fun buildPropertyInfo(project: Project, ktProperty: KtProperty): SymbolInfo {
        val kind = when {
            ktProperty.hasModifier(KtTokens.CONST_KEYWORD) -> SymbolKind.CONSTANT
            ktProperty.parent?.parent is KtClassOrObject -> SymbolKind.PROPERTY
            else -> SymbolKind.VARIABLE
        }

        return SymbolInfo(
            name = ktProperty.name ?: "",
            kind = kind,
            language = languageId,
            qualifiedName = ktProperty.fqName?.asString(),
            documentation = ktProperty.docComment?.text,
            location = getLocation(project, ktProperty),
            nameLocation = ktProperty.nameIdentifier?.let { getLocation(project, it) },
            returnType = ktProperty.typeReference?.text,
            modifiers = getModifiers(ktProperty),
            annotations = getAnnotations(ktProperty)
        )
    }

    private fun buildParameterInfo(project: Project, param: KtParameter): SymbolInfo {
        return SymbolInfo(
            name = param.name ?: "",
            kind = SymbolKind.PARAMETER,
            language = languageId,
            location = getLocation(project, param),
            returnType = param.typeReference?.text,
            annotations = getAnnotations(param)
        )
    }

    private fun buildClassNode(project: Project, ktClass: KtClass): SymbolNode {
        val children = mutableListOf<SymbolNode>()

        // Methods
        ktClass.declarations.filterIsInstance<KtNamedFunction>().forEach { method ->
            children.add(SymbolNode(buildFunctionInfo(project, method)))
        }

        // Properties
        ktClass.declarations.filterIsInstance<KtProperty>().forEach { prop ->
            children.add(SymbolNode(buildPropertyInfo(project, prop)))
        }

        // Nested classes
        ktClass.declarations.filterIsInstance<KtClass>().forEach { nested ->
            children.add(buildClassNode(project, nested))
        }

        // Companion objects
        ktClass.companionObjects.forEach { companion ->
            children.add(buildObjectNode(project, companion))
        }

        return SymbolNode(
            symbol = buildClassInfo(project, ktClass),
            children = children
        )
    }

    private fun buildObjectNode(project: Project, ktObject: KtObjectDeclaration): SymbolNode {
        val children = mutableListOf<SymbolNode>()

        // Methods
        ktObject.declarations.filterIsInstance<KtNamedFunction>().forEach { method ->
            children.add(SymbolNode(buildFunctionInfo(project, method)))
        }

        // Properties
        ktObject.declarations.filterIsInstance<KtProperty>().forEach { prop ->
            children.add(SymbolNode(buildPropertyInfo(project, prop)))
        }

        return SymbolNode(
            symbol = buildObjectInfo(project, ktObject),
            children = children
        )
    }

    private fun buildSignature(ktFunction: KtNamedFunction): String {
        val modifiers = ktFunction.modifierList?.text?.replace("\n", " ")?.trim()?.let { "$it " } ?: ""
        val funKeyword = "fun "
        val typeParams = ktFunction.typeParameters.takeIf { it.isNotEmpty() }?.let { params ->
            "<${params.joinToString(", ") { it.name ?: "" }}> "
        } ?: ""
        val receiver = ktFunction.receiverTypeReference?.text?.let { "$it." } ?: ""
        val name = ktFunction.name ?: ""
        val params = ktFunction.valueParameters.joinToString(", ") { param ->
            buildString {
                append(param.name ?: "_")
                param.typeReference?.let { append(": ${it.text}") }
                param.defaultValue?.let { append(" = ${it.text}") }
            }
        }
        val returnType = ktFunction.typeReference?.text?.let { ": $it" } ?: ""

        return "$modifiers$funKeyword$typeParams$receiver$name($params)$returnType"
    }

    private fun getModifiers(element: KtModifierListOwner?): List<String>? {
        val modifierList = element?.modifierList ?: return null
        val modifiers = mutableListOf<String>()

        val modifierTokens = listOf(
            KtTokens.PUBLIC_KEYWORD to "public",
            KtTokens.PRIVATE_KEYWORD to "private",
            KtTokens.PROTECTED_KEYWORD to "protected",
            KtTokens.INTERNAL_KEYWORD to "internal",
            KtTokens.OPEN_KEYWORD to "open",
            KtTokens.FINAL_KEYWORD to "final",
            KtTokens.ABSTRACT_KEYWORD to "abstract",
            KtTokens.SEALED_KEYWORD to "sealed",
            KtTokens.DATA_KEYWORD to "data",
            KtTokens.INNER_KEYWORD to "inner",
            KtTokens.INLINE_KEYWORD to "inline",
            KtTokens.VALUE_KEYWORD to "value",
            KtTokens.SUSPEND_KEYWORD to "suspend",
            KtTokens.OVERRIDE_KEYWORD to "override",
            KtTokens.EXTERNAL_KEYWORD to "external",
            KtTokens.CONST_KEYWORD to "const",
            KtTokens.LATEINIT_KEYWORD to "lateinit",
            KtTokens.VARARG_KEYWORD to "vararg",
            KtTokens.TAILREC_KEYWORD to "tailrec",
            KtTokens.OPERATOR_KEYWORD to "operator",
            KtTokens.INFIX_KEYWORD to "infix",
            KtTokens.ACTUAL_KEYWORD to "actual",
            KtTokens.EXPECT_KEYWORD to "expect"
        )

        modifierTokens.forEach { (token, name) ->
            if (modifierList.hasModifier(token)) {
                modifiers.add(name)
            }
        }

        return modifiers.takeIf { it.isNotEmpty() }
    }

    private fun getAnnotations(element: KtModifierListOwner?): List<String>? {
        val modifierList = element?.modifierList ?: return null
        val annotations = modifierList.annotationEntries.mapNotNull { annotation ->
            annotation.text
        }
        return annotations.takeIf { it.isNotEmpty() }
    }
}

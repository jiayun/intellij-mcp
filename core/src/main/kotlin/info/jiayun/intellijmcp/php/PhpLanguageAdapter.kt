package info.jiayun.intellijmcp.php

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.codeInsight.completion.PrefixMatcher
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpClassMember
import com.jetbrains.php.lang.psi.elements.PhpModifier
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpReference
import com.jetbrains.php.lang.psi.elements.PhpUse
import com.jetbrains.php.lang.psi.elements.impl.PhpDefineImpl
import info.jiayun.intellijmcp.api.*

class PhpLanguageAdapter : LanguageAdapter {

    override val languageId = "php"
    override val languageDisplayName = "PHP"
    override val supportedExtensions = setOf("php", "phtml")

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val phpIndex = PhpIndex.getInstance(project)
        val results = mutableListOf<SymbolInfo>()

        // Find Class/Interface/Trait
        if (kind == null || kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE || kind == SymbolKind.TRAIT) {
            phpIndex.getClassesByName(name).forEach { phpClass ->
                // Filter by project scope
                if (scope.contains(phpClass.containingFile?.virtualFile ?: return@forEach)) {
                    val symbolKind = when {
                        phpClass.isTrait -> SymbolKind.TRAIT
                        phpClass.isInterface -> SymbolKind.INTERFACE
                        else -> SymbolKind.CLASS
                    }
                    if (kind == null || kind == symbolKind) {
                        results.add(buildClassInfo(project, phpClass))
                    }
                }
            }
        }

        // Find Function
        if (kind == null || kind == SymbolKind.FUNCTION) {
            phpIndex.getFunctionsByName(name).forEach { function ->
                if (scope.contains(function.containingFile?.virtualFile ?: return@forEach)) {
                    results.add(buildFunctionInfo(project, function))
                }
            }
        }

        // Find Constant
        if (kind == null || kind == SymbolKind.CONSTANT) {
            phpIndex.getConstantsByName(name).forEach { constant ->
                if (scope.contains(constant.containingFile?.virtualFile ?: return@forEach)) {
                    results.add(buildConstantInfo(project, constant))
                }
            }
        }

        // Find Method (iterate through all classes like Java adapter)
        if (kind == null || kind == SymbolKind.METHOD) {
            phpIndex.getAllClassNames(PrefixMatcher.ALWAYS_TRUE).forEach { className ->
                phpIndex.getClassesByName(className).forEach { phpClass ->
                    if (scope.contains(phpClass.containingFile?.virtualFile ?: return@forEach)) {
                        phpClass.ownMethods.filter { it.name == name }.forEach { method ->
                            results.add(buildMethodInfo(project, method))
                        }
                    }
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
        val phpFile = getPhpFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = phpFile.findElementAt(offset)
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
        val phpFile = getPhpFile(project, filePath) ?: return null
        val element = phpFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        return when (targetElement) {
            is PhpClass -> buildClassInfo(project, targetElement)
            is Method -> buildMethodInfo(project, targetElement)
            is com.jetbrains.php.lang.psi.elements.Function -> buildFunctionInfo(project, targetElement)
            is Field -> buildFieldInfo(project, targetElement)
            is Parameter -> buildParameterInfo(project, targetElement)
            is PhpDefineImpl -> buildDefineInfo(project, targetElement)
            else -> null
        }
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val phpFile = getPhpFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()
        var namespace: String? = null

        // Walk through all top-level elements
        phpFile.children.forEach { child ->
            processElement(project, child, imports, symbols) { ns ->
                namespace = ns
            }
        }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = namespace,
            imports = imports,
            symbols = symbols
        )
    }

    private fun processElement(
        project: Project,
        element: PsiElement,
        imports: MutableList<ImportInfo>,
        symbols: MutableList<SymbolNode>,
        setNamespace: (String) -> Unit
    ) {
        when (element) {
            is com.jetbrains.php.lang.psi.elements.PhpNamespace -> {
                setNamespace(element.fqn)
                // Process namespace children
                element.children.forEach { child ->
                    processElement(project, child, imports, symbols, setNamespace)
                }
            }
            is PhpUse -> {
                getLocation(project, element)?.let { loc ->
                    imports.add(ImportInfo(
                        module = element.fqn,
                        names = null,
                        alias = element.aliasName,
                        location = loc
                    ))
                }
            }
            is PhpClass -> symbols.add(buildClassNode(project, element))
            is com.jetbrains.php.lang.psi.elements.Function -> symbols.add(SymbolNode(buildFunctionInfo(project, element)))
            is PhpDefineImpl -> symbols.add(SymbolNode(buildDefineInfo(project, element)))
            is com.jetbrains.php.lang.psi.elements.GroupStatement -> {
                // Process group statement children (e.g., inside <?php ... ?>)
                element.children.forEach { child ->
                    processElement(project, child, imports, symbols, setNamespace)
                }
            }
        }
    }

    // ===== Get Type Hierarchy =====

    override fun getTypeHierarchy(
        project: Project,
        typeName: String
    ): TypeHierarchy? {
        val scope = GlobalSearchScope.projectScope(project)
        val phpIndex = PhpIndex.getInstance(project)

        // Find the class by FQN or short name
        val classes = phpIndex.getClassesByFQN(typeName).ifEmpty {
            phpIndex.getClassesByName(typeName.substringAfterLast("\\"))
        }

        val phpClass = classes.firstOrNull() ?: return null

        // Get super classes and interfaces
        val superTypes = mutableListOf<TypeRef>()
        phpClass.superClass?.let { superClass ->
            superTypes.add(TypeRef(
                name = superClass.name,
                qualifiedName = superClass.fqn,
                location = getLocation(project, superClass)
            ))
        }
        phpClass.implementedInterfaces.forEach { iface ->
            superTypes.add(TypeRef(
                name = iface.name,
                qualifiedName = iface.fqn,
                location = getLocation(project, iface)
            ))
        }
        phpClass.traits.forEach { trait ->
            superTypes.add(TypeRef(
                name = trait.name,
                qualifiedName = trait.fqn,
                location = getLocation(project, trait)
            ))
        }

        // Get sub classes using processAllSubclasses (getAllSubclasses is deprecated)
        val subTypes = mutableListOf<TypeRef>()
        phpIndex.processAllSubclasses(phpClass.fqn) { subClass ->
            val virtualFile = subClass.containingFile?.virtualFile
            if (virtualFile != null && scope.contains(virtualFile)) {
                subTypes.add(TypeRef(
                    name = subClass.name,
                    qualifiedName = subClass.fqn,
                    location = getLocation(project, subClass)
                ))
            }
            true  // continue processing
        }

        val symbolKind = when {
            phpClass.isTrait -> SymbolKind.TRAIT
            phpClass.isInterface -> SymbolKind.INTERFACE
            else -> SymbolKind.CLASS
        }

        return TypeHierarchy(
            typeName = phpClass.name,
            qualifiedName = phpClass.fqn,
            kind = symbolKind,
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
        val phpFile = getPhpFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(phpFile)
            ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getPhpFile(project: Project, filePath: String): PhpFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? PhpFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PhpClass,
                is Method,
                is com.jetbrains.php.lang.psi.elements.Function,
                is Field,
                is Parameter,
                is PhpDefineImpl,
                is PhpReference -> return current
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
            line = startLine,  // PHP plugin reports 1-based
            column = startColumn + 1,  // 1-based
            endLine = endLine,  // PHP plugin reports 1-based
            endColumn = endColumn + 1,
            preview = preview
        )
    }

    private fun buildClassInfo(project: Project, phpClass: PhpClass): SymbolInfo {
        val kind = when {
            phpClass.isTrait -> SymbolKind.TRAIT
            phpClass.isInterface -> SymbolKind.INTERFACE
            else -> SymbolKind.CLASS
        }

        return SymbolInfo(
            name = phpClass.name,
            kind = kind,
            language = languageId,
            qualifiedName = phpClass.fqn,
            documentation = phpClass.docComment?.text,
            location = getLocation(project, phpClass),
            nameLocation = phpClass.nameIdentifier?.let { getLocation(project, it) },
            modifiers = getClassModifiers(phpClass),
            superTypes = buildSuperTypes(phpClass)
        )
    }

    private fun buildMethodInfo(project: Project, method: Method): SymbolInfo {
        return SymbolInfo(
            name = method.name,
            kind = SymbolKind.METHOD,
            language = languageId,
            qualifiedName = method.containingClass?.fqn?.let { "$it::${method.name}" },
            signature = buildMethodSignature(method),
            documentation = method.docComment?.text,
            location = getLocation(project, method),
            nameLocation = method.nameIdentifier?.let { getLocation(project, it) },
            returnType = method.type.toString().takeIf { it.isNotBlank() && it != "void" },
            parameters = method.parameters.map { param ->
                ParameterInfo(
                    name = param.name,
                    type = param.type.toString().takeIf { it.isNotBlank() },
                    defaultValue = param.defaultValuePresentation,
                    isOptional = param.isOptional
                )
            },
            modifiers = getMemberModifiers(method)
        )
    }

    private fun buildFunctionInfo(project: Project, function: com.jetbrains.php.lang.psi.elements.Function): SymbolInfo {
        return SymbolInfo(
            name = function.name,
            kind = SymbolKind.FUNCTION,
            language = languageId,
            qualifiedName = function.fqn,
            signature = buildFunctionSignature(function),
            documentation = function.docComment?.text,
            location = getLocation(project, function),
            nameLocation = function.nameIdentifier?.let { getLocation(project, it) },
            returnType = function.type.toString().takeIf { it.isNotBlank() && it != "void" },
            parameters = function.parameters.map { param ->
                ParameterInfo(
                    name = param.name,
                    type = param.type.toString().takeIf { it.isNotBlank() },
                    defaultValue = param.defaultValuePresentation,
                    isOptional = param.isOptional
                )
            }
        )
    }

    private fun buildFieldInfo(project: Project, field: Field): SymbolInfo {
        val kind = if (field.isConstant) SymbolKind.CONSTANT else SymbolKind.FIELD

        return SymbolInfo(
            name = field.name,
            kind = kind,
            language = languageId,
            qualifiedName = field.containingClass?.fqn?.let { "$it::\$${field.name}" },
            location = getLocation(project, field),
            nameLocation = field.nameIdentifier?.let { getLocation(project, it) },
            returnType = field.type.toString().takeIf { it.isNotBlank() },
            modifiers = getMemberModifiers(field),
            documentation = field.docComment?.text
        )
    }

    private fun buildParameterInfo(project: Project, param: Parameter): SymbolInfo {
        return SymbolInfo(
            name = param.name,
            kind = SymbolKind.PARAMETER,
            language = languageId,
            location = getLocation(project, param),
            returnType = param.type.toString().takeIf { it.isNotBlank() }
        )
    }

    private fun buildConstantInfo(project: Project, constant: PhpNamedElement): SymbolInfo {
        return SymbolInfo(
            name = constant.name,
            kind = SymbolKind.CONSTANT,
            language = languageId,
            qualifiedName = constant.fqn,
            location = getLocation(project, constant),
            nameLocation = constant.nameIdentifier?.let { getLocation(project, it) }
        )
    }

    private fun buildDefineInfo(project: Project, define: PhpDefineImpl): SymbolInfo {
        return SymbolInfo(
            name = define.name,
            kind = SymbolKind.CONSTANT,
            language = languageId,
            qualifiedName = define.fqn,
            location = getLocation(project, define),
            nameLocation = define.nameIdentifier?.let { getLocation(project, it) }
        )
    }

    private fun buildClassNode(project: Project, phpClass: PhpClass): SymbolNode {
        val children = mutableListOf<SymbolNode>()

        // Methods
        phpClass.ownMethods.forEach { method ->
            children.add(SymbolNode(buildMethodInfo(project, method)))
        }

        // Fields
        phpClass.ownFields.forEach { field ->
            children.add(SymbolNode(buildFieldInfo(project, field)))
        }

        return SymbolNode(
            symbol = buildClassInfo(project, phpClass),
            children = children
        )
    }

    private fun buildMethodSignature(method: Method): String {
        val modifiers = getMemberModifiers(method)?.joinToString(" ") ?: ""
        val params = method.parameters.joinToString(", ") { param ->
            buildString {
                param.type.toString().takeIf { it.isNotBlank() }?.let { append("$it ") }
                append("\$${param.name}")
                param.defaultValuePresentation?.let { append(" = $it") }
            }
        }
        val returnType = method.type.toString().takeIf { it.isNotBlank() && it != "void" }
            ?.let { ": $it" } ?: ""

        return if (modifiers.isNotBlank()) {
            "$modifiers function ${method.name}($params)$returnType"
        } else {
            "function ${method.name}($params)$returnType"
        }
    }

    private fun buildFunctionSignature(function: com.jetbrains.php.lang.psi.elements.Function): String {
        val params = function.parameters.joinToString(", ") { param ->
            buildString {
                param.type.toString().takeIf { it.isNotBlank() }?.let { append("$it ") }
                append("\$${param.name}")
                param.defaultValuePresentation?.let { append(" = $it") }
            }
        }
        val returnType = function.type.toString().takeIf { it.isNotBlank() && it != "void" }
            ?.let { ": $it" } ?: ""

        return "function ${function.name}($params)$returnType"
    }

    private fun buildSuperTypes(phpClass: PhpClass): List<String> {
        val superTypes = mutableListOf<String>()
        phpClass.superFQN?.let { superTypes.add(it) }
        phpClass.interfaceNames.forEach { superTypes.add(it) }
        phpClass.traitNames.forEach { superTypes.add(it) }
        return superTypes
    }

    private fun getMemberModifiers(element: PhpClassMember): List<String>? {
        val modifiers = mutableListOf<String>()

        when (element.modifier.access) {
            PhpModifier.Access.PUBLIC -> modifiers.add("public")
            PhpModifier.Access.PROTECTED -> modifiers.add("protected")
            PhpModifier.Access.PRIVATE -> modifiers.add("private")
        }

        if (element.modifier.isStatic) modifiers.add("static")
        if (element.modifier.isFinal) modifiers.add("final")
        if (element.modifier.isAbstract) modifiers.add("abstract")

        return modifiers.takeIf { it.isNotEmpty() }
    }

    private fun getClassModifiers(phpClass: PhpClass): List<String>? {
        val modifiers = mutableListOf<String>()

        if (phpClass.isFinal) modifiers.add("final")
        if (phpClass.isAbstract) modifiers.add("abstract")

        return modifiers.takeIf { it.isNotEmpty() }
    }
}

package info.jiayun.intellijmcp.java

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.AllClassesSearch
import info.jiayun.intellijmcp.api.*

class JavaLanguageAdapter : LanguageAdapter {

    override val languageId = "java"
    override val languageDisplayName = "Java"
    override val supportedExtensions = setOf("java")

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        // Find Class/Interface/Enum
        if (kind == null || kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE || kind == SymbolKind.ENUM) {
            AllClassesSearch.search(scope, project).forEach { psiClass ->
                if (psiClass.name == name) {
                    val symbolKind = when {
                        psiClass.isInterface -> SymbolKind.INTERFACE
                        psiClass.isEnum -> SymbolKind.ENUM
                        else -> SymbolKind.CLASS
                    }
                    if (kind == null || kind == symbolKind) {
                        results.add(buildClassInfo(project, psiClass))
                    }
                }
            }
        }

        // Find Method
        if (kind == null || kind == SymbolKind.METHOD || kind == SymbolKind.FUNCTION) {
            AllClassesSearch.search(scope, project).forEach { psiClass ->
                psiClass.methods.filter { it.name == name }.forEach { method ->
                    results.add(buildMethodInfo(project, method))
                }
            }
        }

        // Find Field
        if (kind == null || kind == SymbolKind.FIELD || kind == SymbolKind.VARIABLE) {
            AllClassesSearch.search(scope, project).forEach { psiClass ->
                psiClass.fields.filter { it.name == name }.forEach { field ->
                    results.add(buildFieldInfo(project, field))
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
        val javaFile = getJavaFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = javaFile.findElementAt(offset)
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
        val javaFile = getJavaFile(project, filePath) ?: return null
        val element = javaFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        return when (targetElement) {
            is PsiClass -> buildClassInfo(project, targetElement)
            is PsiMethod -> buildMethodInfo(project, targetElement)
            is PsiField -> buildFieldInfo(project, targetElement)
            is PsiParameter -> buildParameterInfo(project, targetElement)
            is PsiLocalVariable -> buildLocalVariableInfo(project, targetElement)
            else -> null
        }
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val javaFile = getJavaFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()

        // Extract imports
        javaFile.importList?.importStatements?.forEach { importStmt ->
            getLocation(project, importStmt)?.let { loc ->
                imports.add(ImportInfo(
                    module = importStmt.qualifiedName ?: "",
                    names = null,
                    alias = null,
                    location = loc
                ))
            }
        }

        // Extract top-level classes
        javaFile.classes.forEach { psiClass ->
            symbols.add(buildClassNode(project, psiClass))
        }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = javaFile.packageName,
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

        // Find the class
        val targetClass = AllClassesSearch.search(scope, project).find { psiClass ->
            psiClass.qualifiedName == typeName || psiClass.name == typeName.substringAfterLast('.')
        }

        val psiClass = targetClass ?: return null

        // Get super classes and interfaces
        val superTypes = mutableListOf<TypeRef>()
        psiClass.superClass?.let { superClass ->
            superTypes.add(TypeRef(
                name = superClass.name ?: "",
                qualifiedName = superClass.qualifiedName,
                location = getLocation(project, superClass)
            ))
        }
        psiClass.interfaces.forEach { iface ->
            superTypes.add(TypeRef(
                name = iface.name ?: "",
                qualifiedName = iface.qualifiedName,
                location = getLocation(project, iface)
            ))
        }

        // Get sub classes
        val subTypes = ClassInheritorsSearch.search(psiClass, scope, true)
            .findAll()
            .mapNotNull { subClass ->
                TypeRef(
                    name = subClass.name ?: return@mapNotNull null,
                    qualifiedName = subClass.qualifiedName,
                    location = getLocation(project, subClass)
                )
            }

        val symbolKind = when {
            psiClass.isInterface -> SymbolKind.INTERFACE
            psiClass.isEnum -> SymbolKind.ENUM
            else -> SymbolKind.CLASS
        }

        return TypeHierarchy(
            typeName = psiClass.name ?: "",
            qualifiedName = psiClass.qualifiedName,
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
        val javaFile = getJavaFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(javaFile)
            ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getJavaFile(project: Project, filePath: String): PsiJavaFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PsiClass,
                is PsiMethod,
                is PsiField,
                is PsiParameter,
                is PsiLocalVariable,
                is PsiReferenceExpression -> return current
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

    private fun buildClassInfo(project: Project, psiClass: PsiClass): SymbolInfo {
        val kind = when {
            psiClass.isInterface -> SymbolKind.INTERFACE
            psiClass.isEnum -> SymbolKind.ENUM
            else -> SymbolKind.CLASS
        }

        return SymbolInfo(
            name = psiClass.name ?: "",
            kind = kind,
            language = languageId,
            qualifiedName = psiClass.qualifiedName,
            documentation = psiClass.docComment?.text,
            location = getLocation(project, psiClass),
            nameLocation = psiClass.nameIdentifier?.let { getLocation(project, it) },
            modifiers = getModifiers(psiClass),
            annotations = getAnnotations(psiClass),
            superTypes = buildSuperTypes(psiClass)
        )
    }

    private fun buildMethodInfo(project: Project, method: PsiMethod): SymbolInfo {
        return SymbolInfo(
            name = method.name,
            kind = SymbolKind.METHOD,
            language = languageId,
            qualifiedName = method.containingClass?.qualifiedName?.let { "$it.${method.name}" },
            signature = buildSignature(method),
            documentation = method.docComment?.text,
            location = getLocation(project, method),
            nameLocation = method.nameIdentifier?.let { getLocation(project, it) },
            returnType = method.returnType?.presentableText,
            parameters = method.parameterList.parameters.map { param ->
                ParameterInfo(
                    name = param.name,
                    type = param.type.presentableText,
                    defaultValue = null,
                    isOptional = false
                )
            },
            modifiers = getModifiers(method),
            annotations = getAnnotations(method)
        )
    }

    private fun buildFieldInfo(project: Project, field: PsiField): SymbolInfo {
        val kind = if (field.hasModifierProperty("final") &&
                       field.hasModifierProperty("static")) {
            SymbolKind.CONSTANT
        } else {
            SymbolKind.FIELD
        }

        return SymbolInfo(
            name = field.name,
            kind = kind,
            language = languageId,
            qualifiedName = field.containingClass?.qualifiedName?.let { "$it.${field.name}" },
            location = getLocation(project, field),
            nameLocation = field.nameIdentifier?.let { getLocation(project, it) },
            returnType = field.type.presentableText,
            modifiers = getModifiers(field),
            annotations = getAnnotations(field)
        )
    }

    private fun buildParameterInfo(project: Project, param: PsiParameter): SymbolInfo {
        return SymbolInfo(
            name = param.name,
            kind = SymbolKind.PARAMETER,
            language = languageId,
            location = getLocation(project, param),
            returnType = param.type.presentableText,
            annotations = getAnnotations(param)
        )
    }

    private fun buildLocalVariableInfo(project: Project, variable: PsiLocalVariable): SymbolInfo {
        return SymbolInfo(
            name = variable.name,
            kind = SymbolKind.VARIABLE,
            language = languageId,
            location = getLocation(project, variable),
            returnType = variable.type.presentableText
        )
    }

    private fun buildClassNode(project: Project, psiClass: PsiClass): SymbolNode {
        val children = mutableListOf<SymbolNode>()

        // Methods
        psiClass.methods.forEach { method ->
            children.add(SymbolNode(buildMethodInfo(project, method)))
        }

        // Fields
        psiClass.fields.forEach { field ->
            children.add(SymbolNode(buildFieldInfo(project, field)))
        }

        // Inner classes
        psiClass.innerClasses.forEach { inner ->
            children.add(buildClassNode(project, inner))
        }

        return SymbolNode(
            symbol = buildClassInfo(project, psiClass),
            children = children
        )
    }

    private fun buildSignature(method: PsiMethod): String {
        val modifiers = method.modifierList.text.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
        val typeParams = method.typeParameters.takeIf { it.isNotEmpty() }?.let { params ->
            "<${params.joinToString(", ") { it.name ?: "" }}> "
        } ?: ""
        val returnType = method.returnType?.presentableText?.let { "$it " } ?: ""
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.type.presentableText} ${param.name}"
        }
        val throws = method.throwsList.referencedTypes.takeIf { it.isNotEmpty() }?.let { types ->
            " throws ${types.joinToString(", ") { it.presentableText }}"
        } ?: ""

        return "$modifiers$typeParams$returnType${method.name}($params)$throws"
    }

    private fun buildSuperTypes(psiClass: PsiClass): List<String> {
        val superTypes = mutableListOf<String>()
        psiClass.superClass?.qualifiedName?.let { superTypes.add(it) }
        psiClass.interfaces.forEach { iface ->
            iface.qualifiedName?.let { superTypes.add(it) }
        }
        return superTypes
    }

    private fun getModifiers(element: PsiModifierListOwner?): List<String>? {
        val modifierList = element?.modifierList ?: return null
        val modifiers = mutableListOf<String>()
        val modifierNames = listOf(
            "public", "private", "protected",
            "static", "final", "abstract",
            "synchronized", "volatile", "transient",
            "native", "strictfp", "default"
        )
        modifierNames.forEach { modifier ->
            if (modifierList.hasModifierProperty(modifier)) {
                modifiers.add(modifier)
            }
        }
        return modifiers.takeIf { it.isNotEmpty() }
    }

    private fun getAnnotations(element: PsiModifierListOwner?): List<String>? {
        val modifierList = element?.modifierList ?: return null
        val annotations = modifierList.annotations.mapNotNull { annotation ->
            annotation.text
        }
        return annotations.takeIf { it.isNotEmpty() }
    }
}

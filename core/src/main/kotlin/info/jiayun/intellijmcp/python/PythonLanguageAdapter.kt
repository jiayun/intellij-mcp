package info.jiayun.intellijmcp.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import com.jetbrains.python.psi.stubs.PyFunctionNameIndex
import com.jetbrains.python.psi.stubs.PyVariableNameIndex
import com.jetbrains.python.psi.search.PyClassInheritorsSearch
import info.jiayun.intellijmcp.api.*

class PythonLanguageAdapter : LanguageAdapter {

    override val languageId = "python"
    override val languageDisplayName = "Python"
    override val supportedExtensions = setOf("py", "pyi")

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        // Find Class
        if (kind == null || kind == SymbolKind.CLASS) {
            PyClassNameIndex.find(name, project, scope).forEach { pyClass ->
                results.add(buildClassInfo(project, pyClass))
            }
        }

        // Find Function
        if (kind == null || kind == SymbolKind.FUNCTION || kind == SymbolKind.METHOD) {
            PyFunctionNameIndex.find(name, project, scope).forEach { pyFunc ->
                results.add(buildFunctionInfo(project, pyFunc))
            }
        }

        // Find Variable
        if (kind == null || kind == SymbolKind.VARIABLE) {
            PyVariableNameIndex.find(name, project, scope).forEach { pyVar ->
                results.add(buildVariableInfo(project, pyVar))
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
        val pyFile = getPyFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = pyFile.findElementAt(offset)
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
        val pyFile = getPyFile(project, filePath) ?: return null
        val element = pyFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        return when (targetElement) {
            is PyClass -> buildClassInfo(project, targetElement)
            is PyFunction -> buildFunctionInfo(project, targetElement)
            is PyTargetExpression -> buildVariableInfo(project, targetElement)
            is PyNamedParameter -> buildParameterInfo(project, targetElement)
            else -> null
        }
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val pyFile = getPyFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()

        // Extract imports
        pyFile.importTargets.forEach { target ->
            getLocation(project, target)?.let { loc ->
                imports.add(ImportInfo(
                    module = target.importedQName?.toString() ?: "",
                    names = null,
                    alias = target.asName,
                    location = loc
                ))
            }
        }

        pyFile.fromImports.forEach { fromImport ->
            getLocation(project, fromImport)?.let { loc ->
                imports.add(ImportInfo(
                    module = fromImport.importSourceQName?.toString() ?: "",
                    names = fromImport.importElements.mapNotNull { it.visibleName },
                    alias = null,
                    location = loc
                ))
            }
        }

        // Extract top-level classes
        pyFile.topLevelClasses.forEach { pyClass ->
            symbols.add(buildClassNode(project, pyClass))
        }

        // Extract top-level functions
        pyFile.topLevelFunctions.forEach { pyFunc ->
            symbols.add(SymbolNode(buildFunctionInfo(project, pyFunc)))
        }

        // Extract top-level variables
        pyFile.topLevelAttributes.forEach { attr ->
            symbols.add(SymbolNode(buildVariableInfo(project, attr)))
        }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            moduleName = pyFile.name.removeSuffix(".py").removeSuffix(".pyi"),
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
        val classes = PyClassNameIndex.find(typeName.substringAfterLast('.'), project, scope)
        val pyClass = classes.find { it.qualifiedName == typeName }
            ?: classes.firstOrNull()
            ?: return null

        // Get super classes
        val superTypes = pyClass.getSuperClasses(null).mapNotNull { superClass ->
            TypeRef(
                name = superClass.name ?: return@mapNotNull null,
                qualifiedName = superClass.qualifiedName,
                location = getLocation(project, superClass)
            )
        }

        // Get sub classes
        val subTypes = PyClassInheritorsSearch.search(pyClass, true)
            .findAll()
            .mapNotNull { subClass ->
                TypeRef(
                    name = subClass.name ?: return@mapNotNull null,
                    qualifiedName = subClass.qualifiedName,
                    location = getLocation(project, subClass)
                )
            }

        return TypeHierarchy(
            typeName = pyClass.name ?: "",
            qualifiedName = pyClass.qualifiedName,
            kind = SymbolKind.CLASS,
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
        val pyFile = getPyFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(pyFile)
            ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getPyFile(project: Project, filePath: String): PyFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? PyFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PyClass,
                is PyFunction,
                is PyTargetExpression,
                is PyNamedParameter,
                is PyReferenceExpression -> return current
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

    private fun buildClassInfo(project: Project, pyClass: PyClass): SymbolInfo {
        return SymbolInfo(
            name = pyClass.name ?: "",
            kind = SymbolKind.CLASS,
            language = languageId,
            qualifiedName = pyClass.qualifiedName,
            documentation = pyClass.docStringValue,
            location = getLocation(project, pyClass),
            nameLocation = pyClass.nameIdentifier?.let { getLocation(project, it) },
            decorators = pyClass.decoratorList?.decorators?.map { it.text },
            superTypes = pyClass.superClassExpressions.mapNotNull { it.text }
        )
    }

    private fun buildFunctionInfo(project: Project, pyFunc: PyFunction): SymbolInfo {
        val isMethod = pyFunc.containingClass != null

        return SymbolInfo(
            name = pyFunc.name ?: "",
            kind = if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION,
            language = languageId,
            qualifiedName = pyFunc.qualifiedName,
            signature = buildSignature(pyFunc),
            documentation = pyFunc.docStringValue,
            location = getLocation(project, pyFunc),
            nameLocation = pyFunc.nameIdentifier?.let { getLocation(project, it) },
            returnType = pyFunc.annotation?.text,
            parameters = pyFunc.parameterList.parameters.map { param ->
                ParameterInfo(
                    name = param.name ?: "",
                    type = (param as? PyNamedParameter)?.annotation?.text,
                    defaultValue = (param as? PyNamedParameter)?.defaultValue?.text,
                    isOptional = (param as? PyNamedParameter)?.hasDefaultValue() ?: false
                )
            },
            decorators = pyFunc.decoratorList?.decorators?.map { it.text }
        )
    }

    private fun buildVariableInfo(project: Project, pyVar: PyTargetExpression): SymbolInfo {
        return SymbolInfo(
            name = pyVar.name ?: "",
            kind = SymbolKind.VARIABLE,
            language = languageId,
            qualifiedName = pyVar.qualifiedName,
            location = getLocation(project, pyVar),
            returnType = pyVar.annotation?.text
        )
    }

    private fun buildParameterInfo(project: Project, param: PyNamedParameter): SymbolInfo {
        return SymbolInfo(
            name = param.name ?: "",
            kind = SymbolKind.PARAMETER,
            language = languageId,
            location = getLocation(project, param),
            returnType = param.annotation?.text
        )
    }

    private fun buildClassNode(project: Project, pyClass: PyClass): SymbolNode {
        val children = mutableListOf<SymbolNode>()

        // Methods
        pyClass.methods.forEach { method ->
            children.add(SymbolNode(buildFunctionInfo(project, method)))
        }

        // Class attributes
        pyClass.classAttributes.forEach { attr ->
            children.add(SymbolNode(buildVariableInfo(project, attr)))
        }

        // Nested classes
        pyClass.nestedClasses.forEach { nested ->
            children.add(buildClassNode(project, nested))
        }

        return SymbolNode(
            symbol = buildClassInfo(project, pyClass),
            children = children
        )
    }

    private fun buildSignature(pyFunc: PyFunction): String {
        val asyncPrefix = if (pyFunc.isAsync) "async " else ""
        val params = pyFunc.parameterList.parameters.joinToString(", ") { param: PyParameter ->
            buildString {
                append(param.name ?: "_")
                if (param is PyNamedParameter) {
                    param.annotation?.let { anno -> append(": ${anno.text}") }
                    param.defaultValue?.let { defVal -> append(" = ${defVal.text}") }
                }
            }
        }
        val returnType = pyFunc.annotation?.text?.let { " -> $it" } ?: ""
        return "${asyncPrefix}def ${pyFunc.name}($params)$returnType"
    }
}

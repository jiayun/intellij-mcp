package info.jiayun.intellijmcp.javascript

import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import info.jiayun.intellijmcp.api.*

class JavaScriptLanguageAdapter : LanguageAdapter {

    override val languageId = "javascript"
    override val languageDisplayName = "JavaScript/TypeScript"
    override val supportedExtensions = setOf(
        "js", "mjs", "cjs",     // JavaScript
        "ts", "mts", "cts",     // TypeScript
        "jsx",                   // React JSX
        "tsx"                    // React TSX
    )

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        // Search through all JS/TS files in the project
        supportedExtensions.forEach { ext ->
            FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { virtualFile ->
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? JSFile ?: return@forEach

                // Find classes
                if (kind == null || kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE) {
                    PsiTreeUtil.findChildrenOfType(psiFile, JSClass::class.java)
                        .filter { it.name == name }
                        .forEach { results.add(buildClassInfo(project, it)) }
                }

                // Find functions
                if (kind == null || kind == SymbolKind.FUNCTION || kind == SymbolKind.METHOD) {
                    PsiTreeUtil.findChildrenOfType(psiFile, JSFunction::class.java)
                        .filter { it.name == name }
                        .forEach { results.add(buildFunctionInfo(project, it)) }
                }

                // Find variables
                if (kind == null || kind == SymbolKind.VARIABLE || kind == SymbolKind.CONSTANT) {
                    PsiTreeUtil.findChildrenOfType(psiFile, JSVariable::class.java)
                        .filter { it.name == name }
                        .forEach { results.add(buildVariableInfo(project, it)) }
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
        val jsFile = getJsFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = jsFile.findElementAt(offset)
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
        val jsFile = getJsFile(project, filePath) ?: return null
        val element = jsFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        return when (targetElement) {
            is JSClass -> buildClassInfo(project, targetElement)
            is JSFunction -> buildFunctionInfo(project, targetElement)
            is JSVariable -> buildVariableInfo(project, targetElement)
            is JSParameter -> buildParameterInfo(project, targetElement)
            else -> null
        }
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val jsFile = getJsFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()

        // Find all top-level classes
        PsiTreeUtil.findChildrenOfType(jsFile, JSClass::class.java)
            .filter { it.parent == jsFile || it.parent?.parent == jsFile }
            .forEach { symbols.add(buildClassNode(project, it)) }

        // Find all top-level functions
        PsiTreeUtil.findChildrenOfType(jsFile, JSFunction::class.java)
            .filter { it.parent == jsFile || it.parent?.parent == jsFile }
            .filter { it !is JSFunctionExpression || it.name != null }
            .forEach { symbols.add(SymbolNode(buildFunctionInfo(project, it))) }

        // Find all top-level variables
        jsFile.statements
            .filterIsInstance<JSVarStatement>()
            .flatMap { it.variables.toList() }
            .forEach { symbols.add(SymbolNode(buildVariableInfo(project, it))) }

        // Find imports by text analysis
        jsFile.statements
            .filter { it.text.trimStart().startsWith("import ") }
            .forEach { importStmt ->
                getLocation(project, importStmt)?.let { loc ->
                    imports.add(ImportInfo(
                        module = extractModuleFromImport(importStmt.text),
                        names = extractNamesFromImport(importStmt.text),
                        alias = null,
                        location = loc
                    ))
                }
            }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = null,
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

        // Find the class by searching through all JS/TS files
        var targetClass: JSClass? = null
        val shortName = typeName.substringAfterLast('.')

        outer@ for (ext in supportedExtensions) {
            for (virtualFile in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? JSFile ?: continue

                val found = PsiTreeUtil.findChildrenOfType(psiFile, JSClass::class.java)
                    .find { it.name == shortName }

                if (found != null) {
                    targetClass = found
                    break@outer
                }
            }
        }

        val jsClass = targetClass ?: return null

        // Get super types by text analysis
        val superTypes = extractSuperTypes(jsClass)

        // Find subtypes
        val subTypes = mutableListOf<TypeRef>()
        supportedExtensions.forEach { ext ->
            FilenameIndex.getAllFilesByExt(project, ext, scope).forEach { virtualFile ->
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? JSFile ?: return@forEach

                PsiTreeUtil.findChildrenOfType(psiFile, JSClass::class.java).forEach { candidateClass ->
                    val candidateSuperTypes = extractSuperTypes(candidateClass)
                    val extendsTarget = candidateSuperTypes.any { it.name == shortName }

                    if (extendsTarget && candidateClass != jsClass) {
                        subTypes.add(TypeRef(
                            name = candidateClass.name ?: return@forEach,
                            qualifiedName = null,
                            location = getLocation(project, candidateClass)
                        ))
                    }
                }
            }
        }

        return TypeHierarchy(
            typeName = jsClass.name ?: "",
            qualifiedName = null,
            kind = if (jsClass.isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS,
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
        val jsFile = getJsFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(jsFile)
            ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getJsFile(project: Project, filePath: String): JSFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? JSFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is JSClass,
                is JSFunction,
                is JSVariable,
                is JSParameter,
                is JSReferenceExpression -> return current
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

    private fun buildClassInfo(project: Project, jsClass: JSClass): SymbolInfo {
        val kind = if (jsClass.isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS
        val superTypes = extractSuperTypes(jsClass).map { it.name }

        return SymbolInfo(
            name = jsClass.name ?: "",
            kind = kind,
            language = languageId,
            qualifiedName = null,
            documentation = extractDocComment(jsClass),
            location = getLocation(project, jsClass),
            nameLocation = jsClass.nameIdentifier?.let { getLocation(project, it) },
            modifiers = extractModifiers(jsClass),
            superTypes = superTypes.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildFunctionInfo(project: Project, jsFunction: JSFunction): SymbolInfo {
        val isMethod = jsFunction.parent is JSClass ||
                       (jsFunction.parent?.parent is JSClass)

        return SymbolInfo(
            name = jsFunction.name ?: "<anonymous>",
            kind = if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION,
            language = languageId,
            qualifiedName = null,
            signature = buildSignature(jsFunction),
            documentation = extractDocComment(jsFunction),
            location = getLocation(project, jsFunction),
            nameLocation = jsFunction.nameIdentifier?.let { getLocation(project, it) },
            returnType = jsFunction.returnType?.resolvedTypeText,
            parameters = jsFunction.parameters.map { param ->
                ParameterInfo(
                    name = param.name ?: "",
                    type = param.jsType?.resolvedTypeText,
                    defaultValue = param.initializer?.text,
                    isOptional = param.isOptional
                )
            },
            modifiers = extractModifiers(jsFunction)
        )
    }

    private fun buildVariableInfo(project: Project, jsVariable: JSVariable): SymbolInfo {
        val kind = when {
            jsVariable.isConst -> SymbolKind.CONSTANT
            else -> SymbolKind.VARIABLE
        }

        return SymbolInfo(
            name = jsVariable.name ?: "",
            kind = kind,
            language = languageId,
            qualifiedName = null,
            documentation = extractDocComment(jsVariable),
            location = getLocation(project, jsVariable),
            nameLocation = jsVariable.nameIdentifier?.let { getLocation(project, it) },
            returnType = jsVariable.jsType?.resolvedTypeText,
            modifiers = extractModifiers(jsVariable)
        )
    }

    private fun buildParameterInfo(project: Project, param: JSParameter): SymbolInfo {
        return SymbolInfo(
            name = param.name ?: "",
            kind = SymbolKind.PARAMETER,
            language = languageId,
            location = getLocation(project, param),
            returnType = param.jsType?.resolvedTypeText
        )
    }

    private fun buildClassNode(project: Project, jsClass: JSClass): SymbolNode {
        val children = mutableListOf<SymbolNode>()

        // Methods
        jsClass.functions.forEach { method ->
            children.add(SymbolNode(buildFunctionInfo(project, method)))
        }

        // Fields
        PsiTreeUtil.findChildrenOfType(jsClass, JSVariable::class.java)
            .filter { it.parent?.parent == jsClass }
            .forEach { field ->
                children.add(SymbolNode(buildVariableInfo(project, field)))
            }

        return SymbolNode(
            symbol = buildClassInfo(project, jsClass),
            children = children
        )
    }

    private fun buildSignature(jsFunction: JSFunction): String {
        val async = if (jsFunction.isAsync) "async " else ""
        val name = jsFunction.name ?: "anonymous"
        val params = jsFunction.parameters.joinToString(", ") { param ->
            buildString {
                append(param.name ?: "_")
                param.jsType?.let { append(": ${it.resolvedTypeText}") }
                param.initializer?.let { append(" = ${it.text}") }
            }
        }
        val returnType = jsFunction.returnType?.resolvedTypeText?.let { ": $it" } ?: ""

        return "$async$name($params)$returnType"
    }

    private fun extractModifiers(element: PsiElement): List<String>? {
        val modifiers = mutableListOf<String>()
        val text = element.text

        // Extract modifiers by text analysis
        if (text.contains("export ")) modifiers.add("export")
        if (text.contains("export default ")) modifiers.add("default")
        if (text.contains("static ")) modifiers.add("static")
        if (text.contains("async ")) modifiers.add("async")
        if (text.contains("public ")) modifiers.add("public")
        if (text.contains("private ")) modifiers.add("private")
        if (text.contains("protected ")) modifiers.add("protected")
        if (text.contains("readonly ")) modifiers.add("readonly")
        if (text.contains("abstract ")) modifiers.add("abstract")

        return modifiers.takeIf { it.isNotEmpty() }
    }

    private fun extractDocComment(element: PsiElement): String? {
        // Look for JSDoc comment before the element
        var sibling = element.prevSibling
        while (sibling != null) {
            val text = sibling.text.trim()
            if (text.startsWith("/**") && text.endsWith("*/")) {
                return text
            }
            if (text.isNotEmpty() && !text.startsWith("//") && !text.startsWith("/*")) {
                break
            }
            sibling = sibling.prevSibling
        }
        return null
    }

    private fun extractSuperTypes(jsClass: JSClass): List<TypeRef> {
        val superTypes = mutableListOf<TypeRef>()
        val text = jsClass.text

        // Extract extends clause
        val extendsRegex = """extends\s+(\w+)""".toRegex()
        extendsRegex.find(text)?.groupValues?.get(1)?.let { superName ->
            superTypes.add(TypeRef(name = superName, qualifiedName = null, location = null))
        }

        // Extract implements clause
        val implementsRegex = """implements\s+([\w\s,]+)""".toRegex()
        implementsRegex.find(text)?.groupValues?.get(1)?.let { implementsList ->
            implementsList.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { interfaceName ->
                superTypes.add(TypeRef(name = interfaceName, qualifiedName = null, location = null))
            }
        }

        return superTypes
    }

    private fun extractModuleFromImport(importText: String): String {
        // Extract module path from import statement
        // e.g., "import { foo } from 'module'" -> "module"
        val regex = """from\s+['"]([^'"]+)['"]""".toRegex()
        return regex.find(importText)?.groupValues?.get(1) ?: ""
    }

    private fun extractNamesFromImport(importText: String): List<String>? {
        // Extract imported names from import statement
        // e.g., "import { foo, bar } from 'module'" -> ["foo", "bar"]
        val regex = """\{\s*([^}]+)\s*\}""".toRegex()
        val match = regex.find(importText) ?: return null
        return match.groupValues[1]
            .split(",")
            .map { it.trim().split(" ").first() }
            .filter { it.isNotEmpty() }
    }
}

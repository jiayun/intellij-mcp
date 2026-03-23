package info.jiayun.intellijmcp.rust

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

// Rust PSI API imports
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

class RustLanguageAdapter : LanguageAdapter {

    override val languageId = "rust"
    override val languageDisplayName = "Rust"
    override val supportedExtensions = setOf("rs")

    // ===== Find Symbol =====

    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?,
        includeLibraries: Boolean
    ): List<SymbolInfo> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()

        // Search through all Rust files in the project
        FilenameIndex.getAllFilesByExt(project, "rs", scope).forEach { virtualFile ->
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? RsFile ?: return@forEach

            // Find matching named elements
            PsiTreeUtil.findChildrenOfType(psiFile, RsNamedElement::class.java)
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

    private fun getSymbolKind(element: RsNamedElement): SymbolKind {
        return when (element) {
            is RsStructItem -> SymbolKind.CLASS
            is RsEnumItem -> SymbolKind.ENUM
            is RsTraitItem -> SymbolKind.TRAIT
            is RsFunction -> {
                val isMethod = element.parent?.parent is RsImplItem || element.parent?.parent is RsTraitItem
                if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION
            }
            is RsConstant -> SymbolKind.CONSTANT
            is RsModItem -> SymbolKind.MODULE
            is RsTypeAlias -> SymbolKind.CLASS
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
        val rsFile = getRsFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val element = rsFile.findElementAt(offset)
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
        val rsFile = getRsFile(project, filePath) ?: return null
        val element = rsFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null

        return when (targetElement) {
            is RsStructItem -> buildSymbolInfo(project, targetElement, SymbolKind.CLASS)
            is RsEnumItem -> buildSymbolInfo(project, targetElement, SymbolKind.ENUM)
            is RsTraitItem -> buildSymbolInfo(project, targetElement, SymbolKind.TRAIT)
            is RsFunction -> {
                val isMethod = targetElement.owner is RsAbstractableOwner.Impl ||
                               targetElement.owner is RsAbstractableOwner.Trait
                buildSymbolInfo(project, targetElement, if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION)
            }
            is RsConstant -> buildSymbolInfo(project, targetElement, SymbolKind.CONSTANT)
            is RsModItem -> buildSymbolInfo(project, targetElement, SymbolKind.MODULE)
            is RsTypeAlias -> buildSymbolInfo(project, targetElement, SymbolKind.CLASS)
            else -> null
        }
    }

    // ===== Get File Symbols =====

    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val rsFile = getRsFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")

        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()

        // Extract use statements
        rsFile.childrenOfType<RsUseItem>().forEach { useItem ->
            getLocation(project, useItem)?.let { loc ->
                imports.add(ImportInfo(
                    module = useItem.text.removePrefix("use ").removeSuffix(";").trim(),
                    names = null,
                    alias = null,
                    location = loc
                ))
            }
        }

        // Extract top-level items
        rsFile.children.forEach { child ->
            when (child) {
                is RsStructItem -> buildStructNode(project, child)?.let { symbols.add(it) }
                is RsEnumItem -> buildEnumNode(project, child)?.let { symbols.add(it) }
                is RsTraitItem -> buildTraitNode(project, child)?.let { symbols.add(it) }
                is RsImplItem -> buildImplNode(project, child)?.let { symbols.add(it) }
                is RsFunction -> buildSymbolInfo(project, child, SymbolKind.FUNCTION)?.let {
                    symbols.add(SymbolNode(it))
                }
                is RsConstant -> buildSymbolInfo(project, child, SymbolKind.CONSTANT)?.let {
                    symbols.add(SymbolNode(it))
                }
                is RsModItem -> buildModuleNode(project, child)?.let { symbols.add(it) }
            }
        }

        return FileSymbols(
            filePath = filePath,
            language = languageId,
            packageName = null, // Rust uses crates/modules, not packages
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

        // Find the type by searching all Rust files
        var targetType: RsNamedElement? = null

        FilenameIndex.getAllFilesByExt(project, "rs", scope).forEach fileLoop@{ virtualFile ->
            if (targetType != null) return@fileLoop
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? RsFile ?: return@fileLoop

            PsiTreeUtil.findChildrenOfType(psiFile, RsNamedElement::class.java)
                .find { it.name == typeName && (it is RsStructItem || it is RsEnumItem || it is RsTraitItem) }
                ?.let { targetType = it }
        }

        val target = targetType ?: return null

        val kind = when (target) {
            is RsStructItem -> SymbolKind.CLASS
            is RsEnumItem -> SymbolKind.ENUM
            is RsTraitItem -> SymbolKind.TRAIT
            else -> SymbolKind.CLASS
        }

        // For now, return empty hierarchy (finding trait impls requires more complex logic)
        val superTypes = mutableListOf<TypeRef>()
        val subTypes = mutableListOf<TypeRef>()

        return TypeHierarchy(
            typeName = target.name ?: "",
            qualifiedName = (target as? RsQualifiedNamedElement)?.qualifiedName,
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
        val rsFile = getRsFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(rsFile) ?: return null

        if (line < 0 || line >= document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column

        return if (offset <= lineEndOffset) offset else null
    }

    // ===== Helper Methods =====

    private fun getRsFile(project: Project, filePath: String): RsFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? RsFile
    }

    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is RsStructItem,
                is RsEnumItem,
                is RsTraitItem,
                is RsFunction,
                is RsConstant,
                is RsModItem,
                is RsTypeAlias,
                is RsNamedElement -> return current
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

    private fun buildSymbolInfo(project: Project, element: RsNamedElement, kind: SymbolKind): SymbolInfo? {
        val name = element.name ?: return null

        return SymbolInfo(
            name = name,
            kind = kind,
            language = languageId,
            qualifiedName = (element as? RsQualifiedNamedElement)?.qualifiedName,
            signature = buildSignature(element),
            documentation = getDocumentation(element),
            location = getLocation(project, element),
            nameLocation = (element as? RsNameIdentifierOwner)?.nameIdentifier?.let { getLocation(project, it) },
            returnType = when (element) {
                is RsFunction -> element.retType?.typeReference?.text
                is RsConstant -> element.typeReference?.text
                else -> null
            },
            parameters = when (element) {
                is RsFunction -> element.valueParameters.map { param ->
                    ParameterInfo(
                        name = param.patText ?: "",
                        type = param.typeReference?.text,
                        defaultValue = null,
                        isOptional = false
                    )
                }
                else -> null
            },
            modifiers = getModifiers(element),
            annotations = getAttributes(element)
        )
    }

    private fun buildSignature(element: RsNamedElement): String? {
        return when (element) {
            is RsFunction -> {
                val vis = element.vis?.text?.let { "$it " } ?: ""
                val async = if (element.isAsync) "async " else ""
                val unsafe = if (element.isUnsafe) "unsafe " else ""
                val fnKeyword = "fn "
                val name = element.name ?: ""
                val generics = element.typeParameterList?.text ?: ""
                val params = element.valueParameterList?.text ?: "()"
                val ret = element.retType?.text ?: ""
                "$vis$async$unsafe$fnKeyword$name$generics$params$ret"
            }
            is RsStructItem -> {
                val vis = element.vis?.text?.let { "$it " } ?: ""
                "struct ${element.name}"
            }
            is RsEnumItem -> {
                val vis = element.vis?.text?.let { "$it " } ?: ""
                "enum ${element.name}"
            }
            is RsTraitItem -> {
                val vis = element.vis?.text?.let { "$it " } ?: ""
                val unsafe = if (element.isUnsafe) "unsafe " else ""
                "${vis}${unsafe}trait ${element.name}"
            }
            is RsConstant -> {
                val vis = element.vis?.text?.let { "$it " } ?: ""
                val keyword = if (element.isConst) "const" else "static"
                val name = element.name ?: ""
                val type = element.typeReference?.text?.let { ": $it" } ?: ""
                "$vis$keyword $name$type"
            }
            else -> null
        }
    }

    private fun getModifiers(element: RsNamedElement): List<String>? {
        val modifiers = mutableListOf<String>()

        when (element) {
            is RsFunction -> {
                if (element.isAsync) modifiers.add("async")
                if (element.isUnsafe) modifiers.add("unsafe")
                if (element.isConst) modifiers.add("const")
                element.vis?.text?.let { modifiers.add(it) }
            }
            is RsTraitItem -> {
                if (element.isUnsafe) modifiers.add("unsafe")
                element.vis?.text?.let { modifiers.add(it) }
            }
            is RsStructItem, is RsEnumItem -> {
                (element as? RsVisibilityOwner)?.vis?.text?.let { modifiers.add(it) }
            }
        }

        return modifiers.takeIf { it.isNotEmpty() }
    }

    private fun getAttributes(element: RsNamedElement): List<String>? {
        // Extract outer attributes from the element
        val attrs = mutableListOf<String>()
        var sibling = element.prevSibling
        while (sibling != null) {
            if (sibling is RsOuterAttr) {
                attrs.add(0, sibling.text)
            } else if (sibling.text.isNotBlank() && sibling !is com.intellij.psi.PsiWhiteSpace) {
                break
            }
            sibling = sibling.prevSibling
        }
        return attrs.takeIf { it.isNotEmpty() }
    }

    private fun getDocumentation(element: PsiElement): String? {
        // Extract documentation from preceding doc comments
        val docLines = mutableListOf<String>()
        var sibling = element.prevSibling
        while (sibling != null) {
            val text = sibling.text
            when {
                text.startsWith("///") -> docLines.add(0, text.removePrefix("///").trim())
                text.startsWith("//!") -> docLines.add(0, text.removePrefix("//!").trim())
                text.isBlank() || sibling is com.intellij.psi.PsiWhiteSpace -> { /* skip whitespace */ }
                sibling is RsOuterAttr -> { /* skip attributes */ }
                else -> break
            }
            sibling = sibling.prevSibling
        }
        return docLines.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun buildStructNode(project: Project, struct: RsStructItem): SymbolNode? {
        val info = buildSymbolInfo(project, struct, SymbolKind.CLASS) ?: return null
        val children = mutableListOf<SymbolNode>()

        // Add fields
        struct.blockFields?.namedFieldDeclList?.forEach { field ->
            buildSymbolInfo(project, field, SymbolKind.FIELD)?.let { children.add(SymbolNode(it)) }
        }

        return SymbolNode(info, children)
    }

    private fun buildEnumNode(project: Project, enum: RsEnumItem): SymbolNode? {
        val info = buildSymbolInfo(project, enum, SymbolKind.ENUM) ?: return null
        val children = mutableListOf<SymbolNode>()

        // Add variants
        enum.enumBody?.enumVariantList?.forEach { variant ->
            buildSymbolInfo(project, variant, SymbolKind.FIELD)?.let { children.add(SymbolNode(it)) }
        }

        return SymbolNode(info, children)
    }

    private fun buildTraitNode(project: Project, trait: RsTraitItem): SymbolNode? {
        val info = buildSymbolInfo(project, trait, SymbolKind.TRAIT) ?: return null
        val children = mutableListOf<SymbolNode>()

        // Add trait methods
        trait.members?.childrenOfType<RsFunction>()?.forEach { fn ->
            buildSymbolInfo(project, fn, SymbolKind.METHOD)?.let { children.add(SymbolNode(it)) }
        }

        return SymbolNode(info, children)
    }

    private fun buildImplNode(project: Project, impl: RsImplItem): SymbolNode? {
        val typeName = impl.typeReference?.text ?: return null
        val traitRef = impl.traitRef?.text?.let { "impl $it for $typeName" } ?: "impl $typeName"

        val info = SymbolInfo(
            name = traitRef,
            kind = SymbolKind.CLASS,
            language = languageId,
            location = getLocation(project, impl)
        )

        val children = mutableListOf<SymbolNode>()
        impl.members?.childrenOfType<RsFunction>()?.forEach { fn ->
            buildSymbolInfo(project, fn, SymbolKind.METHOD)?.let { children.add(SymbolNode(it)) }
        }

        return SymbolNode(info, children)
    }

    private fun buildModuleNode(project: Project, mod: RsModItem): SymbolNode? {
        val info = buildSymbolInfo(project, mod, SymbolKind.MODULE) ?: return null
        return SymbolNode(info)
    }
}

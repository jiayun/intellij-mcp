package info.jiayun.intellijmcp.intelligence

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import info.jiayun.intellijmcp.api.LocationInfo
import java.lang.reflect.InvocationTargetException

/** Only platform types appear in this class' signatures. Optional APIs live behind NativeHierarchy. */
open class IdeIntelligenceBackend(protected val language: String) : IntelligenceBackend {
    override fun capabilities(project: Project?): IntelligenceCapabilities {
        val limitations = when(language) {
            "vue" -> listOf("script/script setup only; template relationships excluded")
            "rust" -> listOf("Native hierarchy when installed; older plugins: resolved direct calls only, no function pointers, dynamic dispatch or macro expansion")
            "kotlin" -> listOf("K2 hierarchy; cross-language behavior follows installed Kotlin plugin")
            else -> emptyList()
        }
        val status = if (project != null && com.intellij.openapi.project.DumbService.isDumb(project)) "not_ready" else "available"
        return IntelligenceCapabilities(Capability(status,"IDE CodeSmellDetector",listOf("Enabled IDE inspections only; CodeSmellDetector exposes warning/error diagnostics")),
            Capability(status,"IDE DefinitionsScopedSearch",limitations),
            Capability(status,"IDE native hierarchy",limitations),Capability(status,"IDE native hierarchy",limitations))
    }

    override fun diagnostics(context: AnalysisContext): DiagnosticsResult {
        checkSdk(context)
        psiRead { requirePsi(context) }
        val smells = CodeSmellDetector.getInstance(context.project).findCodeSmells(listOf(context.file))
        context.checkFresh()
        val diagnostics = smells.map { smell ->
            val severity = when {
                smell.severity >= HighlightSeverity.ERROR -> "error"
                smell.severity >= HighlightSeverity.WARNING -> "warning"
                smell.severity >= HighlightSeverity.INFORMATION -> "information"
                else -> "hint"
            }
            val range = smell.textRange
            DiagnosticInfo(severity,smell.description,location(context.file.path,context.text,range.startOffset,range.endOffset),"IDE inspections")
        }.filter { severityRank(it.severity) >= severityRank(context.options.minSeverity) }
            .distinct().sortedWith(compareBy({it.location.line},{it.location.column},{it.message}))
        val lowerSeverity = severityRank(context.options.minSeverity) < severityRank("warning")
        return DiagnosticsResult(context.file.path,if(lowerSeverity) AnalysisStatus.partial else AnalysisStatus.complete,
            if(lowerSeverity) "CodeSmellDetector only guarantees warning/error analysis" else null,
            diagnostics=diagnostics,backend="IDE CodeSmellDetector")
    }

    override fun implementations(context: AnalysisContext): ImplementationsResult {
        val target = resolveTarget(context)
        val root = psiRead { symbol(target) }
        val results = linkedMapOf<String,IntelligenceSymbol>()
        var truncated = false
        var status = AnalysisStatus.complete
        var reason: String? = null
        try {
            // Query executors own their read actions (Dart may wait on Analysis Server here).
            val query = psiRead { DefinitionsScopedSearch.search(target,scope(context),true) }
            query.forEach(com.intellij.util.Processor { element ->
                context.deadline.check()
                ProgressManager.checkCanceled()
                psiRead {
                    if (!element.isValid) throw AnalysisFailure(AnalysisStatus.partial,"Implementation invalidated during search")
                    if (inScope(context,element)) {
                        val info = symbol(element)
                        if (info.id != root.id) {
                            if (info.id !in results && results.size >= context.options.limit) truncated = true
                            else results[info.id] = info
                        }
                    }
                }
                !truncated
            })
            context.checkFresh()
        } catch(e: Exception) { status = if(results.isEmpty()) failureStatus(e) else AnalysisStatus.partial; reason = failureMessage(e) }
        if(truncated) { status = AnalysisStatus.partial; reason = "limit reached" }
        return ImplementationsResult(status,reason,root,results.values.sortedBy { it.id },truncated,"IDE DefinitionsScopedSearch")
    }

    override fun calls(context: AnalysisContext): CallHierarchyResult {
        val rootElement = resolveTarget(context)
        val root = psiRead { symbol(rootElement) }
        val elements = mutableMapOf(root.id to rootElement)
        var fallback = false
        val graph = CallGraph(context.options).build(root,context.deadline,"IDE native hierarchy") { current ->
            val element = elements.getValue(current.id)
            context.checkFresh()
            val tree = psiRead { NativeHierarchy.create(element,context.options.direction,context.options.includeLibraries) }
            if (tree == null) {
                if(language != "rust") throw AnalysisFailure(AnalysisStatus.unsupported,"No compatible native hierarchy for ${element.language.id} symbol")
                fallback = true
                return@build psiRead {
                    directCalls(element,context).map { (next,site) ->
                        val info = symbol(next); elements[info.id] = next
                        info to listOfNotNull(site?.let { elementLocation(it) })
                    }
                }
            }
            // Dart's hierarchy invokes its server; it must not inherit our read lock.
            val children = if(language == "dart") tree.getChildElements(tree.baseDescriptor)
                else psiRead { tree.getChildElements(tree.baseDescriptor) }
            psiRead {
                children.mapNotNull { child ->
                    if(child !is HierarchyNodeDescriptor) throw AnalysisFailure(AnalysisStatus.partial,"Hierarchy backend returned ${child}")
                    val next = NativeHierarchy.element(child)
                        ?: throw AnalysisFailure(AnalysisStatus.partial,"Hierarchy node has no declaration")
                    if(!inScope(context,next)) return@mapNotNull null
                    val info = symbol(next); elements[info.id] = next
                    info to NativeHierarchy.callSites(child).mapNotNull { elementLocation(it) }
                }
            }
        }
        return if(fallback) graph.copy(status=if(graph.status == AnalysisStatus.complete) AnalysisStatus.partial else graph.status,
            reason=listOfNotNull(graph.reason,"Resolved direct calls only; function pointers, dynamic dispatch and macro expansions excluded").joinToString("; "),
            backend="Rust PSI direct calls") else graph
    }

    protected open fun directCalls(element: PsiElement, context: AnalysisContext): List<Pair<PsiElement,PsiElement?>> =
        throw AnalysisFailure(AnalysisStatus.unsupported,"No direct-call fallback")

    protected open fun resolveTarget(context: AnalysisContext): PsiElement = psiRead { target(context) }

    private fun requirePsi(context: AnalysisContext): PsiFile {
        val file = PsiManager.getInstance(context.project).findFile(context.file)
            ?: throw AnalysisFailure(AnalysisStatus.not_ready,"PSI is unavailable")
        if(file.language == com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE)
            throw AnalysisFailure(AnalysisStatus.not_ready,"Language plugin has not provided a parser for this file")
        return file
    }
    protected open fun checkBackendReady(context: AnalysisContext) {}

    private fun checkSdk(context: AnalysisContext) {
        checkBackendReady(context)
        if(language !in setOf("java","kotlin","python")) return
        val configured = psiRead {
            val module = com.intellij.openapi.module.ModuleUtilCore.findModuleForFile(context.file,context.project)
            module != null && com.intellij.openapi.roots.ModuleRootManager.getInstance(module).sdk != null
        }
        if(!configured) throw AnalysisFailure(AnalysisStatus.not_ready,"No SDK configured for the file's module")
    }

    protected fun target(context: AnalysisContext): PsiElement {
        checkSdk(context)
        if(language == "vue" && !Regex("<script\\b[^>]*>([\\s\\S]*?)</script\\s*>",RegexOption.IGNORE_CASE)
                .findAll(context.text).any { it.groups[1]!!.range.contains(context.offset) })
            throw AnalysisFailure(AnalysisStatus.unsupported,"Vue call and implementation queries support script/script setup only")
        val file = requirePsi(context)
        // View providers preserve Vue's original offsets for embedded script PSI.
        val files = file.viewProvider.allFiles.sortedBy { if(it.language.id.lowercase() in listOf("javascript","typescript")) 0 else 1 }
        for (view in files) {
            val reference = view.findReferenceAt(context.offset)
            if(reference != null) {
                val resolved = if(reference is PsiPolyVariantReference) {
                    val targets = reference.multiResolve(false).filter { it.isValidResult }.mapNotNull { it.element }.map { normalize(resolvedDeclaration(it).navigationElement) }.distinct()
                    if(targets.size > 1) throw AnalysisFailure(AnalysisStatus.partial,"Ambiguous reference resolves to multiple declarations: "+targets.joinToString { "${it.javaClass.simpleName}@${it.containingFile?.name}:${it.textOffset}" })
                    targets.singleOrNull()
                } else reference.resolve()
                if(resolved != null) return normalize(resolvedDeclaration(resolved).navigationElement)
                throw AnalysisFailure(AnalysisStatus.not_ready,"Reference cannot be resolved; check SDK and indexes")
            }
            val leaf = view.findElementAt(context.offset) ?: continue
            var e: PsiElement? = leaf
            while(e != null && e !is PsiFile) {
                val name = declarationName(e)
                if(name != null && name.textRange.containsOffset(context.offset)) return normalize(e.navigationElement)
                if(e is PsiNamedElement && e.name != null && e.textOffset == leaf.textRange.startOffset && leaf.text == e.name)
                    return normalize(e.navigationElement)
                e = e.parent
            }
        }
        throw AnalysisFailure(AnalysisStatus.unsupported,"Place the cursor on a declaration name or resolvable reference")
    }
    private fun declarationName(element: PsiElement): PsiElement? {
        if(element is PsiNameIdentifierOwner) return element.nameIdentifier
        if(element !is PsiNamedElement) return null
        val accessor = element.javaClass.methods.firstOrNull {
            it.name in setOf("getNameIdentifier","getNameElement","getComponentName") && it.parameterCount == 0 &&
                PsiElement::class.java.isAssignableFrom(it.returnType)
        }
        return accessor?.invoke(element) as? PsiElement
    }
    protected open fun resolvedDeclaration(element: PsiElement): PsiElement = element
    private fun normalize(element: PsiElement): PsiElement {
        // Dart and Rust resolve to identifier PSI rather than its named declaration.
        val parent = element.parent
        return if(parent is PsiNamedElement && element is PsiNamedElement && parent.name == element.name) parent else element
    }
    protected fun symbol(element: PsiElement): IntelligenceSymbol {
        val e = normalize(element.navigationElement)
        val loc = elementLocation(declarationName(e) ?: e)
            ?: throw AnalysisFailure(AnalysisStatus.partial,"Declaration has no source location")
        val name = (e as? PsiNamedElement)?.name ?: e.text.take(80)
        val signature = e.text.substringBefore('{').lineSequence().take(6).joinToString(" ").take(500)
        return IntelligenceSymbol(name,signature,loc,e.language.id.lowercase())
    }
    protected fun scope(context: AnalysisContext) = if(context.options.includeLibraries) GlobalSearchScope.allScope(context.project) else GlobalSearchScope.projectScope(context.project)
    protected fun inScope(context: AnalysisContext, element: PsiElement): Boolean {
        val file = element.navigationElement.containingFile?.virtualFile ?: return false
        return context.options.includeLibraries || ProjectFileIndex.getInstance(context.project).isInContent(file)
    }
}

fun elementLocation(element: PsiElement): LocationInfo? {
    val file = element.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null
    return location(virtualFile.path,document.text,element.textRange.startOffset,element.textRange.endOffset)
}
fun location(path: String, text: String, start: Int, end: Int): LocationInfo {
    fun coordinate(offset: Int): Pair<Int,Int> {
        require(offset in 0..text.length) { "Backend returned an invalid source offset" }
        val line = text.take(offset).count { it == '\n' } + 1
        val previous = if(offset == 0) -1 else text.lastIndexOf('\n',offset-1)
        return line to offset-previous
    }
    val a = coordinate(start); val b = coordinate(end)
    return LocationInfo(path,a.first,a.second,b.first,b.second)
}

/** Version-sensitive native APIs are loaded only from the symbol's optional plugin classloader. */
internal object NativeHierarchy {
    private fun names(element: PsiElement, direction: String): List<String> {
        val side = if(direction == "incoming") "Caller" else "Callee"
        val id = element.language.id.lowercase()
        return when {
            id == "java" -> listOf("com.intellij.ide.hierarchy.call.${side}MethodsTreeStructure")
            id == "kotlin" -> listOf("org.jetbrains.kotlin.idea.k2.codeinsight.hierarchy.calls.Kotlin${side}TreeStructure")
            id in listOf("javascript","typescript","ecmascript 6","vuejs") -> listOf("com.intellij.lang.javascript.hierarchy.call.JS${side}MethodsTreeStructure")
            id == "python" -> listOf("com.jetbrains.python.hierarchy.call.Py${side}FunctionTreeStructure")
            id == "php" -> listOf("com.jetbrains.php.hierarchy.call.Php${side}MethodsTreeStructure")
            id == "go" -> listOf("com.goide.hierarchy.Go${side}HierarchyTreeStructure")
            id == "dart" -> listOf("com.jetbrains.lang.dart.ide.hierarchy.call.Dart${side}TreeStructure")
            id == "rust" -> listOf("org.rust.ide.hierarchy.Rs${side}TreeStructure")
            else -> emptyList()
        }
    }
    fun create(element: PsiElement,direction: String,libraries: Boolean): HierarchyTreeStructure? {
        for(name in names(element,direction)) {
            val provider = com.intellij.ide.hierarchy.LanguageCallHierarchy.INSTANCE.forLanguage(element.language)
            val loaders = listOfNotNull(provider?.javaClass?.classLoader,element.javaClass.classLoader).distinct()
            val clazz = loaders.firstNotNullOfOrNull { loader ->
                try { Class.forName(name,false,loader) } catch(_: ClassNotFoundException) { null }
                catch(e: LinkageError) { throw AnalysisFailure(AnalysisStatus.unsupported,"Incompatible hierarchy API: ${e.message}") }
            } ?: continue
            for(ctor in clazz.constructors) {
                val args = ctor.parameterTypes.map { type -> when {
                    Project::class.java.isAssignableFrom(type) -> element.project
                    PsiElement::class.java.isAssignableFrom(type) && type.isInstance(element) -> element
                    type == String::class.java -> if(libraries) "All" else "Project"
                    type == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }}
                if(args.any { it == null }) continue
                try { return ctor.newInstance(*args.toTypedArray()) as HierarchyTreeStructure }
                catch(e: InvocationTargetException) { throw (e.targetException as? RuntimeException ?: e) }
            }
        }
        return null
    }
    fun element(descriptor: HierarchyNodeDescriptor): PsiElement? {
        val method = descriptor.javaClass.methods.firstOrNull { it.name == "getEnclosingElement" && it.parameterCount == 0 }
        return (method?.invoke(descriptor) as? PsiElement) ?: descriptor.psiElement
    }
    fun callSites(descriptor: HierarchyNodeDescriptor): List<PsiElement> {
        // Only use publicly exposed call-site accessors; never mine private descriptor state.
        val method = descriptor.javaClass.methods.firstOrNull { it.name == "getReferences" && it.parameterCount == 0 } ?: return emptyList()
        val values = when(val value = method.invoke(descriptor)) { is Array<*> -> value.toList(); is Collection<*> -> value.toList(); else -> emptyList() }
        return values.mapNotNull { (it as? PsiReference)?.element }
    }
}

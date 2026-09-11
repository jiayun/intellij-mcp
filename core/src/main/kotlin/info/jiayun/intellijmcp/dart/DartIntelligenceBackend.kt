package info.jiayun.intellijmcp.dart

import com.google.dart.server.*
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService
import com.jetbrains.lang.dart.psi.DartCallExpression
import com.jetbrains.lang.dart.psi.DartComponent
import org.dartlang.analysis.server.protocol.*
import info.jiayun.intellijmcp.intelligence.*
import info.jiayun.intellijmcp.intelligence.AnalysisStatus
import java.util.concurrent.CompletableFuture

/** Uses the IDE-owned Analysis Server; all response waits happen outside IDE read actions. */
class DartIntelligenceBackend : IdeIntelligenceBackend("dart") {
    override fun capabilities(project: Project?): IntelligenceCapabilities {
        val capability = Capability("available","Dart Analysis Server",listOf("Requires configured Dart SDK and analysis roots"))
        return IntelligenceCapabilities(capability,capability,capability,capability)
    }
    private fun readyServer(context: AnalysisContext): DartAnalysisServerService {
        val server = DartAnalysisServerService.getInstance(context.project)
        if(!psiRead { server.serverReadyForRequest() && server.isInIncludedRoots(context.file) })
            throw AnalysisFailure(AnalysisStatus.not_ready,"Dart Analysis Server is not ready; configure Dart SDK and analysis roots")
        server.updateFilesContent()
        return server
    }
    private fun params(server: DartAnalysisServerService,file: VirtualFile,offset: Int? = null) = psiRead {
        JsonObject().apply {
            addProperty("file",server.getFileUri(file))
            if(offset != null) addProperty("offset",server.getOriginalOffset(file,offset))
        }
    }
    private fun failure(error: RequestError) = AnalysisFailure(AnalysisStatus.error,error.toString())
    private fun send(server: DartAnalysisServerService,method: String,params: JsonObject,consumer: Consumer) {
        // The service's low-level API takes a request ID and the entire protocol envelope.
        val id = "mcp-intelligence-"+java.util.UUID.randomUUID()
        val request = JsonObject().apply { addProperty("id",id); addProperty("method",method); add("params",params) }
        server.sendRequestToServer(id,request,consumer)
    }
    private fun navigation(context: AnalysisContext,server: DartAnalysisServerService,file: VirtualFile,offset: Int,length: Int): List<NavigationRegion> {
        val future = CompletableFuture<List<NavigationRegion>>()
        send(server,"analysis.getNavigation",params(server,file,offset).apply { addProperty("length",psiRead { server.getOriginalOffset(file,offset+length)-server.getOriginalOffset(file,offset) }) },object: GetNavigationConsumer {
            override fun computedNavigation(regions: List<NavigationRegion>) { future.complete(regions) }
            override fun onError(error: RequestError) { future.completeExceptionally(failure(error)) }
        })
        return context.deadline.await(future)
    }
    private fun declaration(context: AnalysisContext,path: String,offset: Int): PsiElement {
        val normalized = if(path.startsWith("file:")) lspUriToPath(path) else path
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)
            ?: throw AnalysisFailure(AnalysisStatus.not_ready,"Dart declaration file unavailable: $path")
        return psiRead {
            val psi = PsiManager.getInstance(context.project).findFile(file)
                ?: throw AnalysisFailure(AnalysisStatus.not_ready,"Dart declaration PSI unavailable")
            val converted = DartAnalysisServerService.getInstance(context.project).getConvertedOffset(file,offset)
            val leaf = psi.findElementAt(converted) ?: throw AnalysisFailure(AnalysisStatus.partial,"Invalid Dart declaration offset")
            PsiTreeUtil.getParentOfType(leaf,DartComponent::class.java,false)
                ?: throw AnalysisFailure(AnalysisStatus.unsupported,"Dart target is not a declaration")
        }
    }
    override fun resolveTarget(context: AnalysisContext): PsiElement {
        val server = readyServer(context)
        val targets = navigation(context,server,context.file,context.offset,0).flatMap { it.targetObjects }.distinct()
        context.checkFresh()
        if(targets.size > 1) throw AnalysisFailure(AnalysisStatus.partial,"Multiple Dart navigation targets")
        val target = targets.singleOrNull()
        if(target != null) return declaration(context,target.file,target.offset)
        // A declaration name may have no navigation region. Do not resolve references under this read lock.
        return psiRead {
            val leaf = PsiManager.getInstance(context.project).findFile(context.file)?.findElementAt(context.offset)
            val owner = PsiTreeUtil.getParentOfType(leaf,DartComponent::class.java,false)
            if(owner?.componentName?.textRange?.containsOffset(context.offset) == true) owner
            else throw AnalysisFailure(AnalysisStatus.not_ready,"Dart navigation target is not ready")
        }
    }
    override fun implementations(context: AnalysisContext): ImplementationsResult {
        val server = readyServer(context)
        val target = resolveTarget(context)
        val root = psiRead { symbol(target) }
        val future = CompletableFuture<List<TypeHierarchyItem>?>()
        val (file,offset) = psiRead { target.containingFile.virtualFile to (target as DartComponent).componentName!!.textOffset }
        send(server,"search.getTypeHierarchy",params(server,file,offset).apply { addProperty("superOnly",false) },object: GetTypeHierarchyConsumer {
            override fun computedHierarchy(items: List<TypeHierarchyItem>?) { future.complete(items) }
            override fun onError(error: RequestError) { future.completeExceptionally(failure(error)) }
        })
        val items = context.deadline.await(future)
            ?: throw AnalysisFailure(AnalysisStatus.unsupported,"No Dart type hierarchy for this symbol")
        if(items.isEmpty()) throw AnalysisFailure(AnalysisStatus.not_ready,"Dart hierarchy has no target")
        val queue = ArrayDeque<Int>(); queue.addAll(items[0].subclasses.toList())
        val visited = mutableSetOf(0)
        val results = linkedMapOf<String,IntelligenceSymbol>()
        var truncated = false
        while(queue.isNotEmpty()) {
            context.deadline.check()
            val index = queue.removeFirst()
            if(!visited.add(index)) continue
            val item = items[index]
            queue.addAll(item.subclasses.toList())
            val element = if(items[0].memberElement != null) item.memberElement else item.classElement
            if(element == null) continue
            val psi = declaration(context,element.location.file,element.location.offset)
            psiRead {
                val info = symbol(psi)
                if(info.id != root.id && inScope(context,psi)) results[info.id] = info
            }
            if(results.size > context.options.limit) { truncated = true; break }
        }
        context.checkFresh()
        return ImplementationsResult(if(truncated) AnalysisStatus.partial else AnalysisStatus.complete,
            if(truncated) "limit reached" else null,root,results.values.sortedBy { it.id }.take(context.options.limit),truncated,"Dart Analysis Server")
    }
    private fun references(context: AnalysisContext,server: DartAnalysisServerService,file: VirtualFile,offset: Int): List<SearchResult> {
        val future = CompletableFuture<List<SearchResult>>()
        val lock = Any()
        var requestId: String? = null
        val batches = mutableMapOf<String,MutableList<SearchResult>>()
        val finished = mutableSetOf<String>()
        val listener = object: AnalysisServerListenerAdapter() {
            override fun computedSearchResults(id: String,results: List<SearchResult>,last: Boolean) = synchronized(lock) {
                batches.getOrPut(id) { mutableListOf() }.addAll(results)
                if(last) finished.add(id)
                if(id == requestId && id in finished) future.complete(batches.getValue(id).toList())
                Unit
            }
        }
        server.addAnalysisServerListener(listener)
        try {
            send(server,"search.findElementReferences",params(server,file,offset).apply { addProperty("includePotential",false) },object: FindElementReferencesConsumer {
                override fun computedElementReferences(id: String?,element: Element?) = synchronized(lock) {
                    requestId = id
                    if(id == null) {
                        if(element == null) future.completeExceptionally(AnalysisFailure(AnalysisStatus.not_ready,"Dart reference target unavailable"))
                        else future.complete(emptyList())
                    } else if(id in finished) future.complete(batches[id].orEmpty().toList())
                    Unit
                }
                override fun onError(error: RequestError) { future.completeExceptionally(failure(error)) }
            })
            return context.deadline.await(future)
        } finally { server.removeAnalysisServerListener(listener) }
    }
    override fun calls(context: AnalysisContext): CallHierarchyResult {
        val server = readyServer(context)
        val target = resolveTarget(context)
        val root = psiRead { symbol(target) }
        val declarations = mutableMapOf(root.id to target)
        return CallGraph(context.options).build(root,context.deadline,"Dart Analysis Server") { current ->
            context.checkFresh()
            val element = declarations.getValue(current.id)
            val (file,offset) = psiRead { element.containingFile.virtualFile to (element as DartComponent).componentName!!.textOffset }
            val next = mutableListOf<Pair<PsiElement,info.jiayun.intellijmcp.api.LocationInfo>>()
            if(context.options.direction == "incoming") {
                for(ref in references(context,server,file,offset).filter { it.kind == "INVOCATION" && !it.isPotential }) {
                    val caller = ref.path.firstOrNull { it.kind in setOf("FUNCTION","METHOD","CONSTRUCTOR","GETTER","SETTER") } ?: continue
                    val psi = declaration(context,caller.location.file,caller.location.offset)
                    val loc = ref.location
                    next.add(psi to info.jiayun.intellijmcp.api.LocationInfo(loc.file,loc.startLine,loc.startColumn,loc.startLine,loc.startColumn+loc.length))
                }
            } else {
                val (range,callRanges) = psiRead {
                    element.textRange to PsiTreeUtil.collectElementsOfType(element,DartCallExpression::class.java)
                        .filter { call -> generateSequence(call.parent) { it.parent }.takeWhile { it != element }.none {
                            it is com.jetbrains.lang.dart.psi.DartFunctionExpression ||
                                it is com.jetbrains.lang.dart.psi.DartFunctionDeclarationWithBody ||
                                it is com.jetbrains.lang.dart.psi.DartMethodDeclaration
                        } }.mapNotNull { it.expression?.textRange }
                }
                for(region in navigation(context,server,file,range.startOffset,range.length)) {
                    val converted = psiRead { server.getConvertedOffset(file,region.offset) }
                    if(callRanges.none { it.containsOffset(converted) }) continue
                    for(nav in region.targetObjects.filter { it.kind in setOf("FUNCTION","METHOD","CONSTRUCTOR","GETTER","SETTER") }) {
                        val psi = declaration(context,nav.file,nav.offset)
                        val loc = psiRead { val doc = com.intellij.psi.PsiDocumentManager.getInstance(context.project).getDocument(element.containingFile)!!
                            location(file.path,doc.text,converted,server.getConvertedOffset(file,region.offset+region.length)) }
                        next.add(psi to loc)
                    }
                }
            }
            psiRead { next.filter { inScope(context,it.first) }.map { (psi,site) ->
                val info = symbol(psi); declarations[info.id] = psi; info to listOf(site)
            } }
        }
    }
    override fun diagnostics(context: AnalysisContext): DiagnosticsResult {
        val server = readyServer(context)
        val future = CompletableFuture<Array<AnalysisError>>()
        send(server,"analysis.getErrors",params(server,context.file),object: GetErrorsConsumer {
            override fun computedErrors(errors: Array<AnalysisError>) { future.complete(errors) }
            override fun onError(error: RequestError) { future.completeExceptionally(failure(error)) }
        })
        val errors = context.deadline.await(future)
        context.checkFresh()
        return DiagnosticsResult(context.file.path,AnalysisStatus.complete,diagnostics=errors.map { error ->
            val severity = when(error.severity) { "ERROR" -> "error"; "WARNING" -> "warning"; else -> "information" }
            val start = psiRead { server.getConvertedOffset(context.file,error.location.offset) }
            val end = psiRead { server.getConvertedOffset(context.file,error.location.offset+error.location.length) }
            DiagnosticInfo(severity,error.message,location(context.file.path,context.text,start,end),"Dart Analysis Server: ${error.code}")
        }.filter { severityRank(it.severity) >= severityRank(context.options.minSeverity) }
            .distinct().sortedWith(compareBy({it.location.line},{it.location.column},{it.message})),backend="Dart Analysis Server")
    }
}

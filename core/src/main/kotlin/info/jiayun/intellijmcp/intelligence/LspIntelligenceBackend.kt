package info.jiayun.intellijmcp.intelligence

import com.intellij.openapi.project.Project
import info.jiayun.intellijmcp.api.LocationInfo
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageServer
import java.io.File
import java.net.URI

class LspIntelligenceBackend(private val language: String,
    private val peek: (Project) -> LspIntelligenceState?,
    private val connect: (Project,Deadline) -> Pair<LanguageServer,LspIntelligenceState>) : IntelligenceBackend {
    override fun capabilities(project: Project?): IntelligenceCapabilities {
        val state = project?.let(peek)
        fun cap(supported: Boolean?, limitations: List<String> = emptyList()) = Capability(
            when(supported) { true -> "available"; false -> "unsupported"; null -> "unknown" },"$language LSP",limitations)
        return IntelligenceCapabilities(cap(state?.capabilities?.let { true },listOf("Pull preferred; unversioned push results are partial")),
            cap(state?.supports("textDocument/implementation")),cap(state?.supports("textDocument/prepareCallHierarchy")),
            cap(state?.outgoingSupported(),if(language == "csharp") listOf("csharp-ls outgoing requires a reported version >= 0.27.0; OmniSharp follows capabilities") else emptyList()))
    }
    private fun session(ctx: AnalysisContext): Triple<LanguageServer,LspIntelligenceState,Int> {
        val (server,state) = try { connect(ctx.project,ctx.deadline) } catch(e: IllegalStateException) {
            throw AnalysisFailure(AnalysisStatus.not_ready,e.message ?: "Language server is unavailable")
        }
        ctx.checkFresh()
        ctx.deadline.check()
        // Synchronize other unsaved files too: cross-file analysis must see the IDE's working copy.
        val unsaved = psiRead {
            val manager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            manager.unsavedDocuments.mapNotNull { doc ->
                val file = manager.getFile(doc) ?: return@mapNotNull null
                val ext = file.extension
                if((language == "swift" && ext == "swift" || language == "csharp" && ext == "cs") &&
                    com.intellij.openapi.roots.ProjectFileIndex.getInstance(ctx.project).isInContent(file)) file.path to doc.text else null
            }
        }
        unsaved.forEach { (path,text) -> state.sync(server,path,language,text) }
        return Triple(server,state,state.sync(server,ctx.file.path,language,ctx.text))
    }
    override fun diagnostics(context: AnalysisContext): DiagnosticsResult {
        val (server,state,version) = session(context)
        val uri = uri(context.file.path)
        val values: List<Diagnostic>
        var status = AnalysisStatus.complete
        var reason: String? = null
        if(state.supports("textDocument/diagnostic",context.file.path,language) == true) {
            val report = context.deadline.await(server.textDocumentService.diagnostic(DocumentDiagnosticParams(TextDocumentIdentifier(uri))))
                ?: throw AnalysisFailure(AnalysisStatus.partial,"Pull diagnostics returned null")
            if(report.isLeft) values = report.left.items
            else throw AnalysisFailure(AnalysisStatus.partial,"Server returned unchanged without a previous diagnostic result")
        } else {
            val report = state.awaitDiagnostics(uri,version,context.deadline)
            values = report.diagnostics
            if(report.version != version) { status = AnalysisStatus.partial; reason = "Push diagnostics has no matching document version; freshness is unknown" }
        }
        context.checkFresh()
        return DiagnosticsResult(context.file.path,status,reason,values.map { diagnostic ->
            val severity = when(diagnostic.severity) { DiagnosticSeverity.Error -> "error"; DiagnosticSeverity.Warning -> "warning";
                DiagnosticSeverity.Information -> "information"; null -> "warning"; else -> "hint" }
            DiagnosticInfo(severity,diagnostic.message,loc(uri,diagnostic.range),diagnostic.source ?: "$language LSP")
        }.filter { severityRank(it.severity) >= severityRank(context.options.minSeverity) }
            .distinct().sortedWith(compareBy({it.location.line},{it.location.column},{it.message})),"$language LSP")
    }
    override fun implementations(context: AnalysisContext): ImplementationsResult {
        val (server,state,_) = session(context)
        requireCapability(state.awaitSupport("textDocument/implementation",context.file.path,language,context.deadline),"implementation")
        val identifier = TextDocumentIdentifier(uri(context.file.path))
        val position = Position(context.line-1,context.column-1)
        // Definition resolution avoids excluding only the original usage location from the result.
        val definition = context.deadline.await(server.textDocumentService.definition(DefinitionParams(identifier,position)))
        val definitions = if(definition == null) emptyList() else if(definition.isLeft) definition.left
            else definition.right.map { Location(it.targetUri,it.targetSelectionRange) }
        // SourceKit definition on a protocol requirement can point to its implementation.
        // Only accept a document symbol's name range here, never its enclosing body.
        val declaration = run {
            val local = context.deadline.await(server.textDocumentService.documentSymbol(DocumentSymbolParams(identifier)))
            val candidates = mutableListOf<Location>()
            fun collect(item: DocumentSymbol) {
                val range = item.selectionRange
                if(range.start.line == position.line && range.end.line == position.line &&
                    position.character in range.start.character..range.end.character)
                    candidates.add(Location(identifier.uri,range))
                item.children.orEmpty().forEach(::collect)
            }
            local.orEmpty().filter { it.isRight }.forEach { collect(it.right) }
            candidates.singleOrNull() ?: definitions.singleOrNull() ?: throw AnalysisFailure(if(definitions.isEmpty()) AnalysisStatus.not_ready else AnalysisStatus.partial,"Cannot identify a unique target declaration; server index may still be loading")
        }
        val response = context.deadline.await(server.textDocumentService.implementation(ImplementationParams(identifier,position)))
            ?: throw AnalysisFailure(AnalysisStatus.partial,"Implementation request returned null")
        val locations = if(response.isLeft) response.left else response.right.map { Location(it.targetUri,it.targetSelectionRange) }
        val metadata = mutableMapOf<String,List<DocumentSymbol>>()
        var missingMetadata = false
        fun describe(location: Location): IntelligenceSymbol {
            val symbols = metadata.getOrPut(location.uri) {
                val requestUri = state.openedUri(location.uri) ?: run {
                    val path = File(URI(location.uri)).path
                    val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                        ?: throw AnalysisFailure(AnalysisStatus.not_ready,"Implementation source is unavailable: $path")
                    val text = psiRead { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)?.text }
                        ?: throw AnalysisFailure(AnalysisStatus.not_ready,"Implementation document is unavailable: $path")
                    state.sync(server,path,language,text)
                    uri(path)
                }
                val response = context.deadline.await(server.textDocumentService.documentSymbol(
                    DocumentSymbolParams(TextDocumentIdentifier(requestUri))))
                val result = mutableListOf<DocumentSymbol>()
                fun collect(item: DocumentSymbol) { result.add(item); item.children.orEmpty().forEach(::collect) }
                response.orEmpty().forEach { entry ->
                    if(entry.isRight) collect(entry.right)
                    else result.add(DocumentSymbol(entry.left.name,entry.left.kind,entry.left.location.range,entry.left.location.range))
                }
                result
            }
            fun contains(range: Range,p: Position): Boolean {
                fun compare(a: Position,b: Position) = if(a.line == b.line) a.character.compareTo(b.character) else a.line.compareTo(b.line)
                return compare(range.start,p) <= 0 && compare(p,range.end) <= 0
            }
            val match = symbols.filter { contains(it.range,location.range.start) }
                .minByOrNull { (it.range.end.line-it.range.start.line).toLong()*1_000_000 + it.range.end.character-it.range.start.character }
            if(match != null) return symbol(location.uri,match.selectionRange,match.name,match.detail)
            missingMetadata = true
            return symbol(location.uri,location.range,"<unnamed declaration>",null)
        }
        val target = describe(declaration)
        val unique = locations.filter { canonicalUri(it.uri) != canonicalUri(declaration.uri) || it.range.start != declaration.range.start }
            .filter { inScope(context,it.uri) }.distinctBy { "${canonicalUri(it.uri)}:${it.range.start}" }
            .sortedWith(compareBy({it.uri},{it.range.start.line},{it.range.start.character}))
        context.checkFresh()
        val truncated = unique.size > context.options.limit
        val implementations = mutableListOf<IntelligenceSymbol>()
        try { unique.take(context.options.limit).forEach { implementations.add(describe(it)) } }
        catch(e: Exception) {
            return ImplementationsResult(AnalysisStatus.partial,failureMessage(e),target,implementations,truncated,"$language LSP")
        }
        context.checkFresh()
        return ImplementationsResult(if(truncated || missingMetadata) AnalysisStatus.partial else AnalysisStatus.complete,
            listOfNotNull(if(truncated) "limit reached" else null,if(missingMetadata) "Some declarations have no symbol metadata" else null)
                .joinToString("; ").ifEmpty { null },target,implementations,truncated,"$language LSP")
    }
    override fun calls(context: AnalysisContext): CallHierarchyResult {
        val (server,state,_) = session(context)
        requireCapability(state.awaitSupport("textDocument/prepareCallHierarchy",context.file.path,language,context.deadline),"call hierarchy")
        if(context.options.direction == "outgoing") requireCapability(state.outgoingSupported(),"outgoing call hierarchy (csharp-ls requires reported version >= 0.27.0)")
        val roots = context.deadline.await(server.textDocumentService.prepareCallHierarchy(CallHierarchyPrepareParams(
            TextDocumentIdentifier(uri(context.file.path)),Position(context.line-1,context.column-1))))
        if(roots.isNullOrEmpty()) throw AnalysisFailure(AnalysisStatus.not_ready,"No prepared call hierarchy target; check symbol, SDK and index readiness")
        if(roots.size != 1) throw AnalysisFailure(AnalysisStatus.partial,"Multiple call hierarchy targets at this position")
        val root = roots.single()
        fun info(item: CallHierarchyItem) = symbol(item.uri,item.selectionRange,item.name,item.detail)
        val rootInfo = info(root)
        val items = mutableMapOf(rootInfo.id to root)
        return CallGraph(context.options).build(rootInfo,context.deadline,"$language LSP") { current ->
            val item = items.getValue(current.id)
            val next = if(context.options.direction == "incoming") {
                val calls = context.deadline.await(server.textDocumentService.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item)))
                    ?: throw AnalysisFailure(AnalysisStatus.partial,"Incoming hierarchy returned null")
                calls.map { Triple(it.from,it.from.uri,it.fromRanges) }
            } else {
                val calls = context.deadline.await(server.textDocumentService.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item)))
                    ?: throw AnalysisFailure(AnalysisStatus.partial,"Outgoing hierarchy returned null")
                calls.map { Triple(it.to,item.uri,it.fromRanges) }
            }
            context.checkFresh()
            next.filter { inScope(context,it.first.uri) }.map { (target,siteUri,ranges) ->
                val s = info(target); items[s.id] = target
                s to ranges.map { loc(siteUri,it) }
            }
        }
    }
    private fun requireCapability(value: Boolean?,name: String) {
        if(value != true) throw AnalysisFailure(if(value == null) AnalysisStatus.not_ready else AnalysisStatus.unsupported,"Server capability unavailable: $name")
    }
    private fun inScope(context: AnalysisContext,uri: String): Boolean {
        if(context.options.includeLibraries) return true
        val path = runCatching { File(URI(uri)).path }.getOrNull() ?: return false
        return psiRead {
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
            if(file != null && com.intellij.openapi.roots.ProjectFileIndex.getInstance(context.project).isInContent(file)) true
            else {
                // SourceKit canonicalizes macOS /var and symlink paths. The IDE may have opened
                // the same content root under its original spelling; don't discard those calls.
                val canonical = runCatching { File(path).canonicalFile.toPath() }.getOrNull()
                canonical != null && com.intellij.openapi.roots.ProjectRootManager.getInstance(context.project).contentRoots.any {
                    canonical.startsWith(File(it.path).canonicalFile.toPath())
                }
            }
        }
    }
    private fun symbol(uri: String,range: Range,name: String,signature: String?) = IntelligenceSymbol(name,signature,loc(uri,range),language)
    private fun canonicalUri(uri: String) = runCatching { File(URI(uri)).canonicalFile.toURI().toASCIIString() }.getOrDefault(uri)
    private fun uri(path: String) = lspDocumentUri(path)
    private fun loc(uri: String,range: Range): LocationInfo {
        val path = runCatching { File(URI(uri)).path }.getOrElse { uri }
        return LocationInfo(path,range.start.line+1,range.start.character+1,range.end.line+1,range.end.character+1)
    }
}

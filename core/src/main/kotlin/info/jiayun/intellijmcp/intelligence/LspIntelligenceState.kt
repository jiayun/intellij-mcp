package info.jiayun.intellijmcp.intelligence

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Session-scoped capability and document state. No IDE dependency, so protocol timing is testable. */
class LspIntelligenceState {
    @Volatile var capabilities: ServerCapabilities? = null
    @Volatile var serverInfo: ServerInfo? = null
    @Volatile var csharpLs = false
    private val registrations = ConcurrentHashMap<String,Registration>()
    private val capabilityWaiters = mutableListOf<CompletableFuture<Unit>>()
    data class Document(val version: Int,val text: String)
    private val documents = mutableMapOf<String,Document>()
    private val diagnostics = mutableMapOf<String,PublishDiagnosticsParams>()
    private val waiters = mutableMapOf<String,MutableList<CompletableFuture<PublishDiagnosticsParams>>>()
    @Synchronized fun register(params: RegistrationParams) {
        params.registrations.forEach { registrations[it.id] = it }
        capabilityWaiters.toList().forEach { it.complete(Unit) }; capabilityWaiters.clear()
    }
    fun unregister(params: UnregistrationParams) { params.unregisterations.forEach { registrations.remove(it.id) } }
    fun supports(method: String, path: String? = null, language: String? = null): Boolean? {
        val caps = capabilities ?: return null
        fun enabled(value: Either<Boolean,*>?) = value != null && (value.isRight || value.left == true)
        val static = when(method) {
            "textDocument/implementation" -> enabled(caps.implementationProvider)
            "textDocument/prepareCallHierarchy" -> enabled(caps.callHierarchyProvider)
            "textDocument/diagnostic" -> caps.diagnosticProvider != null
            else -> false
        }
        if(static) return true
        return registrations.values.any { it.method == method && (path == null || matches(it,path,language)) }
    }
    private fun matches(registration: Registration,path: String,language: String?): Boolean {
        val options = com.google.gson.Gson().toJsonTree(registration.registerOptions)
        if(!options.isJsonObject) return true
        val selectors = options.asJsonObject.get("documentSelector") ?: return true
        if(selectors.isJsonNull) return true
        if(!selectors.isJsonArray) return false
        return selectors.asJsonArray.any { entry ->
            if(!entry.isJsonObject) false else {
                val filter = entry.asJsonObject
                val lang = filter.get("language")?.asString
                val scheme = filter.get("scheme")?.asString
                val pattern = filter.get("pattern")
                (lang == null || lang == language) && (scheme == null || scheme == "file") &&
                    (pattern == null || pattern.isJsonPrimitive && runCatching {
                        java.nio.file.FileSystems.getDefault().getPathMatcher("glob:"+pattern.asString)
                            .matches(java.nio.file.Path.of(path))
                    }.getOrDefault(false))
            }
        }
    }
    fun outgoingSupported(): Boolean? {
        val supported = supports("textDocument/prepareCallHierarchy") ?: return null
        if(!supported || !csharpLs) return supported
        return versionAtLeast(serverInfo?.version,0,27,0)
    }
    fun awaitSupport(method: String,path: String,language: String,deadline: Deadline): Boolean? {
        // csharp-ls registers advertised dynamic capabilities after its asynchronous project load.
        // initialize has already returned at that point; absent static capabilities aren't final yet.
        val registrationWindow = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while(true) {
            val changed = CompletableFuture<Unit>()
            synchronized(this) {
                if(supports(method,path,language) == true) return true
                capabilityWaiters.add(changed)
            }
            try {
                if(csharpLs) deadline.await(changed)
                else changed.get(minOf(deadline.remainingMillis(),maxOf(1,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(registrationWindow-System.nanoTime()))),java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            catch(e: java.util.concurrent.TimeoutException) {
                if(!csharpLs) { deadline.check(); return supports(method,path,language) }
                throw AnalysisFailure(AnalysisStatus.not_ready,"csharp-ls has not registered $method; project initialization may still be running")
            } finally { synchronized(this) { capabilityWaiters.remove(changed) } }
        }
    }
    @Synchronized fun sync(server: LanguageServer,path: String,language: String,text: String): Int {
        val uri = lspDocumentUri(path)
        val previous = documents[uri]
        if(previous?.text == text) return previous.version
        val version = (previous?.version ?: 0)+1
        val next = Document(version,text)
        diagnostics.remove(uri)
        documents[uri] = next
        if(previous == null) server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri,language,version,text)))
        else {
            val sync = capabilities?.textDocumentSync
            val kind = if(sync?.isLeft == true) sync.left else sync?.right?.change
            val change = TextDocumentContentChangeEvent(text)
            if(kind == TextDocumentSyncKind.Incremental) {
                val lines = previous.text.split('\n')
                change.range = Range(Position(0,0),Position(lines.size-1,lines.last().length))
                change.rangeLength = previous.text.length
            } else if(kind == TextDocumentSyncKind.None || kind == null) {
                documents.remove(uri)
                server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
                throw AnalysisFailure(AnalysisStatus.unsupported,"Server does not support document changes")
            }
            server.textDocumentService.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri,version),listOf(change)))
        }
        return version
    }
    @Synchronized fun publish(params: PublishDiagnosticsParams) {
        val document = documents[params.uri] ?: return
        if(params.version != null && params.version != document.version) return
        diagnostics[params.uri] = params
        waiters.remove(params.uri)?.forEach { it.complete(params) }
    }
    fun awaitDiagnostics(uri: String,version: Int,deadline: Deadline): PublishDiagnosticsParams {
        val future = CompletableFuture<PublishDiagnosticsParams>()
        synchronized(this) {
            val cached = diagnostics[uri]
            if(cached != null && (cached.version == null || cached.version == version)) return cached
            waiters.getOrPut(uri) { mutableListOf() }.add(future)
        }
        return try { deadline.await(future) } finally { synchronized(this) {
            waiters[uri]?.remove(future)
            if(waiters[uri]?.isEmpty() == true) waiters.remove(uri)
        } }
    }
    @Synchronized fun close(server: LanguageServer?,path: String) {
        val uri = lspDocumentUri(path)
        if(documents.remove(uri) != null) server?.textDocumentService?.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
        diagnostics.remove(uri)
        waiters.remove(uri)?.forEach { it.cancel(false) }
    }
    fun openedUri(uri: String): String? {
        val open = synchronized(this) { documents.keys.toList() }
        if(uri in open) return uri
        val canonical = runCatching { File(java.net.URI(uri)).canonicalFile }.getOrNull() ?: return null
        return open.firstOrNull { runCatching { File(java.net.URI(it)).canonicalFile == canonical }.getOrDefault(false) }
    }
    @Synchronized fun clear(server: LanguageServer? = null) {
        documents.keys.toList().forEach { server?.textDocumentService?.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(it))) }
        documents.clear(); diagnostics.clear()
        waiters.values.flatten().forEach { it.cancel(false) }; waiters.clear()
        registrations.clear(); capabilities = null; serverInfo = null
        capabilityWaiters.toList().forEach { it.cancel(false) }; capabilityWaiters.clear()
    }
    companion object {
        fun versionAtLeast(version: String?,major: Int,minor: Int,patch: Int): Boolean? {
            val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(version ?: return null) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            val required = listOf(major,minor,patch)
            for(i in 0..2) if(parts[i] != required[i]) return parts[i] > required[i]
            return true
        }
    }
}

/** One physical file has one LSP identity, even through macOS /var or project symlinks. */
fun lspDocumentUri(path: String): String = File(path).canonicalFile.toPath().toUri().toASCIIString()

package info.jiayun.intellijmcp.intelligence

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeoutException
import kotlin.test.*

class LspIntelligenceStateTest {
    private val notifications = mutableListOf<Pair<String,Any>>()
    private val textService = Proxy.newProxyInstance(javaClass.classLoader,arrayOf(TextDocumentService::class.java)) { _,method,args ->
        notifications.add(method.name to args[0]); null
    } as TextDocumentService
    private val server = Proxy.newProxyInstance(javaClass.classLoader,arrayOf(LanguageServer::class.java)) { _,method,_ ->
        if(method.name == "getTextDocumentService") textService else null
    } as LanguageServer
    private val path = "/tmp/with space/Example.swift"
    private val uri = lspDocumentUri(path)
    private fun state() = LspIntelligenceState().apply {
        capabilities = ServerCapabilities().apply { textDocumentSync = Either.forLeft(TextDocumentSyncKind.Incremental) }
    }
    @Test fun `listing capabilities cannot start server and remains unknown before initialization`() {
        val state = LspIntelligenceState()
        assertNull(state.supports("textDocument/implementation"))
        assertNull(state.outgoingSupported())
        assertTrue(notifications.isEmpty())
    }
    @Test fun `documents open once and changes carry monotonic versions with full old range`() {
        val state = state()
        assertEquals(1,state.sync(server,path,"swift","😀\nold"))
        assertEquals(1,state.sync(server,path,"swift","😀\nold"))
        assertEquals(2,state.sync(server,path,"swift","fixed"))
        assertEquals(listOf("didOpen","didChange"),notifications.map { it.first })
        val open = notifications[0].second as DidOpenTextDocumentParams
        assertTrue(open.textDocument.uri.contains("%20"))
        val change = notifications[1].second as DidChangeTextDocumentParams
        assertEquals(2,change.textDocument.version)
        assertEquals(Range(Position(0,0),Position(1,3)),change.contentChanges.single().range)
        assertEquals("fixed",change.contentChanges.single().text)
    }
    @Test fun `stale diagnostics cannot resurrect an error after unsaved fix`() {
        val state = state()
        state.sync(server,path,"swift","broken")
        state.publish(PublishDiagnosticsParams(uri,listOf(Diagnostic(Range(Position(0,0),Position(0,1)),"old error")),1))
        assertEquals(1,state.awaitDiagnostics(uri,1,Deadline(100)).diagnostics.size)
        state.sync(server,path,"swift","fixed")
        state.publish(PublishDiagnosticsParams(uri,listOf(Diagnostic(Range(Position(0,0),Position(0,1)),"late old error")),1))
        assertFailsWith<TimeoutException> { state.awaitDiagnostics(uri,2,Deadline(5)) }
        state.publish(PublishDiagnosticsParams(uri,emptyList(),2))
        assertTrue(state.awaitDiagnostics(uri,2,Deadline(100)).diagnostics.isEmpty())
    }
    @Test fun `unversioned push retains unknown freshness`() {
        val state = state(); state.sync(server,path,"swift","text")
        state.publish(PublishDiagnosticsParams(uri,emptyList()))
        assertNull(state.awaitDiagnostics(uri,1,Deadline(100)).version)
    }
    @Test fun `dynamic registration and unregistration update capabilities`() {
        val state = state()
        assertFalse(state.supports("textDocument/implementation")!!)
        state.register(RegistrationParams(listOf(Registration("impl","textDocument/implementation"))))
        assertTrue(state.supports("textDocument/implementation")!!)
        state.unregister(UnregistrationParams(listOf(Unregistration("impl","textDocument/implementation"))))
        assertFalse(state.supports("textDocument/implementation")!!)
    }
    @Test fun `old csharp-ls outgoing is unsupported and unknown version stays unknown`() {
        val state = state(); state.csharpLs = true
        state.capabilities!!.callHierarchyProvider = Either.forLeft(true)
        state.serverInfo = ServerInfo("csharp-ls","0.26.0")
        assertFalse(state.outgoingSupported()!!)
        state.serverInfo = ServerInfo("csharp-ls","0.27.0")
        assertTrue(state.outgoingSupported()!!)
        state.serverInfo = ServerInfo("csharp-ls")
        assertNull(state.outgoingSupported())
        state.csharpLs = false
        assertTrue(state.outgoingSupported()!!)
    }
    @Test fun `close evicts diagnostics and reopening starts a new document lifecycle`() {
        val state = state(); state.sync(server,path,"swift","text")
        state.publish(PublishDiagnosticsParams(uri,emptyList(),1))
        state.close(server,path)
        assertEquals("didClose",notifications.last().first)
        assertEquals(1,state.sync(server,path,"swift","text"))
        assertFailsWith<TimeoutException> { state.awaitDiagnostics(uri,1,Deadline(5)) }
    }
    @Test fun `session reset clears capabilities and open documents`() {
        val state = state(); state.sync(server,path,"swift","text")
        state.clear(server)
        assertNull(state.capabilities)
        assertEquals("didClose",notifications.last().first)
    }
    @Test fun `dynamic registrations respect language and file selectors`() {
        val state = state()
        val options = com.google.gson.JsonParser.parseString("""{"documentSelector":[{"language":"swift","scheme":"file","pattern":"**/*.swift"}]}""")
        state.register(RegistrationParams(listOf(Registration("impl","textDocument/implementation",options))))
        assertTrue(state.supports("textDocument/implementation",path,"swift")!!)
        assertFalse(state.supports("textDocument/implementation","/tmp/a.cs","csharp")!!)
    }

    @Test fun `csharp initialization waits for asynchronous capability registration`() {
        val state = state().apply { csharpLs = true }
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            scheduler.schedule({ state.register(RegistrationParams(listOf(
                Registration("calls","textDocument/prepareCallHierarchy")))) },20,java.util.concurrent.TimeUnit.MILLISECONDS)
            assertTrue(state.awaitSupport("textDocument/prepareCallHierarchy","/tmp/a.cs","csharp",Deadline(2000))!!)
        } finally { scheduler.shutdownNow() }
    }

    @Test fun `macOS symlink aliases share one opened document identity`() {
        val directory = java.nio.file.Files.createTempDirectory("lsp-alias")
        try {
            val target = java.nio.file.Files.writeString(directory.resolve("source.swift"),"text")
            val alias = java.nio.file.Files.createSymbolicLink(directory.resolve("alias.swift"),target)
            val state = state()
            state.sync(server,target.toString(),"swift","text")
            state.sync(server,alias.toString(),"swift","text")
            assertEquals(1,notifications.size)
            assertEquals(lspDocumentUri(target.toString()),state.openedUri(alias.toUri().toASCIIString()))
            state.close(server,alias.toString())
            assertEquals("didClose",notifications.last().first)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `uninitialized csharp project reports not ready instead of unsupported`() {
        val state = state().apply { csharpLs = true }
        val failure = assertFailsWith<AnalysisFailure> {
            state.awaitSupport("textDocument/implementation","/tmp/a.cs","csharp",Deadline(5))
        }
        assertEquals(AnalysisStatus.not_ready,failure.status)
        state.csharpLs = false
        assertFalse(state.awaitSupport("textDocument/implementation","/tmp/a.cs","csharp",Deadline(3000))!!)
    }

}

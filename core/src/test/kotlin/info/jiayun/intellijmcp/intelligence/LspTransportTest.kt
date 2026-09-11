package info.jiayun.intellijmcp.intelligence

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.test.*

/** A controllable in-process server exercises real LSP4J framing and bidirectional messages. */
class LspTransportTest {
    @Test fun `hierarchy data implementations and diagnostic versions survive real JSON RPC transport`() {
        val state = LspIntelligenceState()
        val clientInput = PipedInputStream(65536)
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream(65536)
        val clientOutput = PipedOutputStream(serverInput)
        val executor = Executors.newCachedThreadPool()
        val uri = lspDocumentUri("/tmp/transport.swift")
        val range = Range(Position(2,3),Position(2,9))
        val item = CallHierarchyItem("callee",SymbolKind.Function,uri,range,range).apply {
            data = mapOf("opaque" to "server-key")
        }
        val opened = CompletableFuture<DidOpenTextDocumentParams>()
        val service = object : TextDocumentService {
            override fun didOpen(params: DidOpenTextDocumentParams) { opened.complete(params) }
            override fun didChange(params: DidChangeTextDocumentParams) {}
            override fun didClose(params: DidCloseTextDocumentParams) {}
            override fun didSave(params: DidSaveTextDocumentParams) {}
            override fun prepareCallHierarchy(params: CallHierarchyPrepareParams) = CompletableFuture.completedFuture(listOf(item))
            override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<List<CallHierarchyIncomingCall>> {
                assertTrue(params.item.data.toString().contains("server-key"))
                return CompletableFuture.completedFuture(listOf(CallHierarchyIncomingCall(item,listOf(range))))
            }
            override fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams) = CompletableFuture.completedFuture(listOf(CallHierarchyOutgoingCall(item,listOf(range))))
            override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>,List<LocationLink>>> =
                CompletableFuture.completedFuture(Either.forLeft(listOf(Location(uri,range))))
        }
        val localServer = object : LanguageServer {
            override fun initialize(params: InitializeParams) = CompletableFuture.completedFuture(InitializeResult(ServerCapabilities().apply {
                callHierarchyProvider = Either.forLeft(true)
                implementationProvider = Either.forLeft(true)
                textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
            }))
            override fun shutdown() = CompletableFuture.completedFuture<Any>(null)
            override fun exit() {}
            override fun getTextDocumentService() = service
            override fun getWorkspaceService() = object : WorkspaceService {
                override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}
                override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}
            }
        }
        val localClient = object : LanguageClient {
            override fun telemetryEvent(obj: Any?) {}
            override fun publishDiagnostics(params: PublishDiagnosticsParams) { state.publish(params) }
            override fun showMessage(params: MessageParams) {}
            override fun logMessage(params: MessageParams) {}
            override fun showMessageRequest(params: ShowMessageRequestParams) = CompletableFuture.completedFuture<MessageActionItem>(null)
            override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> { state.register(params); return CompletableFuture.completedFuture(null) }
            override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> { state.unregister(params); return CompletableFuture.completedFuture(null) }
        }
        val clientLauncher = Launcher.Builder<LanguageServer>().setLocalService(localClient).setRemoteInterface(LanguageServer::class.java)
            .setInput(clientInput).setOutput(clientOutput).setExecutorService(executor).create()
        val serverLauncher = Launcher.Builder<LanguageClient>().setLocalService(localServer).setRemoteInterface(LanguageClient::class.java)
            .setInput(serverInput).setOutput(serverOutput).setExecutorService(executor).create()
        val listening = listOf(clientLauncher.startListening(),serverLauncher.startListening())
        val deadline = Deadline(5000)
        try {
            val remote = clientLauncher.remoteProxy
            state.capabilities = deadline.await(remote.initialize(InitializeParams())).capabilities
            state.sync(remote,"/tmp/transport.swift","swift","text")
            assertEquals(1,deadline.await(opened).textDocument.version)
            serverLauncher.remoteProxy.publishDiagnostics(PublishDiagnosticsParams(uri,emptyList(),1))
            assertEquals(1,state.awaitDiagnostics(uri,1,deadline).version)
            val prepared = deadline.await(remote.textDocumentService.prepareCallHierarchy(CallHierarchyPrepareParams(TextDocumentIdentifier(uri),range.start))).single()
            assertEquals(range,deadline.await(remote.textDocumentService.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(prepared))).single().fromRanges.single())
            assertEquals(range,deadline.await(remote.textDocumentService.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(prepared))).single().fromRanges.single())
            assertEquals(uri,deadline.await(remote.textDocumentService.implementation(ImplementationParams(TextDocumentIdentifier(uri),range.start))).left.single().uri)
            deadline.await(serverLauncher.remoteProxy.registerCapability(RegistrationParams(listOf(Registration("diag","textDocument/diagnostic")))))
            assertTrue(state.supports("textDocument/diagnostic")!!)
            deadline.await(serverLauncher.remoteProxy.unregisterCapability(UnregistrationParams(listOf(Unregistration("diag","textDocument/diagnostic")))))
            assertFalse(state.supports("textDocument/diagnostic")!!)
        } finally {
            listening.forEach { it.cancel(true) }
            clientInput.close(); clientOutput.close(); serverInput.close(); serverOutput.close()
            executor.shutdownNow()
        }
    }
}

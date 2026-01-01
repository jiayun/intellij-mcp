package info.jiayun.intellijmcp.swift

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LSP client that manages connection to SourceKit-LSP
 */
class SwiftLspClient(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SwiftLspClient::class.java)

    private var process: Process? = null
    private var server: LanguageServer? = null
    private var initialized = false
    private var initializedAt: Long = 0
    private val openDocuments = mutableSetOf<String>()
    private var stderrReader: Thread? = null
    private var executorService: ExecutorService? = null

    companion object {
        /**
         * Check if running on macOS
         */
        fun isMacOS(): Boolean {
            return System.getProperty("os.name")?.lowercase()?.contains("mac") == true
        }

        /**
         * Find sourcekit-lsp binary path
         */
        fun findSourceKitLsp(): String? {
            if (!isMacOS()) return null

            // Try xcrun first (requires Xcode Command Line Tools)
            try {
                val process = ProcessBuilder("xcrun", "--find", "sourcekit-lsp")
                    .redirectErrorStream(true)
                    .start()
                val result = process.inputStream.bufferedReader().readText().trim()
                if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 && result.isNotEmpty()) {
                    if (File(result).exists()) {
                        return result
                    }
                }
            } catch (e: Exception) {
                // Ignore and try fallback paths
            }

            // Fallback: check common Swift toolchain locations
            val commonPaths = listOf(
                "/usr/bin/sourcekit-lsp",
                "/Library/Developer/Toolchains/swift-latest.xctoolchain/usr/bin/sourcekit-lsp",
                "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/sourcekit-lsp"
            )
            return commonPaths.find { File(it).exists() }
        }

        /**
         * Check if SourceKit-LSP is available
         */
        fun isAvailable(): Boolean {
            return isMacOS() && findSourceKitLsp() != null
        }
    }

    @Synchronized
    fun ensureInitialized(): LanguageServer {
        if (initialized && server != null && process?.isAlive == true) {
            return server!!
        }

        // Find sourcekit-lsp binary
        val sourcekitLspPath = findSourceKitLsp()
            ?: throw IllegalStateException("SourceKit-LSP not found. Please install Xcode or Swift toolchain.")

        logger.info("Starting SourceKit-LSP from: $sourcekitLspPath")

        // Start process
        val workDir = project.basePath?.let { File(it) }
        val processBuilder = ProcessBuilder(sourcekitLspPath)
            .apply { workDir?.let { directory(it) } }
            .redirectErrorStream(false)

        process = processBuilder.start()

        // Consume stderr in background to prevent buffer blocking
        stderrReader = Thread {
            try {
                process!!.errorStream.bufferedReader().forEachLine { line ->
                    logger.debug("SourceKit-LSP stderr: $line")
                }
            } catch (e: Exception) {
                // Process terminated, ignore
            }
        }.apply {
            name = "SourceKit-LSP-stderr-reader"
            isDaemon = true
            start()
        }

        // Create LSP launcher with dedicated thread pool
        val client = SwiftLanguageClient()
        executorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "SourceKit-LSP-worker").apply { isDaemon = true }
        }

        val launcher = Launcher.Builder<LanguageServer>()
            .setLocalService(client)
            .setRemoteInterface(LanguageServer::class.java)
            .setInput(process!!.inputStream)
            .setOutput(process!!.outputStream)
            .setExecutorService(executorService)
            .create()

        server = launcher.remoteProxy

        // Start listening for responses in background
        launcher.startListening()

        // Initialize server
        val initParams = InitializeParams().apply {
            rootUri = project.basePath?.let { "file://$it" }
            capabilities = ClientCapabilities().apply {
                textDocument = TextDocumentClientCapabilities().apply {
                    hover = HoverCapabilities()
                    definition = DefinitionCapabilities()
                    references = ReferencesCapabilities()
                    documentSymbol = DocumentSymbolCapabilities().apply {
                        hierarchicalDocumentSymbolSupport = true
                    }
                }
                workspace = WorkspaceClientCapabilities().apply {
                    symbol = SymbolCapabilities()
                }
            }
        }

        try {
            val initResult = server!!.initialize(initParams).get(30, TimeUnit.SECONDS)
            logger.info("SourceKit-LSP initialized: ${initResult.serverInfo?.name ?: "unknown"}")
            server!!.initialized(InitializedParams())
            initialized = true
            initializedAt = System.currentTimeMillis()

            // Wait for LSP to be ready (indexing may take time)
            waitForIndexing()
        } catch (e: Exception) {
            logger.error("Failed to initialize SourceKit-LSP", e)
            dispose()
            throw IllegalStateException("Failed to initialize SourceKit-LSP: ${e.message}", e)
        }

        return server!!
    }

    /**
     * Wait for SourceKit-LSP to finish indexing the project.
     * The LSP may return "No language service" errors while indexing.
     */
    private fun waitForIndexing() {
        val maxRetries = 15
        val retryInterval = 2000L // 2 seconds

        for (i in 1..maxRetries) {
            try {
                // Test with a simple workspace symbol request
                val result = server!!.workspaceService.symbol(WorkspaceSymbolParams("test"))
                    .get(10, TimeUnit.SECONDS)
                logger.info("SourceKit-LSP indexing complete, ready for requests")
                return
            } catch (e: Exception) {
                val message = e.message ?: ""
                // Check if it's an indexing-related error
                if (message.contains("No language service") || message.contains("indexing")) {
                    if (i < maxRetries) {
                        logger.info("Waiting for SourceKit-LSP indexing... ($i/$maxRetries)")
                        Thread.sleep(retryInterval)
                    }
                } else {
                    // Other errors - LSP might be ready but query failed for other reasons
                    logger.debug("SourceKit-LSP test query returned: ${e.message}")
                    return
                }
            }
        }
        logger.warn("SourceKit-LSP indexing may not be complete after ${maxRetries * retryInterval / 1000}s")
    }

    /**
     * Check if LSP was recently initialized (may still be indexing)
     * @param thresholdMs Time threshold in milliseconds (default 60 seconds)
     */
    fun isRecentlyInitialized(thresholdMs: Long = 60_000): Boolean {
        return initializedAt > 0 &&
            (System.currentTimeMillis() - initializedAt) < thresholdMs
    }

    /**
     * Open a document (required before making requests on it)
     */
    fun openDocument(filePath: String) {
        if (filePath in openDocuments) return

        val file = File(filePath)
        if (!file.exists()) return

        val uri = "file://$filePath"
        val content = file.readText()

        val params = DidOpenTextDocumentParams(
            TextDocumentItem(uri, "swift", 1, content)
        )

        ensureInitialized().textDocumentService.didOpen(params)
        openDocuments.add(filePath)
    }

    /**
     * Close a document
     */
    fun closeDocument(filePath: String) {
        if (filePath !in openDocuments) return

        val uri = "file://$filePath"
        val params = DidCloseTextDocumentParams(
            TextDocumentIdentifier(uri)
        )

        server?.textDocumentService?.didClose(params)
        openDocuments.remove(filePath)
    }

    /**
     * Get document symbols (file structure)
     */
    fun documentSymbol(filePath: String): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        openDocument(filePath)
        val uri = "file://$filePath"
        val params = DocumentSymbolParams(TextDocumentIdentifier(uri))
        return ensureInitialized().textDocumentService.documentSymbol(params)
    }

    /**
     * Find references to a symbol
     * @param line 0-based line number
     * @param column 0-based column number
     */
    fun references(filePath: String, line: Int, column: Int): CompletableFuture<List<Location>> {
        openDocument(filePath)
        val uri = "file://$filePath"
        val params = ReferenceParams(
            TextDocumentIdentifier(uri),
            Position(line, column),
            ReferenceContext(true) // Include declaration
        )
        return ensureInitialized().textDocumentService.references(params)
    }

    /**
     * Go to definition
     * @param line 0-based line number
     * @param column 0-based column number
     */
    fun definition(filePath: String, line: Int, column: Int): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        openDocument(filePath)
        val uri = "file://$filePath"
        val params = DefinitionParams(
            TextDocumentIdentifier(uri),
            Position(line, column)
        )
        return ensureInitialized().textDocumentService.definition(params)
    }

    /**
     * Get hover information
     * @param line 0-based line number
     * @param column 0-based column number
     */
    fun hover(filePath: String, line: Int, column: Int): CompletableFuture<Hover?> {
        openDocument(filePath)
        val uri = "file://$filePath"
        val params = HoverParams(
            TextDocumentIdentifier(uri),
            Position(line, column)
        )
        return ensureInitialized().textDocumentService.hover(params)
    }

    /**
     * Search for symbols in workspace
     */
    fun workspaceSymbol(query: String): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        if (query.isBlank()) {
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        val params = WorkspaceSymbolParams(query)
        return ensureInitialized().workspaceService.symbol(params)
    }

    override fun dispose() {
        logger.info("Disposing SwiftLspClient")

        // Close all open documents
        openDocuments.toList().forEach { closeDocument(it) }

        // Shutdown server
        try {
            server?.shutdown()?.get(5, TimeUnit.SECONDS)
            server?.exit()
        } catch (e: Exception) {
            logger.warn("Error during LSP shutdown", e)
        }

        // Kill process
        process?.let {
            if (it.isAlive) {
                it.destroyForcibly()
                it.waitFor(5, TimeUnit.SECONDS)
            }
        }

        // Stop stderr reader thread
        stderrReader?.interrupt()
        stderrReader = null

        // Shutdown executor service
        executorService?.shutdownNow()
        executorService = null

        process = null
        server = null
        initialized = false
        openDocuments.clear()
    }
}

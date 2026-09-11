package info.jiayun.intellijmcp.csharp

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
 * LSP client that manages connection to a C# Language Server (csharp-ls or OmniSharp)
 */
class CSharpLspClient(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(CSharpLspClient::class.java)

    private var process: Process? = null
    private var server: LanguageServer? = null
    private var initialized = false
    private var initializedAt: Long = 0
    val intelligenceState = info.jiayun.intellijmcp.intelligence.LspIntelligenceState()
    private var stderrReader: Thread? = null
    private var executorService: ExecutorService? = null
    private var languageClient: CSharpLanguageClient? = null

    companion object {
        private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true

        /**
         * Find C# language server binary.
         * Priority:
         * 1. csharp-ls in PATH
         * 2. ~/.dotnet/tools/csharp-ls (dotnet global tool default)
         * 3. OmniSharp in PATH (fallback)
         */
        fun findCSharpLsp(): CSharpLspBinary? {
            val logger = Logger.getInstance(CSharpLspClient::class.java)

            // 1. csharp-ls in PATH
            findInPath("csharp-ls")?.let {
                logger.info("Found csharp-ls in PATH: $it")
                return CSharpLspBinary(it, LspServerType.CSHARP_LS)
            }

            // 2. ~/.dotnet/tools/csharp-ls
            val dotnetToolsDir = File(System.getProperty("user.home"), ".dotnet/tools")
            val csharpLsName = if (isWindows) "csharp-ls.exe" else "csharp-ls"
            val dotnetToolPath = File(dotnetToolsDir, csharpLsName)
            if (dotnetToolPath.exists() && dotnetToolPath.canExecute()) {
                logger.info("Found csharp-ls at: ${dotnetToolPath.absolutePath}")
                return CSharpLspBinary(dotnetToolPath.absolutePath, LspServerType.CSHARP_LS)
            }

            // 3. OmniSharp in PATH (needs --languageserver flag)
            findInPath("OmniSharp")?.let {
                logger.info("Found OmniSharp in PATH: $it")
                return CSharpLspBinary(it, LspServerType.OMNISHARP)
            }

            logger.warn("No C# LSP binary found. Searched: PATH (csharp-ls), ${dotnetToolPath.absolutePath}, PATH (OmniSharp)")
            return null
        }

        /**
         * Check if a C# language server is available
         */
        fun isAvailable(): Boolean = findCSharpLsp() != null

        private fun findInPath(binaryName: String): String? {
            val command = if (isWindows) "where" else "which"
            return try {
                val process = ProcessBuilder(command, binaryName)
                    .redirectErrorStream(true)
                    .start()
                val result = process.inputStream.bufferedReader().readText().trim()
                    .lines().firstOrNull()?.trim() ?: ""
                if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 && result.isNotEmpty()) {
                    if (File(result).exists()) result else null
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    @Synchronized
    fun ensureInitialized(deadline: info.jiayun.intellijmcp.intelligence.Deadline? = null): LanguageServer {
        deadline?.check()
        if (initialized && server != null && process?.isAlive == true) {
            return server!!
        }

        val binary = findCSharpLsp()
            ?: throw IllegalStateException("C# Language Server not found. Install csharp-ls: dotnet tool install --global csharp-ls")

        intelligenceState.csharpLs = binary.type == LspServerType.CSHARP_LS
        logger.info("Starting C# LSP (${binary.type}): ${binary.path}")

        val workDir = project.basePath?.let { File(it) }
        val command = when (binary.type) {
            LspServerType.CSHARP_LS -> listOf(binary.path)
            LspServerType.OMNISHARP -> listOf(binary.path, "--languageserver")
        }

        val processBuilder = ProcessBuilder(command)
            .apply { workDir?.let { directory(it) } }
            .redirectErrorStream(false)

        process = processBuilder.start()

        // Consume stderr in background to prevent buffer blocking
        stderrReader = Thread {
            try {
                process!!.errorStream.bufferedReader().forEachLine { line ->
                    logger.debug("C# LSP stderr: $line")
                }
            } catch (e: Exception) {
                // Process terminated
            }
        }.apply {
            name = "CSharp-LSP-stderr-reader"
            isDaemon = true
            start()
        }

        // Create LSP launcher
        intelligenceState.clear()
        val client = CSharpLanguageClient(intelligenceState)
        languageClient = client
        executorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "CSharp-LSP-worker").apply { isDaemon = true }
        }

        val launcher = Launcher.Builder<LanguageServer>()
            .setLocalService(client)
            .setRemoteInterface(LanguageServer::class.java)
            .setInput(process!!.inputStream)
            .setOutput(process!!.outputStream)
            .setExecutorService(executorService)
            .create()

        server = launcher.remoteProxy
        launcher.startListening()

        // Initialize server
        val initParams = InitializeParams().apply {
            rootUri = project.basePath?.let { pathToUri(it) }
            capabilities = ClientCapabilities().apply {
                textDocument = TextDocumentClientCapabilities().apply {
                    implementation = ImplementationCapabilities().apply { dynamicRegistration = true }
                    callHierarchy = CallHierarchyCapabilities().apply { dynamicRegistration = true }
                    diagnostic = DiagnosticCapabilities().apply { dynamicRegistration = true }
                    publishDiagnostics = PublishDiagnosticsCapabilities().apply { versionSupport = true }
                    hover = HoverCapabilities()
                    definition = DefinitionCapabilities()
                    references = ReferencesCapabilities()
                    documentSymbol = DocumentSymbolCapabilities().apply {
                        hierarchicalDocumentSymbolSupport = true
                    }
                    typeHierarchy = TypeHierarchyCapabilities()
                }
                workspace = WorkspaceClientCapabilities().apply {
                    symbol = SymbolCapabilities()
                }
            }
        }

        try {
            val initialization = server!!.initialize(initParams)
            val initResult = if(deadline != null) deadline.await(initialization) else initialization.get(30, TimeUnit.SECONDS)
            intelligenceState.capabilities = initResult.capabilities
            intelligenceState.serverInfo = initResult.serverInfo
            logger.info("C# LSP initialized: ${initResult.serverInfo?.name ?: "unknown"}")
            server!!.initialized(InitializedParams())
            initialized = true
            initializedAt = System.currentTimeMillis()

            if(deadline == null) waitForIndexing()
        } catch (e: Exception) {
            logger.error("Failed to initialize C# LSP", e)
            dispose()
            throw IllegalStateException("Failed to initialize C# Language Server: ${e.message}", e)
        }

        return server!!
    }

    private fun waitForIndexing() {
        val maxRetries = 15
        val retryInterval = 2000L

        // Phase 1: Wait until the server accepts workspace/symbol without error
        for (i in 1..maxRetries) {
            try {
                server!!.workspaceService.symbol(WorkspaceSymbolParams("test"))
                    .get(10, TimeUnit.SECONDS)
                logger.info("C# LSP accepts queries after $i attempt(s), checking if index is populated...")
                break // Server is responsive, move to phase 2
            } catch (e: Exception) {
                val message = e.message ?: ""
                if (message.contains("not ready", ignoreCase = true) ||
                    message.contains("indexing", ignoreCase = true) ||
                    message.contains("loading", ignoreCase = true)
                ) {
                    if (i < maxRetries) {
                        logger.info("Waiting for C# LSP indexing... ($i/$maxRetries)")
                        Thread.sleep(retryInterval)
                    }
                } else {
                    // Non-indexing error (e.g., method not supported) — skip phase 2
                    logger.debug("C# LSP test query returned: ${e.message}")
                    return
                }
            }
        }

        // Phase 2: Wait until the server returns non-empty results
        // csharp-ls loads .sln quickly but the Roslyn semantic index takes longer;
        // empty result ≠ ready
        val readinessRetries = 10
        val readinessInterval = 3000L
        for (i in 1..readinessRetries) {
            // Check for MSBuild/loading errors before retrying
            val errors = languageClient?.errors ?: emptyList()
            if (errors.any { it.contains("msbuild", ignoreCase = true) || it.contains("failed", ignoreCase = true) }) {
                logger.warn("C# LSP: MSBuild reported errors during solution loading. Symbol index will be empty.\n" +
                    errors.joinToString("\n") + "\n" +
                    "Possible fix: upgrade csharp-ls (dotnet tool update -g csharp-ls) or use a compatible .NET SDK version.")
                return
            }

            try {
                val result = server!!.workspaceService.symbol(WorkspaceSymbolParams("a"))
                    .get(10, TimeUnit.SECONDS)
                val count = when {
                    result.isLeft -> result.left?.size ?: 0
                    result.isRight -> result.right?.size ?: 0
                    else -> 0
                }
                if (count > 0) {
                    logger.info("C# LSP indexing complete, returned $count symbols for readiness probe")
                    return
                }
                if (i < readinessRetries) {
                    logger.info("C# LSP returned empty results, Roslyn may still be compiling... ($i/$readinessRetries)")
                    Thread.sleep(readinessInterval)
                }
            } catch (e: Exception) {
                logger.debug("C# LSP readiness probe error: ${e.message}")
                return
            }
        }
        logger.warn("C# LSP index may not be fully populated after readiness check")
    }

    fun isRecentlyInitialized(thresholdMs: Long = 60_000): Boolean {
        return initializedAt > 0 &&
            (System.currentTimeMillis() - initializedAt) < thresholdMs
    }

    fun getServerErrors(): List<String> = languageClient?.errors?.toList() ?: emptyList()

    // ===== Document Lifecycle =====

    fun openDocument(filePath: String) {
        val remote = ensureInitialized()
        val content = info.jiayun.intellijmcp.intelligence.psiRead {
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
            file?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it)?.text }
        } ?: File(filePath).readText()
        intelligenceState.sync(remote,filePath,"csharp",content)
    }

    fun closeDocument(filePath: String) {
        intelligenceState.close(server,filePath)
    }

    // ===== LSP Methods =====

    fun workspaceSymbol(query: String): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        if (query.isBlank()) {
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        val params = WorkspaceSymbolParams(query)
        return ensureInitialized().workspaceService.symbol(params)
    }

    fun documentSymbol(filePath: String): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        openDocument(filePath)
        val uri = pathToUri(filePath)
        val params = DocumentSymbolParams(TextDocumentIdentifier(uri))
        return ensureInitialized().textDocumentService.documentSymbol(params)
    }

    /**
     * @param line 0-based line number
     * @param column 0-based column number
     */
    fun references(filePath: String, line: Int, column: Int): CompletableFuture<List<Location>> {
        openDocument(filePath)
        val uri = pathToUri(filePath)
        val params = ReferenceParams(
            TextDocumentIdentifier(uri),
            Position(line, column),
            ReferenceContext(true)
        )
        return ensureInitialized().textDocumentService.references(params)
    }

    /**
     * @param line 0-based line number
     * @param column 0-based column number
     */
    fun definition(filePath: String, line: Int, column: Int): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        openDocument(filePath)
        val uri = pathToUri(filePath)
        val params = DefinitionParams(
            TextDocumentIdentifier(uri),
            Position(line, column)
        )
        return ensureInitialized().textDocumentService.definition(params)
    }

    /**
     * @param line 0-based line number
     * @param column 0-based column number
     */
    fun hover(filePath: String, line: Int, column: Int): CompletableFuture<Hover?> {
        openDocument(filePath)
        val uri = pathToUri(filePath)
        val params = HoverParams(
            TextDocumentIdentifier(uri),
            Position(line, column)
        )
        return ensureInitialized().textDocumentService.hover(params)
    }

    /**
     * Prepare type hierarchy at position
     * @param line 0-based line number
     * @param column 0-based column number
     */
    fun prepareTypeHierarchy(filePath: String, line: Int, column: Int): CompletableFuture<List<TypeHierarchyItem>?> {
        openDocument(filePath)
        val uri = pathToUri(filePath)
        val params = TypeHierarchyPrepareParams(
            TextDocumentIdentifier(uri),
            Position(line, column)
        )
        return ensureInitialized().textDocumentService.prepareTypeHierarchy(params)
    }

    fun supertypes(item: TypeHierarchyItem): CompletableFuture<List<TypeHierarchyItem>?> {
        val params = TypeHierarchySupertypesParams(item)
        return ensureInitialized().textDocumentService.typeHierarchySupertypes(params)
    }

    fun subtypes(item: TypeHierarchyItem): CompletableFuture<List<TypeHierarchyItem>?> {
        val params = TypeHierarchySubtypesParams(item)
        return ensureInitialized().textDocumentService.typeHierarchySubtypes(params)
    }

    // ===== URI Helpers =====

    private fun pathToUri(path: String): String = info.jiayun.intellijmcp.intelligence.lspDocumentUri(path)

    fun uriToPath(uri: String): String = info.jiayun.intellijmcp.intelligence.lspUriToPath(uri)

    // ===== Dispose =====

    override fun dispose() {
        logger.info("Disposing CSharpLspClient")

        intelligenceState.clear(server)

        try {
            server?.shutdown()?.get(5, TimeUnit.SECONDS)
            server?.exit()
        } catch (e: Exception) {
            logger.warn("Error during C# LSP shutdown", e)
        }

        process?.let {
            if (it.isAlive) {
                it.destroyForcibly()
                it.waitFor(5, TimeUnit.SECONDS)
            }
        }

        stderrReader?.interrupt()
        stderrReader = null

        executorService?.shutdownNow()
        executorService = null

        process = null
        server = null
        languageClient = null
        initialized = false
        intelligenceState.clear()
    }
}

enum class LspServerType {
    CSHARP_LS,
    OMNISHARP
}

data class CSharpLspBinary(
    val path: String,
    val type: LspServerType
)

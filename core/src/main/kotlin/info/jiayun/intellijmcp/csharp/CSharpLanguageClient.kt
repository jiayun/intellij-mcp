package info.jiayun.intellijmcp.csharp

import com.intellij.openapi.diagnostic.Logger
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LSP client implementation for receiving callbacks from C# Language Server
 */
class CSharpLanguageClient(private val intelligence: info.jiayun.intellijmcp.intelligence.LspIntelligenceState = info.jiayun.intellijmcp.intelligence.LspIntelligenceState()) : LanguageClient {

    private val logger = Logger.getInstance(CSharpLanguageClient::class.java)

    /** Critical errors reported by the LSP server (MSBuild failures, etc.). Thread-safe. */
    val errors = CopyOnWriteArrayList<String>()

    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> {
        intelligence.unregister(params)
        return CompletableFuture.completedFuture(null)
    }

    override fun telemetryEvent(obj: Any?) {
        logger.debug("Telemetry event: $obj")
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) {
        diagnostics?.let { intelligence.publish(it) }
    }

    override fun showMessage(messageParams: MessageParams?) {
        logger.info("C# LSP message: ${messageParams?.message}")
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem?> {
        logger.info("C# LSP message request: ${requestParams?.message}")
        return CompletableFuture.completedFuture(null)
    }

    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
        intelligence.register(params)
        return CompletableFuture.completedFuture(null)
    }

    override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> {
        logger.debug("Configuration request: ${params.items.map { it.section }}")
        return CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
    }

    override fun logMessage(message: MessageParams?) {
        when (message?.type) {
            MessageType.Error -> {
                logger.error("C# LSP: ${message.message}")
                errors.add(message.message)
            }
            MessageType.Warning -> logger.warn("C# LSP: ${message.message}")
            MessageType.Info -> logger.info("C# LSP: ${message.message}")
            MessageType.Log -> logger.debug("C# LSP: ${message.message}")
            else -> logger.debug("C# LSP: ${message?.message}")
        }

        // csharp-ls logs MSBuild failures as Info-level messages — capture them too
        val msg = message?.message ?: return
        if (msg.contains("msbuild", ignoreCase = true) && msg.contains("failed", ignoreCase = true)) {
            errors.addIfAbsent(msg)
        }
    }
}

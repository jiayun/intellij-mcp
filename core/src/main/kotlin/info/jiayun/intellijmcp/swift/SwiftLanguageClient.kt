package info.jiayun.intellijmcp.swift

import com.intellij.openapi.diagnostic.Logger
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

/**
 * LSP client implementation for receiving callbacks from SourceKit-LSP
 */
class SwiftLanguageClient : LanguageClient {

    private val logger = Logger.getInstance(SwiftLanguageClient::class.java)

    override fun telemetryEvent(obj: Any?) {
        logger.debug("Telemetry event: $obj")
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) {
        // We don't need diagnostics for MCP operations
        logger.debug("Diagnostics received for: ${diagnostics?.uri}")
    }

    override fun showMessage(messageParams: MessageParams?) {
        logger.info("SourceKit-LSP message: ${messageParams?.message}")
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem?> {
        logger.info("SourceKit-LSP message request: ${requestParams?.message}")
        return CompletableFuture.completedFuture(null)
    }

    override fun logMessage(message: MessageParams?) {
        when (message?.type) {
            MessageType.Error -> logger.error("SourceKit-LSP: ${message.message}")
            MessageType.Warning -> logger.warn("SourceKit-LSP: ${message.message}")
            MessageType.Info -> logger.info("SourceKit-LSP: ${message.message}")
            MessageType.Log -> logger.debug("SourceKit-LSP: ${message.message}")
            else -> logger.debug("SourceKit-LSP: ${message?.message}")
        }
    }
}

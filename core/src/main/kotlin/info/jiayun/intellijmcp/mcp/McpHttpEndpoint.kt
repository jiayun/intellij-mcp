package info.jiayun.intellijmcp.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** Installs the MCP transport while keeping JSON-RPC parsing/serialization in one place. */
fun Route.mcpEndpoint(
    codec: McpJsonRpcCodec,
    onRequest: (String) -> Unit = {},
    onResponse: (String) -> Unit = {},
    onError: (Throwable) -> Unit = {},
    handler: suspend (McpRequest) -> McpResponse?
) {
    post("/mcp") {
        val body = call.receiveText()
        onRequest(body)

        val request = try {
            codec.parseRequest(body)
        } catch (e: McpProtocolException) {
            onError(e)
            val responseJson = codec.serializeResponse(
                McpResponse(id = e.responseId, error = McpError(e.code, e.message))
            )
            onResponse(responseJson)
            call.respondText(responseJson, ContentType.Application.Json)
            return@post
        }

        val response = try {
            handler(request)
        } catch (e: Exception) {
            onError(e)
            McpResponse(
                id = request.id,
                error = McpError(McpError.INTERNAL_ERROR, e.message ?: "Internal error")
            )
        }

        // Notifications are requests without an id and must never receive a JSON-RPC response.
        if (request.id == null) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        if (response == null) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        // The transport is the final authority: a handler cannot accidentally change the ID.
        val responseJson = codec.serializeResponse(response.copy(id = request.id))
        onResponse(responseJson)
        call.respondText(responseJson, ContentType.Application.Json)
    }
}

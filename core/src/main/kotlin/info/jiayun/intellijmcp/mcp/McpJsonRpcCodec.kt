package info.jiayun.intellijmcp.mcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Central JSON-RPC parser and response serializer.
 *
 * Request IDs stay as their original JSON nodes. In particular, they never pass through
 * Number/Double, so integer spelling and arbitrary precision are retained in responses.
 */
class McpJsonRpcCodec(private val gson: Gson) {
    // JSON-RPC protocol errors require an explicit `id: null`; the application's Gson
    // intentionally keeps its existing null-omission behavior for ordinary payloads.
    private val responseGson = gson.newBuilder().serializeNulls().create()

    fun parseRequest(json: String): McpRequest {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            throw McpProtocolException(McpError.PARSE_ERROR, "Parse error: ${e.message}", cause = e)
        }

        if (!root.isJsonObject) {
            throw McpProtocolException(McpError.INVALID_REQUEST, "Request must be a JSON object")
        }

        val request = root.asJsonObject
        val id = parseRequestId(request)

        val jsonrpc = request.get("jsonrpc")
        if (jsonrpc == null || !jsonrpc.isJsonPrimitive || !jsonrpc.asJsonPrimitive.isString || jsonrpc.asString != "2.0") {
            throw McpProtocolException(McpError.INVALID_REQUEST, "jsonrpc must be \"2.0\"", id)
        }

        val method = request.get("method")
        if (method == null || !method.isJsonPrimitive || !method.asJsonPrimitive.isString) {
            throw McpProtocolException(McpError.INVALID_REQUEST, "method must be a string", id)
        }

        val params = request.get("params")?.let {
            if (!it.isJsonObject) {
                throw McpProtocolException(McpError.INVALID_PARAMS, "params must be an object", id)
            }
            it.asJsonObject
        }

        return McpRequest(id = id, method = method.asString, params = params)
    }

    fun serializeResponse(response: McpResponse): String {
        require((response.result == null) != (response.error == null)) {
            "A JSON-RPC response must contain exactly one of result or error"
        }

        val json = JsonObject().apply {
            addProperty("jsonrpc", response.jsonrpc)
            add("id", response.id ?: JsonNull.INSTANCE)
            if (response.error != null) {
                add("error", gson.toJsonTree(response.error))
            } else {
                add("result", gson.toJsonTree(response.result))
            }
        }
        return responseGson.toJson(json)
    }

    private fun parseRequestId(request: JsonObject): JsonElement? {
        if (!request.has("id")) return null

        val id = request.get("id")
        if (id == null || id.isJsonNull || !id.isJsonPrimitive) {
            throw McpProtocolException(McpError.INVALID_REQUEST, "id must be a string or integer")
        }

        val primitive = id.asJsonPrimitive
        if (primitive.isString) return id

        // Validate the original JSON token, rather than coercing it through Number or Double.
        // Exponents and decimal points are deliberately rejected even when mathematically integral.
        if (primitive.isNumber && JSON_INTEGER.matches(primitive.toString())) return id

        throw McpProtocolException(McpError.INVALID_REQUEST, "id must be a string or integer")
    }

    private companion object {
        val JSON_INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
    }
}

class McpProtocolException(
    val code: Int,
    override val message: String,
    val responseId: JsonElement? = null,
    cause: Throwable? = null
) : Exception(message, cause)

package info.jiayun.intellijmcp.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class McpRequest(
    val jsonrpc: String = "2.0",
    /** The original JSON string or integer node. Null means that this is a notification. */
    val id: JsonElement?,
    val method: String,
    val params: JsonObject? = null
)

data class McpResponse(
    val jsonrpc: String = "2.0",
    /** Reuses the request's original JSON node so its type and numeric precision are preserved. */
    val id: JsonElement?,
    val result: Any? = null,
    val error: McpError? = null
)

data class McpError(
    val code: Int,
    val message: String,
    val data: Any? = null
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // Custom error codes
        const val PROJECT_NOT_FOUND = -32001
        const val FILE_NOT_FOUND = -32002
        const val UNSUPPORTED_LANGUAGE = -32003
        const val INDEX_NOT_READY = -32004
        const val SYMBOL_NOT_FOUND = -32005
        const val CONFIGURATION_NOT_FOUND = -32006
        const val EXECUTION_FAILED = -32007
        const val EXECUTION_TIMEOUT = -32008
        const val NO_TEST_RESULTS = -32009
    }
}

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)

package info.jiayun.intellijmcp.mcp

data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: Any,
    val method: String,
    val params: Map<String, Any?>? = null
)

data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: Any,
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
    }
}

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)

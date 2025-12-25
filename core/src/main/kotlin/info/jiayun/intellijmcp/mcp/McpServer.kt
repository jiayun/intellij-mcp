package info.jiayun.intellijmcp.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import info.jiayun.intellijmcp.api.*
import info.jiayun.intellijmcp.project.*
import info.jiayun.intellijmcp.settings.PluginSettings

@Service(Service.Level.APP)
class McpServer {

    private val logger = Logger.getInstance(McpServer::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val executor = McpToolExecutor()

    private var server: NettyApplicationEngine? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    var port: Int = 9876
        private set

    // ===== Tool definitions =====

    private val tools: List<McpToolDefinition> by lazy {
        val languages = LanguageAdapterRegistry.getInstance().getSupportedLanguages()
        val extensions = LanguageAdapterRegistry.getInstance().getSupportedExtensions()

        listOf(
            McpToolDefinition(
                name = "list_projects",
                description = "List all currently open projects in the IDE.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>()
                )
            ),
            McpToolDefinition(
                name = "get_supported_languages",
                description = "Get list of supported programming languages.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>()
                )
            ),
            McpToolDefinition(
                name = "find_symbol",
                description = "Find symbol (class, function, variable) definition by name.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf(
                            "type" to "string",
                            "description" to "Symbol name to find"
                        ),
                        "kind" to mapOf(
                            "type" to "string",
                            "enum" to listOf("class", "interface", "function", "method", "variable", "field", "property"),
                            "description" to "Optional: filter by symbol kind"
                        ),
                        "language" to mapOf(
                            "type" to "string",
                            "enum" to languages,
                            "description" to "Optional: limit search to specific language"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path. Uses active project if not provided."
                        )
                    ),
                    "required" to listOf("name")
                )
            ),
            McpToolDefinition(
                name = "find_references",
                description = "Find all references to the symbol at the given location.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "filePath" to mapOf(
                            "type" to "string",
                            "description" to "Absolute file path"
                        ),
                        "line" to mapOf(
                            "type" to "integer",
                            "description" to "0-based line number"
                        ),
                        "column" to mapOf(
                            "type" to "integer",
                            "description" to "0-based column number"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("filePath", "line", "column")
                )
            ),
            McpToolDefinition(
                name = "get_symbol_info",
                description = "Get detailed information about the symbol at the given location.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "filePath" to mapOf(
                            "type" to "string",
                            "description" to "Absolute file path"
                        ),
                        "line" to mapOf(
                            "type" to "integer",
                            "description" to "0-based line number"
                        ),
                        "column" to mapOf(
                            "type" to "integer",
                            "description" to "0-based column number"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("filePath", "line", "column")
                )
            ),
            McpToolDefinition(
                name = "get_file_symbols",
                description = "List all symbols in a file. Supported extensions: ${extensions.joinToString(", ") { ".$it" }}",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "filePath" to mapOf(
                            "type" to "string",
                            "description" to "Absolute file path"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("filePath")
                )
            ),
            McpToolDefinition(
                name = "get_type_hierarchy",
                description = "Get the type hierarchy (base types and subtypes) for a class/interface.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "typeName" to mapOf(
                            "type" to "string",
                            "description" to "Type name (can be qualified name)"
                        ),
                        "language" to mapOf(
                            "type" to "string",
                            "enum" to languages,
                            "description" to "Optional: specify language if type name is ambiguous"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("typeName")
                )
            )
        )
    }

    // ===== Server lifecycle =====

    fun start(port: Int = PluginSettings.getInstance().port) {
        if (isRunning) {
            logger.info("MCP Server is already running on port ${this.port}")
            return
        }

        this.port = port

        // Log loaded adapters
        LanguageAdapterRegistry.getInstance().logLoadedAdapters()

        server = embeddedServer(Netty, port = port) {
            routing {
                get("/health") {
                    call.respondText("OK")
                }

                get("/info") {
                    val info = mapOf(
                        "name" to "intellij-mcp",
                        "version" to "1.0.0",
                        "languages" to LanguageAdapterRegistry.getInstance().getSupportedLanguages()
                    )
                    call.respondText(gson.toJson(info), ContentType.Application.Json)
                }

                post("/mcp") {
                    val body = call.receiveText()
                    logger.debug("MCP Request: $body")

                    val response = try {
                        val request = gson.fromJson(body, McpRequest::class.java)
                        handleRequest(request)
                    } catch (e: Exception) {
                        logger.error("Failed to parse request", e)
                        McpResponse(
                            id = 0,
                            error = McpError(McpError.PARSE_ERROR, "Parse error: ${e.message}")
                        )
                    }

                    val responseJson = gson.toJson(response)
                    logger.debug("MCP Response: $responseJson")
                    call.respondText(responseJson, ContentType.Application.Json)
                }

                get("/sse") {
                    call.response.cacheControl(CacheControl.NoCache(null))
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        write("data: {\"status\": \"connected\"}\n\n")
                        flush()

                        while (isRunning) {
                            delay(30000)
                            write(": keepalive\n\n")
                            flush()
                        }
                    }
                }
            }
        }.start(wait = false)

        isRunning = true
        logger.info("MCP Server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        isRunning = false
        logger.info("MCP Server stopped")
    }

    // ===== Request handling =====

    private fun handleRequest(request: McpRequest): McpResponse {
        return when (request.method) {
            "initialize" -> handleInitialize(request)
            "initialized" -> McpResponse(id = request.id, result = emptyMap<String, Any>())
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolCall(request)
            else -> McpResponse(
                id = request.id,
                error = McpError(McpError.METHOD_NOT_FOUND, "Method not found: ${request.method}")
            )
        }
    }

    private fun handleInitialize(request: McpRequest): McpResponse {
        return McpResponse(
            id = request.id,
            result = mapOf(
                "protocolVersion" to "2024-11-05",
                "serverInfo" to mapOf(
                    "name" to "intellij-mcp",
                    "version" to "1.0.0"
                ),
                "capabilities" to mapOf(
                    "tools" to mapOf("listChanged" to false)
                )
            )
        )
    }

    private fun handleToolsList(request: McpRequest): McpResponse {
        return McpResponse(
            id = request.id,
            result = mapOf("tools" to tools)
        )
    }

    private fun handleToolCall(request: McpRequest): McpResponse {
        val params = request.params ?: return McpResponse(
            id = request.id,
            error = McpError(McpError.INVALID_PARAMS, "Missing params")
        )

        val toolName = params["name"] as? String ?: return McpResponse(
            id = request.id,
            error = McpError(McpError.INVALID_PARAMS, "Missing tool name")
        )

        @Suppress("UNCHECKED_CAST")
        val arguments = params["arguments"] as? Map<String, Any?> ?: emptyMap()

        return try {
            val result = executeTool(toolName, arguments)
            McpResponse(
                id = request.id,
                result = mapOf(
                    "content" to listOf(
                        mapOf("type" to "text", "text" to gson.toJson(result))
                    )
                )
            )
        } catch (e: ProjectNotFoundException) {
            McpResponse(id = request.id, error = McpError(McpError.PROJECT_NOT_FOUND, e.message ?: ""))
        } catch (e: UnsupportedLanguageException) {
            McpResponse(id = request.id, error = McpError(McpError.UNSUPPORTED_LANGUAGE, e.message ?: ""))
        } catch (e: IndexNotReadyException) {
            McpResponse(id = request.id, error = McpError(McpError.INDEX_NOT_READY, e.message ?: ""))
        } catch (e: SymbolNotFoundException) {
            McpResponse(id = request.id, error = McpError(McpError.SYMBOL_NOT_FOUND, e.message ?: ""))
        } catch (e: Exception) {
            logger.error("Tool execution failed: $toolName", e)
            McpResponse(id = request.id, error = McpError(McpError.INTERNAL_ERROR, e.message ?: "Unknown error"))
        }
    }

    private fun executeTool(name: String, args: Map<String, Any?>): Any {
        return when (name) {
            "list_projects" -> executor.listProjects()

            "get_supported_languages" -> executor.getSupportedLanguages()

            "find_symbol" -> executor.findSymbol(
                name = args["name"] as String,
                kind = args["kind"] as? String,
                language = args["language"] as? String,
                projectPath = args["projectPath"] as? String
            )

            "find_references" -> executor.findReferences(
                filePath = args["filePath"] as String,
                line = (args["line"] as Number).toInt(),
                column = (args["column"] as Number).toInt(),
                projectPath = args["projectPath"] as? String
            )

            "get_symbol_info" -> executor.getSymbolInfo(
                filePath = args["filePath"] as String,
                line = (args["line"] as Number).toInt(),
                column = (args["column"] as Number).toInt(),
                projectPath = args["projectPath"] as? String
            )

            "get_file_symbols" -> executor.getFileSymbols(
                filePath = args["filePath"] as String,
                projectPath = args["projectPath"] as? String
            )

            "get_type_hierarchy" -> executor.getTypeHierarchy(
                typeName = args["typeName"] as String,
                language = args["language"] as? String,
                projectPath = args["projectPath"] as? String
            )

            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    companion object {
        fun getInstance(): McpServer =
            ApplicationManager.getApplication().getService(McpServer::class.java)
    }
}

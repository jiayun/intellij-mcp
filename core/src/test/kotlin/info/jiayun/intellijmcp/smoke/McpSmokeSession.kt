package info.jiayun.intellijmcp.smoke

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import info.jiayun.intellijmcp.mcp.McpServer
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Uses an external Python process and the production HTTP endpoint, never a fake handler. */
class McpSmokeSession(private val clientPath: String, private val root: Path) : AutoCloseable {
    private val gson = Gson()
    private val server = McpServer.getInstance()
    val exchanges = mutableListOf<JsonElement>()

    init {
        val port = ServerSocket(0).use { it.localPort }
        server.start(port)
        check(server.isRunning)
    }

    fun probe(file: String): JsonElement = invoke(mapOf("probe" to true,
        "expectedVersion" to (McpServer::class.java.classLoader as com.intellij.ide.plugins.cl.PluginAwareClassLoader)
            .pluginDescriptor.version,
        "projectPath" to root.toString(), "filePath" to root.resolve(file).toString()))

    fun query(tool: String, arguments: Map<String,Any?>): JsonElement =
        invoke(mapOf("tool" to tool, "arguments" to (arguments + ("projectPath" to root.toString()))))

    fun postProbe(arguments: Map<String,Any?>): JsonElement =
        invoke(mapOf("postProbe" to true,"arguments" to (arguments + ("projectPath" to root.toString()))))

    private fun invoke(input: Any): JsonElement {
        val output = Files.createTempFile("mcp-client-", ".json")
        val error = Files.createTempFile("mcp-client-", ".log")
        var process: Process? = null
        try {
            process = ProcessBuilder(System.getProperty("mcp.smoke.python", "python3"),clientPath,
                "http://127.0.0.1:${server.port}/mcp")
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start()
            process.outputStream.bufferedWriter().use { it.write(gson.toJson(input)) }
            check(process.waitFor(90,TimeUnit.SECONDS)) { "External MCP client timed out" }
            val response = gson.fromJson(Files.readString(output),JsonObject::class.java)
                ?: error("MCP client returned no JSON: "+Files.readString(error))
            response.getAsJsonArray("exchanges")?.forEach { exchanges.add(it) }
            check(process.exitValue() == 0 && !response.has("error")) { "MCP client failed: $response" }
            return response["value"]
        } finally {
            process?.let { if(it.isAlive) it.destroyForcibly() }
            Files.deleteIfExists(output)
            Files.deleteIfExists(error)
        }
    }

    override fun close() = server.stop()
}

package info.jiayun.intellijmcp.mcp

/** Kept independent of IDE services so the public contract can be tested without an IDE. */
object IntelligenceTools {
    val names = setOf("get_diagnostics","find_implementations","get_call_hierarchy")
    private val common = mapOf(
        "projectPath" to mapOf("type" to "string","description" to "Optional absolute project root"),
        "timeout" to mapOf("type" to "integer","default" to 30000,"minimum" to 1,"maximum" to 60000))
    private val position = common + mapOf(
        "filePath" to mapOf("type" to "string","description" to "Absolute file path; unsaved IDE content takes precedence"),
        "line" to mapOf("type" to "integer","minimum" to 1,"description" to "1-based line"),
        "column" to mapOf("type" to "integer","minimum" to 1,"description" to "1-based UTF-16 column"),
        "includeLibraries" to mapOf("type" to "boolean","default" to false))
    val definitions = listOf(
        McpToolDefinition("get_diagnostics","Analyze specified files with enabled IDE inspections or language server. Per-file status distinguishes clean results from incomplete analysis.",mapOf(
            "type" to "object","properties" to common + mapOf(
                "filePaths" to mapOf("type" to "array","items" to mapOf("type" to "string"),"minItems" to 1),
                "minSeverity" to mapOf("type" to "string","enum" to listOf("error","warning","information","hint"),"default" to "warning")),
            "required" to listOf("filePaths"))),
        McpToolDefinition("find_implementations","Find IDE/LSP implementations and overrides at a declaration or usage. Excludes the target and deduplicates declarations; includes analysis status and truncation.",mapOf(
            "type" to "object","properties" to position + mapOf("limit" to mapOf("type" to "integer","default" to 200,"minimum" to 1,"maximum" to 1000)),
            "required" to listOf("filePath","line","column"))),
        McpToolDefinition("get_call_hierarchy","Get incoming/outgoing calls as declaration nodes and directed edges. Recursive edges are retained. Vue script only; older Rust supports direct resolved calls; csharp-ls outgoing requires 0.27.0+. See get_supported_languages for backend limitations.",mapOf(
            "type" to "object","properties" to position + mapOf(
                "direction" to mapOf("type" to "string","enum" to listOf("incoming","outgoing")),
                "depth" to mapOf("type" to "integer","default" to 1,"minimum" to 1,"maximum" to 3),
                "maxNodes" to mapOf("type" to "integer","default" to 100,"minimum" to 1,"maximum" to 500)),
            "required" to listOf("filePath","line","column","direction")))
    )
}

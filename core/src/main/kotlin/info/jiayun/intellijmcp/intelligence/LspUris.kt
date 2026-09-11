package info.jiayun.intellijmcp.intelligence

/** Decode only actual file URIs; preserve server-specific metadata URIs for existing callers. */
fun lspUriToPath(uri: String): String = try {
    val parsed = java.net.URI(uri)
    if(parsed.scheme.equals("file",ignoreCase=true)) java.nio.file.Path.of(parsed).toString() else uri
} catch(_: Exception) {
    uri.removePrefix("file://")
}

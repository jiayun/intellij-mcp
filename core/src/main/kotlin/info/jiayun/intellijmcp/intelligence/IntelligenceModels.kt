package info.jiayun.intellijmcp.intelligence

import info.jiayun.intellijmcp.api.LocationInfo
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// New intelligence DTOs always use one-based UTF-16 coordinates.
enum class AnalysisStatus { complete, partial, unsupported, not_ready, error }
data class Capability(val status: String, val backend: String, val limitations: List<String> = emptyList())
data class IntelligenceCapabilities(val diagnostics: Capability, val implementations: Capability,
    val incoming: Capability, val outgoing: Capability)
data class IntelligenceSymbol(val name: String, val signature: String?, val location: LocationInfo, val language: String) {
    val id: String get() = "${location.filePath}:${location.line}:${location.column}:${signature.orEmpty()}"
}
data class DiagnosticInfo(val severity: String, val message: String, val location: LocationInfo, val source: String)
data class DiagnosticsResult(val filePath: String, val status: AnalysisStatus, val reason: String? = null,
    val diagnostics: List<DiagnosticInfo> = emptyList(), val backend: String? = null)
data class ImplementationsResult(val status: AnalysisStatus, val reason: String? = null,
    val target: IntelligenceSymbol? = null, val implementations: List<IntelligenceSymbol> = emptyList(),
    val truncated: Boolean = false, val backend: String? = null)
data class CallNode(val id: String, val symbol: IntelligenceSymbol)
data class CallEdge(val from: String, val to: String, val callLocations: List<LocationInfo> = emptyList())
data class CallHierarchyResult(val status: AnalysisStatus, val reason: String? = null,
    val root: String? = null, val nodes: List<CallNode> = emptyList(), val edges: List<CallEdge> = emptyList(),
    val truncated: Boolean = false, val backend: String? = null)
class AnalysisFailure(val status: AnalysisStatus, override val message: String) : RuntimeException(message)

class Deadline(timeout: Int, private val nanoTime: () -> Long = System::nanoTime) {
    private val end = nanoTime() + timeout.toLong() * 1_000_000
    fun remainingMillis(): Long {
        val left = end - nanoTime()
        if (left <= 0 || Thread.currentThread().isInterrupted) throw TimeoutException("Request deadline exceeded or cancelled")
        return maxOf(1, TimeUnit.NANOSECONDS.toMillis(left))
    }
    fun check() { remainingMillis() }
    fun <T> await(future: Future<T>): T = try {
        future.get(remainingMillis(), TimeUnit.MILLISECONDS)
    } finally {
        if (!future.isDone) future.cancel(true)
    }
}

data class IntelligenceOptions(val timeout: Int = 30000, val depth: Int = 1, val maxNodes: Int = 100,
    val limit: Int = 200, val includeLibraries: Boolean = false, val direction: String = "incoming",
    val minSeverity: String = "warning") {
    companion object {
        fun parse(args: Map<String, Any?>): IntelligenceOptions {
            fun integer(key: String, default: Int, max: Int): Int {
                if (!args.containsKey(key)) return default
                val n = args[key] as? Number ?: throw IllegalArgumentException("$key must be an integer")
                val d = n.toDouble()
                require(d.isFinite() && d % 1.0 == 0.0 && d in 1.0..max.toDouble()) { "$key must be an integer between 1 and $max" }
                return d.toInt()
            }
            val libraries = if (args.containsKey("includeLibraries")) args["includeLibraries"] as? Boolean
                ?: throw IllegalArgumentException("includeLibraries must be boolean") else false
            val direction = args["direction"] ?: "incoming"
            require(direction in listOf("incoming", "outgoing")) { "direction must be incoming or outgoing" }
            val severity = args["minSeverity"] ?: "warning"
            require(severity in listOf("error", "warning", "information", "hint")) { "Invalid minSeverity" }
            return IntelligenceOptions(integer("timeout",30000,60000), integer("depth",1,3),
                integer("maxNodes",100,500),integer("limit",200,1000),libraries,direction as String,severity as String)
        }
    }
}
fun severityRank(severity: String): Int = when(severity) { "error" -> 4; "warning" -> 3; "information" -> 2; else -> 1 }

/** Breadth-first graph keeps recursive edges, expanding each declaration at its shallowest depth only. */
class CallGraph(private val options: IntelligenceOptions) {
    private val nodes = linkedMapOf<String, CallNode>()
    private val edges = linkedMapOf<Pair<String,String>, CallEdge>()
    var truncated = false; private set
    fun build(root: IntelligenceSymbol, deadline: Deadline, backend: String,
        expand: (IntelligenceSymbol) -> List<Pair<IntelligenceSymbol,List<LocationInfo>>>): CallHierarchyResult {
        nodes[root.id] = CallNode(root.id, root)
        val queue = ArrayDeque<Pair<IntelligenceSymbol,Int>>()
        queue.add(root to 0)
        var status = AnalysisStatus.complete
        var reason: String? = null
        try {
            while (queue.isNotEmpty()) {
                deadline.check()
                val (current, level) = queue.removeFirst()
                if (level >= options.depth) continue
                for ((next, locations) in expand(current).sortedBy { it.first.id }) {
                    deadline.check()
                    if (next.id !in nodes) {
                        if (nodes.size >= options.maxNodes) { truncated = true; continue }
                        nodes[next.id] = CallNode(next.id, next)
                        queue.add(next to level + 1)
                    }
                    val key = if (options.direction == "incoming") next.id to current.id else current.id to next.id
                    val allLocations = (edges[key]?.callLocations.orEmpty() + locations).distinct()
                        .sortedWith(compareBy({it.filePath},{it.line},{it.column}))
                    edges[key] = CallEdge(key.first,key.second,allLocations)
                }
            }
        } catch (e: Exception) {
            status = if (nodes.size > 1 || edges.isNotEmpty()) AnalysisStatus.partial else failureStatus(e)
            reason = failureMessage(e)
        }
        if (truncated) { status = AnalysisStatus.partial; reason = listOfNotNull(reason,"maxNodes reached").joinToString("; ") }
        return CallHierarchyResult(status,reason,root.id,nodes.values.sortedBy { it.id },
            edges.values.sortedWith(compareBy({it.from},{it.to})),truncated,backend)
    }
}
fun failureMessage(e: Throwable): String = (e.cause ?: e).message ?: e.javaClass.simpleName
fun failureStatus(e: Throwable): AnalysisStatus = when(e) {
    is AnalysisFailure -> e.status
    is com.intellij.openapi.project.IndexNotReadyException -> AnalysisStatus.not_ready
    is com.intellij.openapi.progress.ProcessCanceledException, is TimeoutException, is InterruptedException, is java.util.concurrent.CancellationException -> AnalysisStatus.partial
    else -> if (e.cause != null) failureStatus(e.cause!!) else AnalysisStatus.error
}

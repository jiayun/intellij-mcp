package info.jiayun.intellijmcp.intelligence

import info.jiayun.intellijmcp.api.LocationInfo
import info.jiayun.intellijmcp.mcp.IntelligenceTools
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.test.*

class IntelligenceTest {
    private fun symbol(name: String,line: Int = 1) = IntelligenceSymbol(name,"$name()",LocationInfo("/test.kt",line,1),"kotlin")
    @Test fun `defaults and schema expose all three tools`() {
        assertEquals(setOf("get_diagnostics","find_implementations","get_call_hierarchy"),IntelligenceTools.definitions.map { it.name }.toSet())
        assertEquals(IntelligenceOptions(),IntelligenceOptions.parse(emptyMap()))
        assertEquals(30000,IntelligenceOptions.parse(emptyMap()).timeout)
    }
    @Test fun `reject fractional negative oversized and incorrectly typed options`() {
        for((key,value) in listOf("timeout" to 60001,"timeout" to 0,"timeout" to 2.5,"timeout" to "100",
            "limit" to -1,"limit" to 1001,"maxNodes" to 501,"depth" to 4,"includeLibraries" to "true",
            "direction" to "both","minSeverity" to "fatal")) {
            assertFailsWith<IllegalArgumentException>("$key=$value") { IntelligenceOptions.parse(mapOf(key to value)) }
        }
    }
    @Test fun `one based UTF16 coordinates handle CRLF emoji and EOF`() {
        val text = "a\r\n😀b\n"
        assertEquals(LocationInfo("/x",2,3,3,1),location("/x",text,5,text.length))
        assertEquals(LocationInfo("/x",1,1,1,1),location("/x","",0,0))
    }
    @Test fun `one deadline is shared and pending futures are cancelled`() {
        var now = 0L
        val deadline = Deadline(100) { now }
        assertEquals(100,deadline.remainingMillis())
        now = 75_000_000
        assertEquals(25,deadline.remainingMillis())
        now = 100_000_000
        val pending = CompletableFuture<String>()
        assertFailsWith<TimeoutException> { deadline.await(pending) }
        assertTrue(pending.isCancelled)
    }
    @Test fun `recursive graph retains edges and expands each declaration once`() {
        val a = symbol("a"); val b = symbol("b")
        val visited = mutableListOf<String>()
        val result = CallGraph(IntelligenceOptions(depth=3,direction="outgoing")).build(a,Deadline(1000),"test") {
            visited.add(it.name)
            listOf((if(it == a) b else a) to emptyList())
        }
        assertEquals(listOf("a","b"),visited)
        assertEquals(setOf(a.id to b.id,b.id to a.id),result.edges.map { it.from to it.to }.toSet())
        assertEquals(AnalysisStatus.complete,result.status)
    }
    @Test fun `incoming edge points from caller to callee`() {
        val target = symbol("target"); val caller = symbol("caller")
        val result = CallGraph(IntelligenceOptions()).build(target,Deadline(1000),"test") { listOf(caller to emptyList()) }
        assertEquals(CallEdge(caller.id,target.id),result.edges.single())
    }
    @Test fun `same name in different scopes remains distinct and node bound marks partial`() {
        val root = symbol("root")
        val result = CallGraph(IntelligenceOptions(maxNodes=2)).build(root,Deadline(1000),"test") {
            listOf(symbol("f",2) to emptyList(),symbol("f",3) to emptyList())
        }
        assertEquals(2,result.nodes.size)
        assertTrue(result.truncated)
        assertEquals(AnalysisStatus.partial,result.status)
    }
    @Test fun `depth one stops expanding children without claiming truncation`() {
        var expansions = 0
        val result = CallGraph(IntelligenceOptions()).build(symbol("root"),Deadline(1000),"test") {
            expansions++; listOf(symbol("leaf") to emptyList())
        }
        assertEquals(1,expansions)
        assertFalse(result.truncated)
    }
    @Test fun `failure after first branch retains graph and reports partial`() {
        val root = symbol("root")
        val result = CallGraph(IntelligenceOptions(depth=2)).build(root,Deadline(1000),"test") {
            if(it == root) listOf(symbol("a") to emptyList()) else throw AnalysisFailure(AnalysisStatus.not_ready,"indexing")
        }
        assertEquals(2,result.nodes.size)
        assertEquals(AnalysisStatus.partial,result.status)
        assertEquals("indexing",result.reason)
    }
    @Test fun `unavailable backend cannot look like a complete empty hierarchy`() {
        val result = CallGraph(IntelligenceOptions()).build(symbol("root"),Deadline(1000),"test") {
            throw AnalysisFailure(AnalysisStatus.unsupported,"No provider")
        }
        assertEquals(AnalysisStatus.unsupported,result.status)
        assertTrue(result.edges.isEmpty())
    }
    @Test fun `duplicate edges merge call locations deterministically`() {
        val a = symbol("a"); val b = symbol("b")
        val site1 = LocationInfo("/a",2,1); val site2 = LocationInfo("/a",1,1)
        val result = CallGraph(IntelligenceOptions()).build(a,Deadline(1000),"test") {
            listOf(b to listOf(site1,site2),b to listOf(site1))
        }
        assertEquals(listOf(site2,site1),result.edges.single().callLocations)
    }
    @Test fun `file URI decoding preserves spaces plus signs unicode and metadata URIs`() {
        assertEquals("/tmp/a b+c.swift",lspUriToPath("file:///tmp/a%20b+c.swift"))
        assertEquals("/tmp/a b.swift",lspUriToPath("file:///tmp/a b.swift"))
        assertEquals("/tmp/中文.swift",lspUriToPath(java.nio.file.Path.of("/tmp/中文.swift").toUri().toASCIIString()))
        assertEquals("csharp:/metadata/System.String",lspUriToPath("csharp:/metadata/System.String"))
    }

}

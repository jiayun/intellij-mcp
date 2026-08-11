package info.jiayun.intellijmcp.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpHttpEndpointTest {
    private val gson = GsonBuilder().create()
    private val codec = McpJsonRpcCodec(gson)

    @Test
    fun `request id 0 remains an integer in the raw HTTP response`() = assertRoundTripId("0") { body, id ->
        assertEquals("0", id.toString())
        assertEquals("""{"jsonrpc":"2.0","id":0,"result":{"ok":true}}""", body)
        assertTrue(body.contains("\"id\":0"))
        assertFalse(body.contains("\"id\":0.0"))
    }

    @Test
    fun `positive integer request id is preserved`() = assertRoundTripId("42") { _, id ->
        assertEquals("42", id.toString())
    }

    @Test
    fun `negative integer request id is preserved`() = assertRoundTripId("-1") { _, id ->
        assertEquals("-1", id.toString())
    }

    @Test
    fun `string request id is preserved`() = assertRoundTripId("\"probe\"") { _, id ->
        assertTrue(id.asJsonPrimitive.isString)
        assertEquals("probe", id.asString)
    }

    @Test
    fun `arbitrary precision integer request id is preserved`() {
        val largeId = "922337203685477580812345678901234567890"
        assertRoundTripId(largeId) { body, id ->
            assertEquals(largeId, id.toString())
            assertTrue(body.contains("\"id\":$largeId"))
        }
    }

    @Test
    fun `notification has no response body`() = testApplication {
        val handled = AtomicInteger()
        application {
            routing {
                mcpEndpoint(codec) { request ->
                    handled.incrementAndGet()
                    McpResponse(id = request.id, result = emptyMap<String, Any>())
                }
            }
        }

        val response = client.post("/mcp") {
            setBody("""{"jsonrpc":"2.0","method":"initialized"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
        assertEquals(1, handled.get())
    }

    @Test
    fun `fractional request id is rejected`() = assertInvalidId("1.5")

    @Test
    fun `decimal notation for an integral value is rejected`() = assertInvalidId("0.0")

    @Test
    fun `null request id is rejected`() = assertInvalidId("null")

    @Test
    fun `error response preserves the original request id`() = testApplication {
        application {
            routing {
                mcpEndpoint(codec) { request ->
                    McpResponse(
                        id = request.id,
                        error = McpError(McpError.METHOD_NOT_FOUND, "Method not found: ${request.method}")
                    )
                }
            }
        }

        val response = client.post("/mcp") {
            setBody(request("\"probe\"", method = "missing"))
        }
        val body = response.bodyAsText()
        val json = JsonParser.parseString(body).asJsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("probe", json.get("id").asString)
        assertEquals(McpError.METHOD_NOT_FOUND, json.getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun `transport always uses the request id even if a handler supplies another id`() = testApplication {
        application {
            routing {
                mcpEndpoint(codec) {
                    McpResponse(id = JsonParser.parseString("999"), result = mapOf("ok" to true))
                }
            }
        }

        val response = client.post("/mcp") {
            setBody(request("42"))
        }
        val json = JsonParser.parseString(response.bodyAsText()).asJsonObject

        assertEquals("42", json.get("id").toString())
    }

    private fun assertRoundTripId(idJson: String, assertion: (String, JsonElement) -> Unit) = testApplication {
        application {
            routing {
                mcpEndpoint(codec) { request ->
                    McpResponse(id = request.id, result = mapOf("ok" to true))
                }
            }
        }

        val response = client.post("/mcp") {
            setBody(request(idJson))
        }
        val body = response.bodyAsText()
        val json = JsonParser.parseString(body).asJsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("2.0", json.get("jsonrpc").asString)
        assertion(body, json.get("id"))
    }

    private fun assertInvalidId(idJson: String) = testApplication {
        val handled = AtomicInteger()
        application {
            routing {
                mcpEndpoint(codec) { request ->
                    handled.incrementAndGet()
                    McpResponse(id = request.id, result = mapOf("ok" to true))
                }
            }
        }

        val response = client.post("/mcp") {
            setBody(request(idJson))
        }
        val json = JsonParser.parseString(response.bodyAsText()).asJsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.get("id").isJsonNull)
        assertEquals(McpError.INVALID_REQUEST, json.getAsJsonObject("error").get("code").asInt)
        assertEquals(0, handled.get())
    }

    private fun request(idJson: String, method: String = "initialize") =
        """{"jsonrpc":"2.0","id":$idJson,"method":"$method","params":{}}"""
}

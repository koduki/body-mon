package com.master.healthcoach.data.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TunnelTransportTest {
    private val config = TunnelConfig("tunnel_" + "a".repeat(32), "test-key", "", 15, SharePermissions())
    private fun command(timeout: String = "30s", id: String = "request-1") = """{"request_id":"$id","shard_token":"route-secret","command_type":"jsonrpc","channel":"main","response_timeout":"$timeout","jsonrpc":{"jsonrpc":"2.0","id":7,"method":"ping"}}"""
    @Test fun `timeouts parse zero fractions overflows and legacy values`() {
        assertEquals(30000L, TunnelTransport.responseTimeoutMs(JsonPrimitive("30s")))
        assertEquals(0L, TunnelTransport.responseTimeoutMs(JsonPrimitive("0s")))
        for (v in listOf("4.5s", "-1s", "30d", " 1s", "9999999999999999999h")) assertNull(TunnelTransport.responseTimeoutMs(JsonPrimitive(v)))
        assertNull(TunnelTransport.responseTimeoutMs(JsonPrimitive(30)))
        assertNull(TunnelTransport.responseTimeoutMs(JsonNull))
    }
    @Test fun `poll responds using correct envelope and shard header then stops on auth failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"commands\":[${command()}]}"))
            server.enqueue(MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val transport = TunnelTransport(config, { true }, { req -> McpProtocol.dispatch(req, { JsonArray(emptyList()) }) { _, _ -> JsonObject(emptyMap()) } }, {}, baseUrl = server.url("/").toString().trimEnd('/'), allowLocalTestServer = true)
            try { withTimeout(5000) { transport.run() }; fail("must stop") } catch (_: TunnelAuthFailure) { }
            val poll = server.takeRequest(); assertTrue(poll.path!!.startsWith("/v1/tunnels/${config.id}/poll"))
            assertEquals("Bearer test-key", poll.getHeader("Authorization"))
            val post = server.takeRequest(); assertEquals("route-secret", post.getHeader("X-Tunnel-Shard-Token"))
            val body = Json.parseToJsonElement(post.body.readUtf8()).jsonObject
            assertEquals("request-1", body["request_id"]!!.jsonPrimitive.content)
            assertEquals(7, body["resp_json"]!!.jsonObject["id"]!!.jsonPrimitive.int)
            assertFalse(body.containsKey("shard_token"))
        }
    }
    @Test fun `cancellation closes a stalled poll body promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"commands\":[]}").setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
            server.start()
            val transport = TunnelTransport(config, { true }, { null }, {}, baseUrl = server.url("/").toString().trimEnd('/'), allowLocalTestServer = true)
            val job = launch(Dispatchers.Default) { transport.run() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
    @Test fun `expired command is never dispatched or posted`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"commands\":[${command("0s")}]}") )
            server.enqueue(MockResponse().setResponseCode(403)); server.start()
            var calls = 0
            val transport = TunnelTransport(config, { true }, { calls++; JsonObject(emptyMap()) }, {}, baseUrl = server.url("/").toString().trimEnd('/'), allowLocalTestServer = true)
            try { withTimeout(5000) { transport.run() }; fail() } catch (_: TunnelAuthFailure) { }
            assertEquals(0, calls); assertEquals(2, server.requestCount)
        }
    }
    @Test fun `duplicate command reuses result and stop suppresses pending response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"commands\":[${command()}]}"))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{\"commands\":[${command()}]}"))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(401)); server.start()
            var calls = 0
            val transport = TunnelTransport(config, { true }, { calls++; McpProtocol.error(JsonPrimitive(7), -32601, "unknown") }, {}, baseUrl = server.url("/").toString().trimEnd('/'), allowLocalTestServer = true)
            try { withTimeout(5000) { transport.run() } } catch (_: TunnelAuthFailure) { }
            assertEquals(1, calls)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"commands\":[${command()}]}")); server.start()
            var active = true
            val transport = TunnelTransport(config, { active }, { active = false; JsonObject(emptyMap()) }, {}, baseUrl = server.url("/").toString().trimEnd('/'), allowLocalTestServer = true)
            withTimeout(5000) { transport.run() }
            assertEquals(1, server.requestCount)
        }
    }
}

package com.master.healthcoach.data.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun `write tool is never listed or callable and evidence needs all consents`() {
        assertFalse(McpTools.available(SharePermissions(true, true, true, true)).toString().contains("update_profile"))
        assertEquals("UNKNOWN_TOOL", runCatching { McpTools.check("update_profile", obj("{}"), SharePermissions(true, true, true, true)) }.exceptionOrNull()?.message)
        assertEquals("PERMISSION_DENIED", runCatching { McpTools.check("get_response_evidence", obj("{\"messageId\":1}"), SharePermissions(false, true, true, true)) }.exceptionOrNull()?.message)
        val diagnosticsOnly = McpTools.available(SharePermissions(false, false, true, false)).toString()
        assertTrue(diagnosticsOnly.contains("get_app_diagnostics"))
        assertFalse(diagnosticsOnly.contains("search_conversations"))
        assertFalse(diagnosticsOnly.contains("get_health_metrics"))
    }
    @Test fun `tool arguments reject unknown keys wrong types and unbounded strings`() {
        for (args in listOf(obj("{\"messageId\":\"1\"}"), obj("{\"messageId\":1,\"sql\":\"DELETE\"}"), obj("{}"))) {
            assertEquals("INVALID_ARGUMENT", runCatching { McpTools.check("get_response_evidence", args, SharePermissions(true,true,true,true)) }.exceptionOrNull()?.message)
        }
    }
    @Test fun `initialize preserves id and notifications receive no json rpc response`() = runTest {
        val reply = McpProtocol.dispatch(obj("""{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""), { JsonArray(emptyList()) }) { _, _ -> error("unused") }!!
        assertEquals("a", reply["id"]!!.jsonPrimitive.content)
        assertEquals("2025-06-18", reply["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(McpProtocol.dispatch(obj("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), { JsonArray(emptyList()) }) { _, _ -> error("unused") })
    }
    @Test fun `permission denied returns bounded tool error and does not leak exception text`() = runTest {
        val reply = McpProtocol.dispatch(obj("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_conversations"}}"""), { JsonArray(emptyList()) }) { name, args ->
            McpTools.check(name, args, SharePermissions()); error("should not execute")
        }!!
        assertTrue(reply["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertTrue(reply.toString().contains("PERMISSION_DENIED"))
        val error = McpProtocol.dispatch(obj("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"x"}}"""), { JsonArray(emptyList()) }) { _, _ -> throw IllegalStateException("secret-key") }!!
        assertFalse(error.toString().contains("secret-key"))
    }
    @Test fun `cursor cannot cross data revisions or filters`() {
        val cursor = HealthMcpBackend.encodeCursor("1", "scope", "50")
        assertEquals("50", HealthMcpBackend.decodeCursor(cursor,"1","scope"))
        assertEquals("STALE_CURSOR", runCatching { HealthMcpBackend.decodeCursor(cursor,"2","scope") }.exceptionOrNull()?.message)
        assertEquals("INVALID_CURSOR", runCatching { HealthMcpBackend.decodeCursor(cursor,"1","other") }.exceptionOrNull()?.message)
    }
}

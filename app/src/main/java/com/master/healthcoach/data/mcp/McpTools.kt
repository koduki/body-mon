package com.master.healthcoach.data.mcp

import kotlinx.serialization.json.*

data class McpTool(val name: String, val description: String, val scope: String, val fields: Map<String, String> = emptyMap(), val required: List<String> = emptyList())

object McpTools {
    private val dates = mapOf("from" to "string", "to" to "string")
    private val page = mapOf("cursor" to "string", "limit" to "integer")
    val all = listOf(
        McpTool("get_app_context", "Read first: schema, timezone, session expiry, sync status and available permissions. No health values.", "basic"),
        McpTool("get_health_metrics", "Read cached health metrics. metric: body/activity/exercise/sleep/heart_rate/intensity/metabolism/nutrition. Dates YYYY-MM-DD, at most 90 days. limit is days per page (1..7). Today is partial. Follow nextCursor.", "health", dates + page + mapOf("metric" to "string"), listOf("metric")),
        McpTool("get_profile_and_goals", "Current user profile and goals; settings can be updated by the user in the app. Read only.", "health"),
        McpTool("list_weekly_reports", "Saved report snapshots, at most 90 days, limit 1..100; follow nextCursor. Legacy weekly cache has limited historical coverage.", "health", dates + page),
        McpTool("get_weekly_report", "Get saved reportId, latest cache, or explicitly reconstruct a completed week using periodEnd YYYY-MM-DD. Reconstructed is not the original historical report.", "health", mapOf("reportId" to "string", "periodEnd" to "string")),
        McpTool("get_data_quality", "Missing metrics, nutrition confirmation, heart-rate coverage and origin candidates (not proof of contribution). limit is 1..7 days per page.", "health", dates + page),
        McpTool("search_conversations", "Search retained chat messages by dates, literal query and role. Returns short excerpts. limit 1..100, follow nextCursor.", "conversations", dates + page + mapOf("query" to "string", "role" to "string")),
        McpTool("get_conversation_context", "Get message and up to 5 messages before/after. contentOffset retrieves next text chunk (4000 characters) for the selected message. includeMemory optionally returns bounded memory; memoryOffset pages it.", "conversations", mapOf("messageId" to "integer", "before" to "integer", "after" to "integer", "contentOffset" to "integer", "includeMemory" to "boolean", "memoryOffset" to "integer"), listOf("messageId")),
        McpTool("get_app_diagnostics", "App/OS/code versions, permissions and bounded event summary. No chat content or health values.", "diagnostics", dates),
        McpTool("list_app_events", "Local event timeline: category sync/report/chat/weekly_ai/ui_sync/ui_goal/ui_tab/tunnel/mcp. limit 1..100. No free-text logs.", "diagnostics", dates + page + mapOf("category" to "string")),
        McpTool("get_response_evidence", "Evidence for an assistant message: tool trace and optional captured initial prompt. Unrecorded inputs stay unavailable. offset pages the JSON text in 16000-character chunks.", "evidence", mapOf("messageId" to "integer", "offset" to "integer"), listOf("messageId")),
        McpTool("get_calculation_trace", "Saved report inputs, output and algorithm/code version, or reconstructed current inputs for periodEnd. offset pages JSON text in 16000-character chunks. Not model chain of thought.", "evidence", mapOf("reportId" to "string", "periodEnd" to "string", "offset" to "integer")),
    )
    fun available(permissions: SharePermissions): JsonArray = buildJsonArray {
        all.filter { permissions.allows(it.scope) }.forEach { tool -> add(buildJsonObject {
            put("name", tool.name); put("description", tool.description)
            put("inputSchema", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject { tool.fields.forEach { (name, type) -> put(name, buildJsonObject { put("type", type) }) } })
                put("required", buildJsonArray { tool.required.forEach { add(JsonPrimitive(it)) } })
            })
            put("annotations", buildJsonObject { put("readOnlyHint", true); put("destructiveHint", false); put("idempotentHint", true); put("openWorldHint", false) })
        }) }
    }
    fun check(name: String, args: JsonObject, permissions: SharePermissions): McpTool {
        val tool = all.find { it.name == name } ?: throw McpFailure("UNKNOWN_TOOL")
        if (!permissions.allows(tool.scope)) throw McpFailure("PERMISSION_DENIED")
        if (!args.keys.all { it in tool.fields } || !tool.required.all { it in args }) throw McpFailure("INVALID_ARGUMENT")
        args.forEach { (key, value) ->
            val p = value as? JsonPrimitive ?: throw McpFailure("INVALID_ARGUMENT")
            val valid = when (tool.fields[key]) { "integer" -> !p.isString && p.longOrNull != null; "boolean" -> !p.isString && p.booleanOrNull != null; else -> p.isString && p.content.length <= 1024 }
            if (!valid) throw McpFailure("INVALID_ARGUMENT")
        }
        return tool
    }
}

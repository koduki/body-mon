package com.master.healthcoach.data.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class GeminiTurn(val role: String, val text: String)

data class GeminiFunctionCall(
    val name: String,
    val arguments: JsonObject,
)

data class GeminiResult(
    val text: String?,
    val functionCalls: List<GeminiFunctionCall>,
    val modelParts: JsonArray,
)

class GeminiClient(
    private val json: Json,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun chatWithTools(
        apiKey: String,
        model: String,
        systemInstruction: String,
        turns: List<GeminiTurn>,
        toolExecutor: suspend (GeminiFunctionCall) -> JsonElement,
    ): String {
        val contents = turnsToContents(turns).toMutableList()
        var result = generate(apiKey, model, systemInstruction, contents, healthTools())
        var toolRounds = 0
        while (result.functionCalls.isNotEmpty() && toolRounds < MAX_TOOL_ROUNDS) {
            val responses = result.functionCalls.map { call ->
                call to toolExecutor(call)
            }
            contents += buildJsonObject {
                put("role", "model")
                put("parts", result.modelParts)
            }
            contents += buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    responses.forEach { (call, response) ->
                        add(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", call.name)
                                put("response", response)
                            })
                        })
                    }
                })
            }
            result = generate(apiKey, model, systemInstruction, contents, healthTools())
            toolRounds++
        }
        return result.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: error("Geminiからテキスト応答が返りませんでした")
    }

    suspend fun generateStructuredAdvice(
        apiKey: String,
        model: String,
        systemInstruction: String,
        prompt: String,
    ): String {
        val body = buildRequestBody(
            systemInstruction = systemInstruction,
            contents = turnsToContents(listOf(GeminiTurn("user", prompt))),
            tools = null,
            generationConfig = buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", adviceSchema())
                put("temperature", 0.3)
            },
        )
        return execute(apiKey, model, body).text
            ?: error("Geminiから構造化応答が返りませんでした")
    }

    suspend fun summarizeConversation(
        apiKey: String,
        model: String,
        currentMemory: String?,
        messages: List<GeminiTurn>,
    ): String {
        val transcript = messages.joinToString("\n") { "${it.role}: ${it.text}" }
        val prompt = """
            次の健康コーチ会話を、次回以降の会話に必要な長期メモリーへ圧縮してください。
            数値の一時的な値より、利用者の目標、期限、好み、制約、継続中の方針を優先します。
            事実を追加せず、日本語の箇条書きで800文字以内にしてください。

            既存メモリー:
            ${currentMemory.orEmpty()}

            会話:
            $transcript
        """.trimIndent()
        val result = generate(
            apiKey = apiKey,
            model = model,
            systemInstruction = "あなたは会話メモリーを安全に圧縮する編集者です。",
            contents = turnsToContents(listOf(GeminiTurn("user", prompt))),
            tools = null,
        )
        return result.text?.trim() ?: currentMemory.orEmpty()
    }

    private suspend fun generate(
        apiKey: String,
        model: String,
        systemInstruction: String,
        contents: List<JsonObject>,
        tools: JsonArray?,
    ): GeminiResult {
        val root = execute(
            apiKey,
            model,
            buildRequestBody(systemInstruction, contents, tools, null),
        ).root
        val parts = root["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            .orEmpty()
        val text = parts.firstNotNullOfOrNull {
            it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }
        val functionCalls = parts.mapNotNull { part ->
            part.jsonObject["functionCall"]?.jsonObject?.let {
                GeminiFunctionCall(
                    name = it.getValue("name").jsonPrimitive.content,
                    arguments = it["args"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        return GeminiResult(
            text = text,
            functionCalls = functionCalls,
            modelParts = JsonArray(parts),
        )
    }

    private data class RawResponse(val root: JsonObject, val text: String?)

    private suspend fun execute(
        apiKey: String,
        model: String,
        body: JsonObject,
    ): RawResponse = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "${model}:generateContent"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gemini APIエラー (${response.code}): ${safeError(raw)}")
            }
            val root = json.parseToJsonElement(raw).jsonObject
            val text = root["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            RawResponse(root, text)
        }
    }

    private fun buildRequestBody(
        systemInstruction: String,
        contents: List<JsonObject>,
        tools: JsonArray?,
        generationConfig: JsonObject?,
    ) = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray { add(buildJsonObject { put("text", systemInstruction) }) })
        })
        put("contents", JsonArray(contents))
        tools?.let { put("tools", it) }
        generationConfig?.let { put("generationConfig", it) }
    }

    private fun turnsToContents(turns: List<GeminiTurn>): List<JsonObject> = turns.map {
        buildJsonObject {
            put("role", if (it.role == "assistant") "model" else "user")
            put("parts", buildJsonArray { add(buildJsonObject { put("text", it.text) }) })
        }
    }

    private fun healthTools() = buildJsonArray {
        add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                add(function("get_body_composition", "指定期間の体重、脂肪量、除脂肪量を取得します"))
                add(function("get_activity_summary", "指定期間の歩数、距離、活動消費を取得します"))
                add(function("get_exercise_summary", "指定期間の運動回数と運動時間を取得します"))
                add(buildJsonObject {
                    put("name", "get_goal_progress")
                    put("description", "現在の目標と直近の進捗を取得します")
                })
            })
        })
    }

    private fun function(name: String, description: String) = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("from", buildJsonObject {
                    put("type", "string")
                    put("description", "開始日 YYYY-MM-DD。省略時は28日前")
                })
                put("to", buildJsonObject {
                    put("type", "string")
                    put("description", "終了日 YYYY-MM-DD。省略時は今日")
                })
            })
        })
    }

    private fun adviceSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("summary", stringSchema())
            put("positiveChange", nullableStringSchema())
            put("caution", nullableStringSchema())
            put("nextActions", buildJsonObject {
                put("type", "array")
                put("items", stringSchema())
                put("maxItems", 3)
            })
            put("confidence", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("high"); add("medium"); add("low") })
            })
            put("dataLimitations", buildJsonObject {
                put("type", "array")
                put("items", stringSchema())
            })
        })
        put("required", buildJsonArray {
            add("summary")
            add("nextActions")
            add("confidence")
            add("dataLimitations")
        })
    }

    private fun stringSchema() = buildJsonObject { put("type", "string") }
    private fun nullableStringSchema() = buildJsonObject {
        put("type", "string")
    }

    private fun safeError(raw: String): String = runCatching {
        json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: "詳細を取得できませんでした"

    companion object {
        private const val MAX_TOOL_ROUNDS = 3
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

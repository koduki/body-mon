package com.master.healthcoach.data.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

class TunnelAuthFailure : IOException("TUNNEL_AUTH_FAILED")
class TunnelProtocolFailure : IOException("TUNNEL_PROTOCOL_FAILED")

data class TunnelCounters(var polls: Long = 0, var emptyPolls: Long = 0, var retries: Long = 0, var bytes: Long = 0, var tools: Long = 0, var errors: Long = 0)

/** Outbound-only client. No local listening socket, no arbitrary forwarding URL. */
class TunnelTransport(
    private val config: TunnelConfig,
    private val active: () -> Boolean,
    private val dispatch: suspend (JsonObject) -> JsonObject?,
    private val status: (String) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://api.openai.com",
    private val allowLocalTestServer: Boolean = false,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val counters = TunnelCounters()
    private val instanceId = UUID.randomUUID().toString()
    private val cache = object : LinkedHashMap<String, Pair<String, JsonObject>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, JsonObject>>?) = size > 32
    }
    init {
        require(TunnelSettings.ID.matches(config.id))
        require(baseUrl == "https://api.openai.com" || (allowLocalTestServer && baseUrl.startsWith("http://localhost:")))
    }
    suspend fun run() {
        var failures = 0
        while (currentCoroutineContext().isActive && active()) {
            try {
                val response = exchange("poll?limit=2&timeout_ms=15000")
                counters.polls++
                when (response.code) {
                    200 -> {
                        status("connected"); failures = 0
                        val envelope = Json.parseToJsonElement(response.body).jsonObject
                        val commands = envelope["commands"]?.jsonArray ?: throw TunnelProtocolFailure()
                        // No excess command is discarded; process in bounded groups, with one poll outstanding.
                        commands.chunked(2).forEach { group -> coroutineScope {
                            group.map { command -> async {
                                val cmd = command as? JsonObject ?: throw TunnelProtocolFailure()
                                process(cmd, response.receivedAt)
                            } }.awaitAll()
                        } }
                    }
                    204 -> { counters.emptyPolls++; failures = 0; status("connected") }
                    401, 403 -> throw TunnelAuthFailure()
                    429, in 500..599 -> { status("reconnecting"); counters.retries++; backoff(failures++, response.retryAfter) }
                    else -> throw TunnelProtocolFailure()
                }
            } catch (e: CancellationException) { throw e
            } catch (e: TunnelAuthFailure) { throw e
            } catch (e: TunnelProtocolFailure) { throw e
            } catch (_: IOException) { status("reconnecting"); counters.retries++; backoff(failures++, null)
            } catch (_: IllegalArgumentException) { throw TunnelProtocolFailure() }
        }
    }
    private suspend fun process(command: JsonObject, receivedAt: Long) {
        if (!active()) return
        val id = command.string("request_id")
        val token = command.string("shard_token")
        val channel = command.string("channel").ifBlank { "main" }
        if (id.isBlank() || token.isBlank() || token.any { it == '\r' || it == '\n' } || channel != "main") { counters.errors++; return }
        val remaining = responseTimeoutMs(command["response_timeout"])?.let { it - (monotonicMs() - receivedAt) } ?: 30_000L
        if (remaining <= 0) return
        withTimeoutOrNull(remaining.coerceAtMost(60_000)) {
            val rpc = command["jsonrpc"] as? JsonObject
            val signature = HealthMcpBackend.digest(command.string("command_type") + (rpc?.toString() ?: ""))
            val key = "$channel:$id"
            val cached = synchronized(cache) { cache[key] }
            if (cached != null && cached.first != signature) { counters.errors++; return@withTimeoutOrNull }
            val result = cached?.second ?: when (command.string("command_type")) {
                "session_termination" -> envelope(id, channel, null, "session_termination_response", 204)
                "jsonrpc" -> {
                    if (rpc == null) { counters.errors++; return@withTimeoutOrNull }
                    counters.tools++
                    val response = dispatch(rpc)
                    envelope(id, channel, response, if (response == null) "notify_ack" else "jsonrpc_response", if (response == null) 202 else 200)
                }
                else -> { counters.errors++; return@withTimeoutOrNull }
            }
            if (!active()) return@withTimeoutOrNull
            synchronized(cache) { cache[key] = signature to result }
            val text = result.toString()
            if (text.toByteArray().size > McpProtocol.MAX_BYTES) { counters.errors++; return@withTimeoutOrNull }
            var attempt = 0
            while (active() && currentCoroutineContext().isActive) {
                val posted = try { exchange("response", text, token) } catch (e: IOException) {
                    counters.retries++; backoff(attempt++, null); continue
                }
                when (posted.code) {
                    in 200..299, 404 -> return@withTimeoutOrNull
                    401, 403 -> throw TunnelAuthFailure()
                    408, 429, 502, 503, 504 -> { counters.retries++; backoff(attempt++, posted.retryAfter) }
                    else -> { counters.errors++; return@withTimeoutOrNull }
                }
            }
        }
    }
    private fun envelope(id: String, channel: String, rpc: JsonObject?, type: String, code: Int) = buildJsonObject {
        put("request_id", id); put("channel", channel); put("resp_type", type); put("resp_code", code)
        if (rpc != null) { put("resp_json", rpc); put("resp_headers", buildJsonObject { put("Content-Type", buildJsonArray { add(JsonPrimitive("application/json")) }) }) }
    }
    private suspend fun backoff(attempt: Int, retryAfter: String?) {
        val exponential = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000)
        delay(maxOf(exponential + Random.nextLong(0, 500), retryAfterMs(retryAfter)).coerceAtMost(60_000))
    }
    private data class WireResponse(val code: Int, val body: String, val retryAfter: String?, val receivedAt: Long)
    private suspend fun exchange(path: String, body: String? = null, shardToken: String? = null): WireResponse {
        if (!active()) throw CancellationException("Session ended")
        val builder = Request.Builder().url("$baseUrl/v1/tunnels/${config.id}/$path")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Tunnel-Client-Name", "body-mon-android").header("X-Tunnel-Client-Version", "1.0.0")
            .header("X-Tunnel-Client-Instance-Id", instanceId)
            .header("X-Tunnel-Client-Wire-Protocol-Version", "2026-08-25")
            .header("X-Tunnel-MCP-Server-Info", "{\"version\":1,\"channels\":[{\"name\":\"main\",\"proc_affinity\":true}]}")
        if (config.organization.isNotBlank()) builder.header("OpenAI-Organization", config.organization)
        if (shardToken != null) builder.header("X-Tunnel-Shard-Token", shardToken)
        if (body != null) { builder.post(body.toRequestBody("application/json".toMediaType())); counters.bytes += body.toByteArray().size }
        return await(client.newCall(builder.build()))
    }
    // Keep cancellation attached to the Call until the entire bounded body is read.
    private suspend fun await(call: Call): WireResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val receivedAt = monotonicMs()
                try {
                    val result = response.use {
                        val source = it.body?.source()
                        if (source != null && source.request(1_048_577)) throw TunnelProtocolFailure()
                        val text = source?.readUtf8().orEmpty()
                        synchronized(counters) { counters.bytes += text.toByteArray().size }
                        WireResponse(it.code, text, it.header("Retry-After"), receivedAt)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
    companion object {
        fun responseTimeoutMs(value: JsonElement?): Long? {
            val p = value as? JsonPrimitive ?: return null
            if (!p.isString) return null
            val match = Regex("^(\\d+)(ns|us|ms|s|m|h)$").matchEntire(p.content) ?: return null
            val n = match.groupValues[1].toLongOrNull() ?: return null
            val unit = match.groupValues[2]
            return runCatching {
                val nanos = Math.multiplyExact(n, when(unit) { "ns" -> 1L; "us" -> 1000L; "ms" -> 1_000_000L; "s" -> 1_000_000_000L; "m" -> 60_000_000_000L; else -> 3_600_000_000_000L })
                nanos / 1_000_000
            }.getOrNull()
        }
        fun retryAfterMs(value: String?): Long {
            if (value == null) return 0
            val seconds = value.toLongOrNull()
            if (seconds != null) return seconds.coerceIn(0, 60) * 1000
            return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0, 60_000) }.getOrDefault(0)
        }
    }
}

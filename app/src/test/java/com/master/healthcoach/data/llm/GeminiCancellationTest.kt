package com.master.healthcoach.data.llm

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiCancellationTest {
    @Test fun `stop cancels a response whose body has not arrived`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"candidates\":[]}").setBodyDelay(3, TimeUnit.SECONDS))
            server.start()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/generate")).build())
            }.build()
            val gemini = GeminiClient(Json, http)
            var returned = false
            val job = launch(Dispatchers.Default) {
                gemini.chatWithTools("test-key", "test-model", "test", listOf(GeminiTurn("user", "test"))) { JsonNull }
                returned = true
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertFalse(returned)
        }
    }
}

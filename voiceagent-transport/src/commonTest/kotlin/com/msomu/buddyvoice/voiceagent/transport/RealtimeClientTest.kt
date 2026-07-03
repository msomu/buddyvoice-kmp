package com.msomu.buddyvoice.voiceagent.transport

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class RealtimeClientTest {

    @Test
    fun postJsonSendsHeadersAndBodyAndReturnsResponse() = runTest {
        var seenAuth: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenAuth = request.headers["X-BuddyVoice-Proxy-Key"]
            seenBody = request.body.toByteArray().decodeToString()
            respond(
                content = """{"value":"token-123"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }

        val response = RealtimeClient(engine).use { client ->
            client.postJson(
                url = "https://proxy.example.dev/session/grok",
                headers = mapOf("X-BuddyVoice-Proxy-Key" to "secret"),
                body = """{"hello":true}""",
            )
        }

        assertEquals(200, response.status)
        assertEquals("""{"value":"token-123"}""", response.body)
        assertEquals("secret", seenAuth)
        assertEquals("""{"hello":true}""", seenBody)
    }

    @Test
    fun postJsonDoesNotThrowOnErrorStatus() = runTest {
        val engine = MockEngine {
            respond(content = """{"error":"unauthorized"}""", status = HttpStatusCode.Unauthorized)
        }

        val response = RealtimeClient(engine).use { client ->
            client.postJson("https://proxy.example.dev/session/grok")
        }

        assertEquals(401, response.status)
        assertEquals("""{"error":"unauthorized"}""", response.body)
    }
}

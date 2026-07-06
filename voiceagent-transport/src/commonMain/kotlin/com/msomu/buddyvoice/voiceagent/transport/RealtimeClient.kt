package com.msomu.buddyvoice.voiceagent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Thin transport for realtime voice providers: one JSON POST (token minting)
 * plus a text-frame WebSocket. Shared by WebSocket-based provider modules;
 * WebRTC-based providers own their transport instead.
 */
class RealtimeClient private constructor(
    private val http: HttpClient,
) : AutoCloseable {

    constructor(engine: HttpClientEngineFactory<*> = defaultEngine()) :
        this(HttpClient(engine) { install(WebSockets) })

    /** For tests and callers that manage their own engine instance (e.g. Ktor's MockEngine). */
    constructor(engine: HttpClientEngine) :
        this(HttpClient(engine) { install(WebSockets) })

    /** POSTs [body] as JSON and returns the raw status + body text. Does not throw on non-2xx. */
    suspend fun postJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "{}",
    ): HttpTextResponse {
        val response = http.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return HttpTextResponse(status = response.status.value, body = response.bodyAsText())
    }

    /**
     * Opens a WebSocket to [url].
     *
     * [headers] are applied on platforms whose engines support custom handshake
     * headers (Android/JVM/iOS); browser engines ignore them. [subprotocols] are
     * sent as `Sec-WebSocket-Protocol`, the browser-compatible way to carry a
     * credential — pass both and each platform uses what it can.
     */
    suspend fun connectWebSocket(
        url: String,
        headers: Map<String, String> = emptyMap(),
        subprotocols: List<String> = emptyList(),
    ): WebSocketConnection {
        val session = http.webSocketSession(url) {
            headers.forEach { (name, value) -> header(name, value) }
            if (subprotocols.isNotEmpty()) {
                header(HttpHeaders.SecWebSocketProtocol, subprotocols.joinToString(", "))
            }
        }
        return KtorWebSocketConnection(session)
    }

    override fun close() {
        http.close()
    }
}

/** A connected WebSocket exchanging text frames. */
interface WebSocketConnection {

    /**
     * Incoming text frames. Completes when the peer closes the socket normally,
     * throws on abnormal closure. Binary/control frames are skipped.
     */
    val incoming: Flow<String>

    suspend fun send(text: String)

    suspend fun close(code: Short = CloseReason.Codes.NORMAL.code, reason: String = "client closed")
}

data class HttpTextResponse(val status: Int, val body: String)

private class KtorWebSocketConnection(
    private val session: DefaultWebSocketSession,
) : WebSocketConnection {

    override val incoming: Flow<String> = flow {
        for (frame in session.incoming) {
            if (frame is Frame.Text) emit(frame.readText())
        }
    }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close(code: Short, reason: String) {
        session.close(CloseReason(code, reason))
    }
}

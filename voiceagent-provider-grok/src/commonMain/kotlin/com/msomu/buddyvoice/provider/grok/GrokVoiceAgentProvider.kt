package com.msomu.buddyvoice.provider.grok

import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentProvider
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentSession
import com.msomu.buddyvoice.voiceagent.transport.RealtimeClient

/**
 * [VoiceAgentProvider] for xAI's Grok Voice Agent API.
 *
 * Connection flow (no long-lived key ever reaches the client):
 * 1. `POST {proxyBaseUrl}/session/grok` with the `X-BuddyVoice-Proxy-Key` header;
 *    the proxy mints a short-lived ephemeral token via xAI's `client_secrets` API.
 * 2. Connect directly to `wss://api.x.ai/v1/realtime` with that token — as an
 *    `Authorization` header where supported, and as the `xai-client-secret.` WebSocket
 *    subprotocol for browsers.
 */
class GrokVoiceAgentProvider(
    private val client: RealtimeClient = RealtimeClient(),
) : VoiceAgentProvider {

    override val id: String = "grok"

    override suspend fun connect(config: VoiceAgentConfig): VoiceAgentSession {
        val response = client.postJson(
            url = config.proxyBaseUrl.trimEnd('/') + "/session/grok",
            headers = buildMap {
                config.proxyKey?.let { put("X-BuddyVoice-Proxy-Key", it) }
            },
        )
        if (response.status !in 200..299) {
            throw GrokConnectException(
                "Proxy returned ${response.status}: ${response.body.take(200)}",
            )
        }
        val token = parseEphemeralToken(response.body)

        val model = config.extra["model"] ?: DEFAULT_MODEL
        val ws = client.connectWebSocket(
            url = "$REALTIME_URL?model=$model",
            headers = mapOf("Authorization" to "Bearer $token"),
            subprotocols = listOf("xai-client-secret.$token"),
        )
        return GrokVoiceAgentSession(ws, config).also { it.start() }
    }

    private companion object {
        const val REALTIME_URL = "wss://api.x.ai/v1/realtime"
        const val DEFAULT_MODEL = "grok-voice-latest"
    }
}

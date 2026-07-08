package com.msomu.buddyvoice.provider.openai

import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentProvider
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentSession
import com.msomu.buddyvoice.voiceagent.transport.RealtimeClient

/**
 * [VoiceAgentProvider] for OpenAI's Realtime API.
 *
 * Connection flow (no long-lived key ever reaches the client):
 * 1. `POST {proxyBaseUrl}/session/openai` with the `X-BuddyVoice-Proxy-Key` header;
 *    the proxy mints a short-lived ephemeral token via OpenAI's `client_secrets` API.
 * 2. Connect directly to `wss://api.openai.com/v1/realtime` with that token — as an
 *    `Authorization` header where supported, and as OpenAI's documented
 *    `realtime` + `openai-insecure-api-key.` WebSocket subprotocols for browsers.
 *
 * `config.extra["model"]` selects the realtime model (default [DEFAULT_MODEL]);
 * `config.extra["transcriptionModel"]` overrides the user-ASR model.
 */
class OpenAIRealtimeProvider(
    private val client: RealtimeClient = RealtimeClient(),
) : VoiceAgentProvider {

    override val id: String = "openai-realtime"

    override suspend fun connect(config: VoiceAgentConfig): VoiceAgentSession {
        val response = client.postJson(
            url = config.proxyBaseUrl.trimEnd('/') + "/session/openai",
            headers = buildMap {
                config.proxyKey?.let { put("X-BuddyVoice-Proxy-Key", it) }
            },
        )
        if (response.status !in 200..299) {
            throw OpenAIConnectException(
                "Proxy returned ${response.status}: ${response.body.take(200)}",
            )
        }
        val token = parseEphemeralToken(response.body)

        val model = config.extra["model"] ?: DEFAULT_MODEL
        val ws = client.connectWebSocket(
            url = "$REALTIME_URL?model=$model",
            headers = mapOf("Authorization" to "Bearer $token"),
            subprotocols = listOf("realtime", "openai-insecure-api-key.$token"),
        )
        return OpenAIRealtimeSession(ws, config).also { it.start() }
    }

    private companion object {
        const val REALTIME_URL = "wss://api.openai.com/v1/realtime"
        const val DEFAULT_MODEL = "gpt-realtime"
    }
}

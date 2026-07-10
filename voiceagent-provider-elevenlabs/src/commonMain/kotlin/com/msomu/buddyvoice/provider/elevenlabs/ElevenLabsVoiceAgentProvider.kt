package com.msomu.buddyvoice.provider.elevenlabs

import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentProvider
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentSession
import com.msomu.buddyvoice.voiceagent.transport.RealtimeClient

/**
 * [VoiceAgentProvider] for ElevenLabs Agents (Conversational AI).
 *
 * ElevenLabs agents are configured server-side in the ElevenLabs dashboard, so
 * unlike Grok/OpenAI the conversation shape mostly lives there; the library
 * sends [VoiceAgentConfig.systemPrompt]/[VoiceAgentConfig.voice] as overrides,
 * which only apply if the agent's security settings allow overrides.
 *
 * Connection flow (no long-lived key ever reaches the client):
 * 1. `POST {proxyBaseUrl}/session/elevenlabs` with the `X-BuddyVoice-Proxy-Key`
 *    header (optional JSON body `{"agentId": ...}` from `config.extra["agentId"]`);
 *    the proxy calls ElevenLabs' `get-signed-url` API with its `xi-api-key`.
 * 2. Connect directly to the returned signed WebSocket URL — the short-lived
 *    credential is embedded in the URL, so no auth headers are needed (and the
 *    same code path works in browsers).
 *
 * Requirements on the agent (ElevenLabs dashboard):
 * - Voice settings: input AND output format set to "PCM 16000 Hz" (the library's
 *   boundary format). A mismatch surfaces as an [ElevenLabsProtocolException]
 *   error event at connect time.
 * - Security settings: enable overrides for prompt/voice if you want
 *   [VoiceAgentConfig] values to take effect.
 */
class ElevenLabsVoiceAgentProvider(
    private val client: RealtimeClient = RealtimeClient(),
) : VoiceAgentProvider {

    override val id: String = "elevenlabs"

    override suspend fun connect(config: VoiceAgentConfig): VoiceAgentSession {
        val agentId = config.extra["agentId"]
        val response = client.postJson(
            url = config.proxyBaseUrl.trimEnd('/') + "/session/elevenlabs",
            headers = buildMap {
                config.proxyKey?.let { put("X-BuddyVoice-Proxy-Key", it) }
            },
            body = if (agentId != null) """{"agentId":"$agentId"}""" else "{}",
        )
        if (response.status !in 200..299) {
            throw ElevenLabsConnectException(
                "Proxy returned ${response.status}: ${response.body.take(200)}",
            )
        }
        val signedUrl = parseSignedUrl(response.body)

        val ws = client.connectWebSocket(url = signedUrl)
        return ElevenLabsSession(ws, config).also { it.start() }
    }
}

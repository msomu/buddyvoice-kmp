package com.msomu.buddyvoice.voiceagent.core

/**
 * Entry point to a realtime voice AI provider (Grok, OpenAI Realtime, ElevenLabs, ...).
 *
 * Implementations live in their own `voiceagent-provider-*` module and are the only
 * place that knows the provider's wire protocol. App code depends on this interface
 * alone, so switching providers is a configuration change, not a rewrite.
 */
interface VoiceAgentProvider {

    /** Stable identifier for this provider, e.g. `"grok"`, `"openai-realtime"`, `"elevenlabs"`. */
    val id: String

    /**
     * Opens a realtime session with the provider.
     *
     * Credentials are never part of [config]: implementations call the token-mint
     * proxy at [VoiceAgentConfig.proxyBaseUrl] to obtain a short-lived credential,
     * then connect to the provider with it.
     *
     * @throws Exception if the proxy rejects the request or the provider connection fails.
     */
    suspend fun connect(config: VoiceAgentConfig): VoiceAgentSession
}

package com.msomu.buddyvoice.voiceagent.core

/**
 * Configuration for a voice agent session.
 *
 * Deliberately provider-agnostic: no field here may carry a provider API key.
 * Real credentials live server-side behind the proxy at [proxyBaseUrl].
 */
data class VoiceAgentConfig(
    /**
     * Base URL of your deployed token-mint proxy (see `server-proxy/`), e.g.
     * `https://buddyvoice-proxy.example.workers.dev`. Never a raw provider URL
     * with a key in it.
     */
    val proxyBaseUrl: String,

    /**
     * Shared secret sent to the proxy as the `X-BuddyVoice-Proxy-Key` header.
     * A basic gate against casual scraping of a public proxy URL — not user auth.
     */
    val proxyKey: String? = null,

    /** System prompt establishing the agent's persona and instructions. */
    val systemPrompt: String,

    /** Provider-specific voice name (e.g. Grok's `"eve"`), or `null` for the provider default. */
    val voice: String? = null,

    /** Provider-specific overrides, e.g. `mapOf("model" to "grok-voice-latest")`. */
    val extra: Map<String, String> = emptyMap(),
)

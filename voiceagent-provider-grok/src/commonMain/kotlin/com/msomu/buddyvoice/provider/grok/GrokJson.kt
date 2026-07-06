package com.msomu.buddyvoice.provider.grok

import kotlinx.serialization.json.Json

/**
 * Lenient JSON for the Grok wire protocol: xAI's realtime schema is evolving,
 * so unknown fields and event types must never crash a session.
 */
internal val grokJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/** The proxy or the Grok connection handshake failed. */
class GrokConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Grok reported an error event over the realtime socket. */
class GrokProtocolException(message: String) : Exception(message)

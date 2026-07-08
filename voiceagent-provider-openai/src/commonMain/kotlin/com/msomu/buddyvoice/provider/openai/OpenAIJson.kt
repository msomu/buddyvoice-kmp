package com.msomu.buddyvoice.provider.openai

import kotlinx.serialization.json.Json

/**
 * Lenient JSON for the OpenAI realtime wire protocol: the schema shifted between
 * beta and GA (and keeps evolving), so unknown fields and event types must never
 * crash a session.
 */
internal val openAIJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/** The proxy or the OpenAI connection handshake failed. */
class OpenAIConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** OpenAI reported an error event over the realtime socket. */
class OpenAIProtocolException(message: String) : Exception(message)

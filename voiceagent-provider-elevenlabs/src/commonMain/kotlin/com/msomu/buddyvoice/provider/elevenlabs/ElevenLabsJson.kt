package com.msomu.buddyvoice.provider.elevenlabs

import kotlinx.serialization.json.Json

/**
 * Lenient JSON for the ElevenLabs Agents wire protocol: unknown fields and
 * event types must never crash a session.
 */
internal val elevenLabsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/** The proxy or the ElevenLabs connection handshake failed. */
class ElevenLabsConnectException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** ElevenLabs reported an error event over the conversation socket. */
class ElevenLabsProtocolException(message: String) : Exception(message)

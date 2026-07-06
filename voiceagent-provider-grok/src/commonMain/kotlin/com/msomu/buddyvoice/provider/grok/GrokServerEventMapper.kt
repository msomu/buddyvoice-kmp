package com.msomu.buddyvoice.provider.grok

import com.msomu.buddyvoice.voiceagent.core.AgentEvent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps one Grok server event onto provider-agnostic [AgentEvent]s.
 *
 * Pure function over the parsed JSON plus the per-turn cumulative assistant
 * transcript, which makes the whole wire mapping unit-testable without a socket.
 * Unknown event types are ignored by design — xAI's schema is evolving.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun mapServerEvent(event: JsonObject, assistantText: StringBuilder): List<AgentEvent> =
    when (event["type"]?.jsonPrimitive?.contentOrNull) {

        "response.created" -> {
            assistantText.clear()
            listOf(AgentEvent.TurnStarted)
        }

        // xAI carries the base64 audio in "delta" (like its transcript deltas);
        // "audio" is kept as a fallback for older captures of the schema.
        "response.output_audio.delta" ->
            (event["delta"] ?: event["audio"])?.jsonPrimitive?.contentOrNull
                ?.let { listOf(AgentEvent.AudioChunk(Base64.decode(it))) }
                ?: emptyList()

        // Agent-speech transcript, streamed as incremental deltas.
        "response.output_audio_transcript.delta", "response.text.delta" ->
            (event["delta"] ?: event["text"])?.jsonPrimitive?.contentOrNull
                ?.let {
                    assistantText.append(it)
                    listOf(AgentEvent.PartialTranscript(assistantText.toString(), isFinal = false))
                }
                ?: emptyList()

        "response.done" -> buildList {
            if (assistantText.isNotEmpty()) {
                add(AgentEvent.PartialTranscript(assistantText.toString(), isFinal = true))
            }
            add(AgentEvent.TurnEnded)
        }

        // xAI sends the user's ASR cumulatively (full text so far), matching the
        // PartialTranscript contract directly.
        "conversation.item.input_audio_transcription.updated" ->
            event["transcript"]?.jsonPrimitive?.contentOrNull
                ?.let { listOf(AgentEvent.PartialTranscript(it, isFinal = false)) }
                ?: emptyList()

        // Final user ASR, emitted once the input buffer is committed.
        "conversation.item.input_audio_transcription.completed" ->
            event["transcript"]?.jsonPrimitive?.contentOrNull
                ?.let { listOf(AgentEvent.PartialTranscript(it, isFinal = true)) }
                ?: emptyList()

        "error" -> listOf(AgentEvent.Error(GrokProtocolException(event.toString())))

        // session.updated, conversation.created, response.output_audio.done, ... — not surfaced.
        else -> emptyList()
    }

/**
 * Extracts the ephemeral token from the proxy's pass-through of xAI's
 * `POST /v1/realtime/client_secrets` response. Tolerates both the flat
 * `{"value": ...}` and nested `{"client_secret": {"value": ...}}` shapes.
 */
internal fun parseEphemeralToken(body: String): String {
    val root = runCatching { grokJson.parseToJsonElement(body).jsonObject }
        .getOrElse { throw GrokConnectException("Proxy returned non-JSON token response", it) }
    return root["value"]?.jsonPrimitive?.contentOrNull
        ?: root["client_secret"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
        ?: throw GrokConnectException("Unrecognized client_secrets response shape")
}

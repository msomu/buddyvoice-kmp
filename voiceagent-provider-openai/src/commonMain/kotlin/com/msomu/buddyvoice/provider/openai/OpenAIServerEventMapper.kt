package com.msomu.buddyvoice.provider.openai

import com.msomu.buddyvoice.voiceagent.core.AgentEvent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps one OpenAI realtime server event onto provider-agnostic [AgentEvent]s.
 *
 * Pure function over the parsed JSON plus the per-turn cumulative transcripts,
 * which makes the whole wire mapping unit-testable without a socket. The mapper
 * is deliberately defensive: OpenAI renamed several events between the beta
 * (`response.audio.delta`) and GA (`response.output_audio.delta`) schemas, so
 * both names and both payload field spellings are accepted. Unknown event types
 * are ignored by design.
 *
 * Audio in [AgentEvent.AudioChunk] is the raw wire audio (24 kHz); the session
 * resamples it to the 16 kHz boundary format before emitting it to the app.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun mapServerEvent(
    event: JsonObject,
    assistantText: StringBuilder,
    userText: StringBuilder,
): List<AgentEvent> =
    when (event["type"]?.jsonPrimitive?.contentOrNull) {

        "response.created" -> {
            assistantText.clear()
            listOf(AgentEvent.TurnStarted)
        }

        // GA name + beta name; payload documented as "delta", "audio" tolerated.
        "response.output_audio.delta", "response.audio.delta" ->
            (event["delta"] ?: event["audio"])?.jsonPrimitive?.contentOrNull
                ?.let { listOf(AgentEvent.AudioChunk(Base64.decode(it))) }
                ?: emptyList()

        // Agent-speech transcript, streamed as incremental deltas (GA + beta names).
        "response.output_audio_transcript.delta", "response.audio_transcript.delta",
        "response.output_text.delta", "response.text.delta",
        ->
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

        // User ASR streams as incremental deltas (unlike xAI's cumulative updates),
        // so accumulate locally to honor the cumulative PartialTranscript contract.
        "conversation.item.input_audio_transcription.delta" ->
            (event["delta"] ?: event["transcript"])?.jsonPrimitive?.contentOrNull
                ?.let {
                    userText.append(it)
                    listOf(AgentEvent.PartialTranscript(userText.toString(), isFinal = false))
                }
                ?: emptyList()

        // Final user ASR carries the full utterance; fall back to the accumulated
        // deltas if the field is ever missing.
        "conversation.item.input_audio_transcription.completed" -> {
            val text = event["transcript"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: userText.toString()
            userText.clear()
            if (text.isBlank()) emptyList()
            else listOf(AgentEvent.PartialTranscript(text.trim(), isFinal = true))
        }

        "error" -> listOf(AgentEvent.Error(OpenAIProtocolException(event.toString())))

        // session.created, session.updated, rate_limits.updated, response.output_item.*,
        // input_audio_buffer.speech_* ... — not surfaced.
        else -> emptyList()
    }

/**
 * Extracts the ephemeral token from the proxy's pass-through of OpenAI's
 * `POST /v1/realtime/client_secrets` response. Tolerates both the GA flat
 * `{"value": ...}` and the older nested `{"client_secret": {"value": ...}}` shapes.
 */
internal fun parseEphemeralToken(body: String): String {
    val root = runCatching { openAIJson.parseToJsonElement(body).jsonObject }
        .getOrElse { throw OpenAIConnectException("Proxy returned non-JSON token response", it) }
    return root["value"]?.jsonPrimitive?.contentOrNull
        ?: root["client_secret"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
        ?: throw OpenAIConnectException("Unrecognized client_secrets response shape")
}

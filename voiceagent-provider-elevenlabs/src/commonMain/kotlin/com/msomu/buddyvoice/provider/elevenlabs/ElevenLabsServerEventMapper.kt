package com.msomu.buddyvoice.provider.elevenlabs

import com.msomu.buddyvoice.voiceagent.core.AgentEvent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Mutable per-session turn tracking. ElevenLabs has no explicit turn-start
 * event, so the first agent signal of a turn (`agent_response` or `audio`)
 * synthesizes [AgentEvent.TurnStarted], and completion/interruption events end
 * it exactly once.
 */
internal class ElevenLabsTurnState {
    var agentTurnActive: Boolean = false
}

/**
 * Maps one ElevenLabs Agents server event onto provider-agnostic [AgentEvent]s.
 *
 * Pure function over the parsed JSON plus [ElevenLabsTurnState], which makes the
 * whole wire mapping unit-testable without a socket. Unlike Grok/OpenAI,
 * transcripts arrive as complete utterances (not streamed deltas), so both
 * user and agent text map to `isFinal = true` lines directly. Unknown event
 * types are ignored by design.
 *
 * Keepalive `ping` events are answered by the session via [pongFor], not mapped.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun mapServerEvent(event: JsonObject, turn: ElevenLabsTurnState): List<AgentEvent> =
    when (event["type"]?.jsonPrimitive?.contentOrNull) {

        // Handshake echo: verify the agent speaks our boundary format. A mismatch
        // is surfaced loudly instead of playing chipmunk/slow-motion audio.
        "conversation_initiation_metadata" -> {
            val meta = event["conversation_initiation_metadata_event"]?.jsonObject
            val outFormat = meta?.get("agent_output_audio_format")?.jsonPrimitive?.contentOrNull
            val inFormat = meta?.get("user_input_audio_format")?.jsonPrimitive?.contentOrNull
            val mismatched = listOfNotNull(outFormat, inFormat).filter { it != REQUIRED_AUDIO_FORMAT }
            if (mismatched.isEmpty()) emptyList()
            else listOf(
                AgentEvent.Error(
                    ElevenLabsProtocolException(
                        "Agent audio format is ${mismatched.joinToString()} but BuddyVoice requires " +
                            "$REQUIRED_AUDIO_FORMAT. Set input and output format to " +
                            "\"PCM 16000 Hz\" in the agent's Voice settings on ElevenLabs.",
                    ),
                ),
            )
        }

        "audio" ->
            event["audio_event"]?.jsonObject?.let { audioEvent ->
                buildList {
                    if (!turn.agentTurnActive) {
                        turn.agentTurnActive = true
                        add(AgentEvent.TurnStarted)
                    }
                    audioEvent["audio_base_64"]?.jsonPrimitive?.contentOrNull
                        ?.let { add(AgentEvent.AudioChunk(Base64.decode(it))) }
                    if (audioEvent["is_final"]?.jsonPrimitive?.booleanOrNull == true) {
                        turn.agentTurnActive = false
                        add(AgentEvent.TurnEnded)
                    }
                }
            } ?: emptyList()

        // Full agent reply text, delivered up front (not streamed).
        "agent_response" ->
            event["agent_response_event"]?.jsonObject
                ?.get("agent_response")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    buildList {
                        if (!turn.agentTurnActive) {
                            turn.agentTurnActive = true
                            add(AgentEvent.TurnStarted)
                        }
                        add(AgentEvent.PartialTranscript(text.trim(), isFinal = true))
                    }
                }
                ?: emptyList()

        // Replaces the agent text after a barge-in truncated the spoken reply.
        "agent_response_correction" ->
            event["agent_response_correction_event"]?.jsonObject
                ?.get("corrected_agent_response")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(AgentEvent.PartialTranscript(it.trim(), isFinal = true)) }
                ?: emptyList()

        "agent_response_complete" ->
            if (turn.agentTurnActive) {
                turn.agentTurnActive = false
                listOf(AgentEvent.TurnEnded)
            } else {
                emptyList()
            }

        // Server-side VAD detected the user talking over the agent.
        "interruption" ->
            if (turn.agentTurnActive) {
                turn.agentTurnActive = false
                listOf(AgentEvent.TurnEnded)
            } else {
                emptyList()
            }

        // Complete user utterance from ASR.
        "user_transcript" ->
            event["user_transcription_event"]?.jsonObject
                ?.get("user_transcript")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(AgentEvent.PartialTranscript(it.trim(), isFinal = true)) }
                ?: emptyList()

        "client_error", "error" ->
            listOf(AgentEvent.Error(ElevenLabsProtocolException(event.toString())))

        // ping (answered separately), vad_score, agent_response_metadata,
        // client_tool_call, mcp_* ... — not surfaced.
        else -> emptyList()
    }

/**
 * If [event] is a keepalive `ping`, returns the serialized `pong` reply the
 * session must send; otherwise null. Kept beside the mapper so the protocol's
 * request/response pairs are testable together.
 */
internal fun pongFor(event: JsonObject): String? {
    if (event["type"]?.jsonPrimitive?.contentOrNull != "ping") return null
    val eventId = event["ping_event"]?.jsonObject?.get("event_id")?.jsonPrimitive?.longOrNull
        ?: return null
    return elevenLabsJson.encodeToString(Pong.serializer(), Pong(eventId = eventId))
}

/**
 * Extracts the WebSocket URL from the proxy's pass-through of ElevenLabs'
 * `GET /v1/convai/conversation/get-signed-url` response.
 */
internal fun parseSignedUrl(body: String): String {
    val root = runCatching { elevenLabsJson.parseToJsonElement(body).jsonObject }
        .getOrElse { throw ElevenLabsConnectException("Proxy returned non-JSON signed-url response", it) }
    return root["signed_url"]?.jsonPrimitive?.contentOrNull
        ?: throw ElevenLabsConnectException("Unrecognized signed-url response shape")
}

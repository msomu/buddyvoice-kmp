package com.msomu.buddyvoice.provider.elevenlabs

import com.msomu.buddyvoice.voiceagent.core.AgentEvent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalEncodingApi::class)
class ElevenLabsServerEventMapperTest {

    private fun map(raw: String, turn: ElevenLabsTurnState = ElevenLabsTurnState()): List<AgentEvent> =
        mapServerEvent(elevenLabsJson.parseToJsonElement(raw).jsonObject, turn)

    @Test
    fun metadataWithMatchingFormatIsSilent() {
        val events = map(
            """{"type":"conversation_initiation_metadata","conversation_initiation_metadata_event":
               {"conversation_id":"c1","agent_output_audio_format":"pcm_16000","user_input_audio_format":"pcm_16000"}}""",
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun metadataWithWrongFormatSurfacesActionableError() {
        val events = map(
            """{"type":"conversation_initiation_metadata","conversation_initiation_metadata_event":
               {"conversation_id":"c1","agent_output_audio_format":"pcm_44100","user_input_audio_format":"pcm_16000"}}""",
        )
        val error = assertIs<AgentEvent.Error>(events.single())
        assertTrue(error.cause.message!!.contains("pcm_44100"))
        assertTrue(error.cause.message!!.contains("PCM 16000"))
    }

    @Test
    fun firstAudioStartsTurnAndDecodesBase64() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val turn = ElevenLabsTurnState()

        val events = map(
            """{"type":"audio","audio_event":{"audio_base_64":"${Base64.encode(pcm)}","event_id":1}}""",
            turn,
        )

        assertEquals(AgentEvent.TurnStarted, events[0])
        assertContentEquals(pcm, assertIs<AgentEvent.AudioChunk>(events[1]).data)
        assertTrue(turn.agentTurnActive)
    }

    @Test
    fun subsequentAudioDoesNotRestartTurn() {
        val turn = ElevenLabsTurnState().apply { agentTurnActive = true }

        val events = map(
            """{"type":"audio","audio_event":{"audio_base_64":"${Base64.encode(byteArrayOf(9))}","event_id":2}}""",
            turn,
        )

        assertEquals(1, events.size)
        assertIs<AgentEvent.AudioChunk>(events.single())
    }

    @Test
    fun finalAudioEndsTurn() {
        val turn = ElevenLabsTurnState().apply { agentTurnActive = true }

        val events = map(
            """{"type":"audio","audio_event":{"audio_base_64":"${Base64.encode(byteArrayOf(7))}","event_id":3,"is_final":true}}""",
            turn,
        )

        assertIs<AgentEvent.AudioChunk>(events[0])
        assertEquals(AgentEvent.TurnEnded, events[1])
        assertTrue(!turn.agentTurnActive)
    }

    @Test
    fun agentResponseStartsTurnWithFinalTranscript() {
        val turn = ElevenLabsTurnState()

        val events = map(
            """{"type":"agent_response","agent_response_event":{"agent_response":"Hello there!","event_id":4}}""",
            turn,
        )

        assertEquals(AgentEvent.TurnStarted, events[0])
        assertEquals(AgentEvent.PartialTranscript("Hello there!", isFinal = true), events[1])
        assertTrue(turn.agentTurnActive)
    }

    @Test
    fun agentResponseCorrectionReplacesText() {
        val events = map(
            """{"type":"agent_response_correction","agent_response_correction_event":
               {"original_agent_response":"Hello there, how can...","corrected_agent_response":"Hello there,","event_id":5}}""",
        )
        assertEquals(
            listOf(AgentEvent.PartialTranscript("Hello there,", isFinal = true)),
            events,
        )
    }

    @Test
    fun completionAndInterruptionEndTurnExactlyOnce() {
        val turn = ElevenLabsTurnState().apply { agentTurnActive = true }

        assertEquals(
            listOf(AgentEvent.TurnEnded),
            map("""{"type":"agent_response_complete","agent_response_complete_event":{"event_id":6}}""", turn),
        )
        // Turn already ended: neither event should emit a duplicate TurnEnded.
        assertTrue(map("""{"type":"agent_response_complete","agent_response_complete_event":{"event_id":7}}""", turn).isEmpty())
        assertTrue(map("""{"type":"interruption","interruption_event":{"event_id":8}}""", turn).isEmpty())

        turn.agentTurnActive = true
        assertEquals(
            listOf(AgentEvent.TurnEnded),
            map("""{"type":"interruption","interruption_event":{"event_id":9}}""", turn),
        )
    }

    @Test
    fun userTranscriptIsFinalUtterance() {
        val events = map(
            """{"type":"user_transcript","user_transcription_event":{"user_transcript":"What is the weather?","event_id":10}}""",
        )
        assertEquals(
            listOf(AgentEvent.PartialTranscript("What is the weather?", isFinal = true)),
            events,
        )
    }

    @Test
    fun clientErrorSurfacesAsAgentError() {
        val events = map(
            """{"type":"client_error","error_event":{"code":400,"message":"agent not found"}}""",
        )
        val error = assertIs<AgentEvent.Error>(events.single())
        assertIs<ElevenLabsProtocolException>(error.cause)
        assertTrue(error.cause.message!!.contains("agent not found"))
    }

    @Test
    fun unknownEventsAreIgnored() {
        assertTrue(map("""{"type":"vad_score","vad_score_event":{"vad_score":0.9}}""").isEmpty())
        assertTrue(map("""{"type":"agent_response_metadata","agent_response_metadata_event":{"event_id":1}}""").isEmpty())
        assertTrue(map("""{"no_type":true}""").isEmpty())
        // Malformed audio event with no payload must neither crash nor start a turn.
        assertTrue(map("""{"type":"audio"}""").isEmpty())
    }

    @Test
    fun pingProducesPongWithMatchingEventId() {
        val pong = pongFor(
            elevenLabsJson.parseToJsonElement(
                """{"type":"ping","ping_event":{"event_id":42,"ping_ms":500}}""",
            ).jsonObject,
        )
        assertEquals("""{"type":"pong","event_id":42}""", pong)
    }

    @Test
    fun nonPingProducesNoPong() {
        assertNull(pongFor(elevenLabsJson.parseToJsonElement("""{"type":"audio"}""").jsonObject))
        assertNull(pongFor(elevenLabsJson.parseToJsonElement("""{"type":"ping"}""").jsonObject))
    }
}

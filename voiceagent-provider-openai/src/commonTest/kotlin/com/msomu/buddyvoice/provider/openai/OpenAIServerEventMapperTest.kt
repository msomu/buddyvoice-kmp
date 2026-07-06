package com.msomu.buddyvoice.provider.openai

import com.msomu.buddyvoice.voiceagent.core.AgentEvent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalEncodingApi::class)
class OpenAIServerEventMapperTest {

    private fun map(
        raw: String,
        assistant: StringBuilder = StringBuilder(),
        user: StringBuilder = StringBuilder(),
    ): List<AgentEvent> =
        mapServerEvent(openAIJson.parseToJsonElement(raw).jsonObject, assistant, user)

    @Test
    fun responseCreatedStartsTurnAndResetsTranscript() {
        val assistant = StringBuilder("left over from last turn")

        val events = map("""{"type":"response.created","response":{"id":"r1"}}""", assistant)

        assertEquals(listOf(AgentEvent.TurnStarted), events)
        assertTrue(assistant.isEmpty())
    }

    @Test
    fun gaAudioDeltaDecodesBase64FromDeltaField() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val raw = """{"type":"response.output_audio.delta","delta":"${Base64.encode(pcm)}","item_id":"i1"}"""

        val events = map(raw)

        val chunk = assertIs<AgentEvent.AudioChunk>(events.single())
        assertContentEquals(pcm, chunk.data)
    }

    @Test
    fun betaAudioDeltaEventNameIsAccepted() {
        // Beta schema used response.audio.delta; keep accepting it.
        val pcm = byteArrayOf(9, 8, 7, 6)
        val raw = """{"type":"response.audio.delta","delta":"${Base64.encode(pcm)}"}"""

        val events = map(raw)

        val chunk = assertIs<AgentEvent.AudioChunk>(events.single())
        assertContentEquals(pcm, chunk.data)
    }

    @Test
    fun audioDeltaToleratesAudioFieldSpelling() {
        // Phase 1 lesson: docs lie about field names. Accept "audio" as a fallback.
        val pcm = byteArrayOf(4, 4, 4, 4)
        val raw = """{"type":"response.output_audio.delta","audio":"${Base64.encode(pcm)}"}"""

        val events = map(raw)

        val chunk = assertIs<AgentEvent.AudioChunk>(events.single())
        assertContentEquals(pcm, chunk.data)
    }

    @Test
    fun gaOutputAudioTranscriptDeltasAccumulateAcrossEvents() {
        val assistant = StringBuilder()

        val first = map(
            """{"type":"response.output_audio_transcript.delta","delta":"Sure,"}""",
            assistant,
        )
        val second = map(
            """{"type":"response.output_audio_transcript.delta","delta":" here you go"}""",
            assistant,
        )

        assertEquals(listOf(AgentEvent.PartialTranscript("Sure,", isFinal = false)), first)
        assertEquals(
            listOf(AgentEvent.PartialTranscript("Sure, here you go", isFinal = false)),
            second,
        )
    }

    @Test
    fun betaAudioTranscriptDeltaEventNameIsAccepted() {
        val events = map("""{"type":"response.audio_transcript.delta","delta":"Hello"}""")

        assertEquals(listOf(AgentEvent.PartialTranscript("Hello", isFinal = false)), events)
    }

    @Test
    fun textDeltaVariantsAccumulateIntoAssistantTranscript() {
        val assistant = StringBuilder()

        val first = map("""{"type":"response.output_text.delta","delta":"Hi"}""", assistant)
        val second = map("""{"type":"response.text.delta","text":" there"}""", assistant)

        assertEquals(listOf(AgentEvent.PartialTranscript("Hi", isFinal = false)), first)
        assertEquals(listOf(AgentEvent.PartialTranscript("Hi there", isFinal = false)), second)
    }

    @Test
    fun responseDoneEmitsFinalTranscriptThenTurnEnded() {
        val assistant = StringBuilder("Hi there")

        val events = map("""{"type":"response.done","response":{"status":"completed"}}""", assistant)

        assertEquals(
            listOf(
                AgentEvent.PartialTranscript("Hi there", isFinal = true),
                AgentEvent.TurnEnded,
            ),
            events,
        )
    }

    @Test
    fun responseDoneWithoutTextOnlyEndsTurn() {
        val events = map("""{"type":"response.done"}""")

        assertEquals(listOf(AgentEvent.TurnEnded), events)
    }

    @Test
    fun userTranscriptionDeltasAccumulateCumulatively() {
        // OpenAI streams user ASR as increments; the PartialTranscript contract is
        // cumulative text, so the mapper must accumulate.
        val user = StringBuilder()

        val first = map(
            """{"type":"conversation.item.input_audio_transcription.delta","delta":"What is"}""",
            user = user,
        )
        val second = map(
            """{"type":"conversation.item.input_audio_transcription.delta","delta":" the time?"}""",
            user = user,
        )

        assertEquals(listOf(AgentEvent.PartialTranscript("What is", isFinal = false)), first)
        assertEquals(
            listOf(AgentEvent.PartialTranscript("What is the time?", isFinal = false)),
            second,
        )
    }

    @Test
    fun userTranscriptionCompletedIsFinalAndResetsAccumulator() {
        val user = StringBuilder("What is the ti")

        val events = map(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"What is the time?\n","item_id":"i2"}""",
            user = user,
        )

        assertEquals(listOf(AgentEvent.PartialTranscript("What is the time?", isFinal = true)), events)
        assertTrue(user.isEmpty())
    }

    @Test
    fun userTranscriptionCompletedFallsBackToAccumulatedDeltas() {
        val user = StringBuilder("Accumulated text")

        val events = map(
            """{"type":"conversation.item.input_audio_transcription.completed"}""",
            user = user,
        )

        assertEquals(listOf(AgentEvent.PartialTranscript("Accumulated text", isFinal = true)), events)
        assertTrue(user.isEmpty())
    }

    @Test
    fun errorEventSurfacesAsAgentError() {
        val events = map("""{"type":"error","error":{"type":"invalid_request_error","message":"boom"}}""")

        val error = assertIs<AgentEvent.Error>(events.single())
        assertIs<OpenAIProtocolException>(error.cause)
        assertTrue(error.cause.message!!.contains("boom"))
    }

    @Test
    fun unknownAndMalformedEventsAreIgnored() {
        assertTrue(map("""{"type":"session.created","session":{}}""").isEmpty())
        assertTrue(map("""{"type":"session.updated","session":{}}""").isEmpty())
        assertTrue(map("""{"type":"rate_limits.updated","rate_limits":[]}""").isEmpty())
        assertTrue(map("""{"type":"input_audio_buffer.speech_started"}""").isEmpty())
        assertTrue(map("""{"type":"response.output_item.added","item":{}}""").isEmpty())
        assertTrue(map("""{"no_type_at_all":true}""").isEmpty())
        assertTrue(map("""{"type":"response.output_audio.delta"}""").isEmpty()) // missing audio field
    }
}

package com.msomu.buddyvoice.provider.grok

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
class GrokServerEventMapperTest {

    private fun map(raw: String, text: StringBuilder = StringBuilder()): List<AgentEvent> =
        mapServerEvent(grokJson.parseToJsonElement(raw).jsonObject, text)

    @Test
    fun responseCreatedStartsTurnAndResetsTranscript() {
        val text = StringBuilder("left over from last turn")

        val events = map("""{"type":"response.created","response":{"id":"r1"}}""", text)

        assertEquals(listOf(AgentEvent.TurnStarted), events)
        assertTrue(text.isEmpty())
    }

    @Test
    fun audioDeltaDecodesBase64() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5)
        val raw = """{"type":"response.output_audio.delta","audio":"${Base64.encode(pcm)}"}"""

        val events = map(raw)

        val chunk = assertIs<AgentEvent.AudioChunk>(events.single())
        assertContentEquals(pcm, chunk.data)
    }

    @Test
    fun textDeltasAccumulateAcrossEvents() {
        val text = StringBuilder()

        val first = map("""{"type":"response.text.delta","text":"Hello"}""", text)
        val second = map("""{"type":"response.text.delta","text":", world"}""", text)

        assertEquals(listOf(AgentEvent.PartialTranscript("Hello", isFinal = false)), first)
        assertEquals(listOf(AgentEvent.PartialTranscript("Hello, world", isFinal = false)), second)
    }

    @Test
    fun responseDoneEmitsFinalTranscriptThenTurnEnded() {
        val text = StringBuilder("Hello, world")

        val events = map("""{"type":"response.done","response":{"status":"completed"}}""", text)

        assertEquals(
            listOf(
                AgentEvent.PartialTranscript("Hello, world", isFinal = true),
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
    fun userTranscriptionIsCumulativePartial() {
        val events =
            map("""{"type":"conversation.item.input_audio_transcription.updated","transcript":"How is the"}""")

        assertEquals(listOf(AgentEvent.PartialTranscript("How is the", isFinal = false)), events)
    }

    @Test
    fun errorEventSurfacesAsAgentError() {
        val events = map("""{"type":"error","error":{"message":"boom"}}""")

        val error = assertIs<AgentEvent.Error>(events.single())
        assertIs<GrokProtocolException>(error.cause)
        assertTrue(error.cause.message!!.contains("boom"))
    }

    @Test
    fun unknownAndMalformedEventsAreIgnored() {
        assertTrue(map("""{"type":"session.updated","session":{}}""").isEmpty())
        assertTrue(map("""{"type":"conversation.created","conversation":{"id":"c1"}}""").isEmpty())
        assertTrue(map("""{"no_type_at_all":true}""").isEmpty())
        assertTrue(map("""{"type":"response.output_audio.delta"}""").isEmpty()) // missing audio field
    }
}

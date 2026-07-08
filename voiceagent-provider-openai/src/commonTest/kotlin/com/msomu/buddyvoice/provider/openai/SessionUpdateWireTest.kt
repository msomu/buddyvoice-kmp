package com.msomu.buddyvoice.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pins the GA `session.update` wire shape so schema drift is caught by tests, not live runs. */
class SessionUpdateWireTest {

    private fun encode(config: SessionConfig): String =
        openAIJson.encodeToString(SessionUpdate.serializer(), SessionUpdate(session = config))

    @Test
    fun sessionUpdateMatchesGaShape() {
        val raw = encode(
            SessionConfig(
                instructions = "Be brief.",
                audio = AudioConfig(
                    input = AudioInput(transcription = TranscriptionConfig("gpt-4o-mini-transcribe")),
                    output = AudioOutput(voice = "marin"),
                ),
            ),
        )

        val root = openAIJson.parseToJsonElement(raw).jsonObject
        assertEquals("session.update", root["type"]?.jsonPrimitive?.content)

        val session = root["session"]!!.jsonObject
        assertEquals("realtime", session["type"]?.jsonPrimitive?.content)
        assertEquals("Be brief.", session["instructions"]?.jsonPrimitive?.content)

        val input = session["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("audio/pcm", input["format"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(24_000, input["format"]!!.jsonObject["rate"]?.jsonPrimitive?.int)
        assertEquals("server_vad", input["turn_detection"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "gpt-4o-mini-transcribe",
            input["transcription"]!!.jsonObject["model"]?.jsonPrimitive?.content,
        )

        val output = session["audio"]!!.jsonObject["output"]!!.jsonObject
        assertEquals(24_000, output["format"]!!.jsonObject["rate"]?.jsonPrimitive?.int)
        assertEquals("marin", output["voice"]?.jsonPrimitive?.content)
    }

    @Test
    fun nullVoiceIsOmittedFromTheWire() {
        val raw = encode(SessionConfig(instructions = "x"))

        assertFalse(raw.contains("\"voice\""), "null voice must not be serialized: $raw")
        assertTrue(raw.contains("\"turn_detection\""))
    }

    @Test
    fun inputAudioAppendCarriesBase64Audio() {
        val raw = openAIJson.encodeToString(InputAudioAppend.serializer(), InputAudioAppend(audio = "AAEC"))
        val root = openAIJson.parseToJsonElement(raw).jsonObject

        assertEquals("input_audio_buffer.append", root["type"]?.jsonPrimitive?.content)
        assertEquals("AAEC", root["audio"]?.jsonPrimitive?.content)
    }

    @Test
    fun responseCancelHasOnlyAType() {
        val raw = openAIJson.encodeToString(ResponseCancel.serializer(), ResponseCancel())
        assertEquals("""{"type":"response.cancel"}""", raw)
    }
}

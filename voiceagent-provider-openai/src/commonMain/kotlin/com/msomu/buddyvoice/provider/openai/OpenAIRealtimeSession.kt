package com.msomu.buddyvoice.provider.openai

import com.msomu.buddyvoice.voiceagent.core.AgentEvent
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentSession
import com.msomu.buddyvoice.voiceagent.transport.WebSocketConnection
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalEncodingApi::class)
internal class OpenAIRealtimeSession(
    private val ws: WebSocketConnection,
    private val config: VoiceAgentConfig,
) : VoiceAgentSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<AgentEvent>(Channel.BUFFERED)
    private val assistantText = StringBuilder()
    private val userText = StringBuilder()
    private var audioChunksSent = 0

    /** Wire diagnostics on stdout (logcat tag `System.out`). Payloads are truncated; never logs credentials. */
    private fun logWire(direction: String, message: String) {
        println("BuddyVoice/Wire $direction $message")
    }

    override val events: Flow<AgentEvent> = channel.receiveAsFlow()

    /** Configures the OpenAI realtime session and starts pumping server events. */
    fun start() {
        scope.launch {
            try {
                val sessionUpdate = openAIJson.encodeToString(
                    SessionUpdate.serializer(),
                    SessionUpdate(
                        session = SessionConfig(
                            instructions = config.systemPrompt,
                            audio = AudioConfig(
                                input = AudioInput(
                                    transcription = TranscriptionConfig(
                                        model = config.extra["transcriptionModel"]
                                            ?: DEFAULT_TRANSCRIPTION_MODEL,
                                    ),
                                ),
                                output = AudioOutput(voice = config.voice),
                            ),
                        ),
                    ),
                )
                logWire(">>", sessionUpdate)
                ws.send(sessionUpdate)
                ws.incoming.collect { raw ->
                    val event = runCatching { openAIJson.parseToJsonElement(raw).jsonObject }
                        .getOrNull() ?: return@collect // non-JSON frames are dropped
                    val type = event["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                    logWire(
                        "<<",
                        if (type.endsWith("audio.delta")) "$type (${raw.length} chars)"
                        else raw.take(500),
                    )
                    mapServerEvent(event, assistantText, userText)
                        .forEach { channel.send(it.atBoundaryRate()) }
                }
                logWire("--", "incoming flow completed")
                channel.close()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logWire("--", "receive loop failed: $t")
                channel.trySend(AgentEvent.Error(t))
                channel.close()
            }
        }
    }

    /** OpenAI emits 24 kHz PCM; the app-facing contract is 16 kHz, so resample here. */
    private fun AgentEvent.atBoundaryRate(): AgentEvent =
        if (this is AgentEvent.AudioChunk) {
            AgentEvent.AudioChunk(resamplePcm16(data, OPENAI_WIRE_SAMPLE_RATE, BOUNDARY_SAMPLE_RATE))
        } else {
            this
        }

    override suspend fun sendAudio(chunk: ByteArray) {
        audioChunksSent++
        if (audioChunksSent % 25 == 1) {
            logWire(">>", "input_audio_buffer.append #$audioChunksSent (${chunk.size} bytes)")
        }
        val wireChunk = resamplePcm16(chunk, BOUNDARY_SAMPLE_RATE, OPENAI_WIRE_SAMPLE_RATE)
        ws.send(
            openAIJson.encodeToString(
                InputAudioAppend.serializer(),
                InputAudioAppend(audio = Base64.encode(wireChunk)),
            ),
        )
    }

    override suspend fun interrupt() {
        // response.cancel stops the in-flight response server-side; the synthetic
        // TurnEnded lets the app flush local playback immediately without waiting
        // for the cancelled response.done to round-trip.
        logWire(">>", "response.cancel (interrupt)")
        ws.send(openAIJson.encodeToString(ResponseCancel.serializer(), ResponseCancel()))
        channel.trySend(AgentEvent.TurnEnded)
    }

    override suspend fun close() {
        runCatching { ws.close() }
        scope.cancel()
        channel.close()
    }

    private companion object {
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"
    }
}

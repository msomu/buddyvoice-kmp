package com.msomu.buddyvoice.provider.grok

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
internal class GrokVoiceAgentSession(
    private val ws: WebSocketConnection,
    private val config: VoiceAgentConfig,
) : VoiceAgentSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<AgentEvent>(Channel.BUFFERED)
    private val assistantText = StringBuilder()
    private var audioChunksSent = 0

    /** Wire diagnostics on stdout (logcat tag `System.out`). Payloads are truncated; never logs credentials. */
    private fun logWire(direction: String, message: String) {
        println("BuddyVoice/Wire $direction $message")
    }

    override val events: Flow<AgentEvent> = channel.receiveAsFlow()

    /** Configures the Grok session and starts pumping server events. */
    fun start() {
        scope.launch {
            try {
                val sessionUpdate = grokJson.encodeToString(
                    SessionUpdate.serializer(),
                    SessionUpdate(
                        session = SessionConfig(
                            instructions = config.systemPrompt,
                            voice = config.voice,
                        ),
                    ),
                )
                logWire(">>", sessionUpdate)
                ws.send(sessionUpdate)
                ws.incoming.collect { raw ->
                    val event = runCatching { grokJson.parseToJsonElement(raw).jsonObject }
                        .getOrNull() ?: return@collect // non-JSON frames are dropped
                    val type = event["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                    logWire(
                        "<<",
                        if (type == "response.output_audio.delta") "$type (${raw.length} chars)"
                        else raw.take(500),
                    )
                    mapServerEvent(event, assistantText).forEach { channel.send(it) }
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

    override suspend fun sendAudio(chunk: ByteArray) {
        audioChunksSent++
        if (audioChunksSent % 25 == 1) {
            logWire(">>", "input_audio_buffer.append #$audioChunksSent (${chunk.size} bytes)")
        }
        ws.send(
            grokJson.encodeToString(
                InputAudioAppend.serializer(),
                InputAudioAppend(audio = Base64.encode(chunk)),
            ),
        )
    }

    override suspend fun interrupt() {
        // xAI documents no response.cancel yet; clearing buffered input plus a
        // synthetic TurnEnded lets the app flush local playback immediately.
        // TODO(xai): switch to response.cancel if/when it is documented.
        logWire(">>", "input_audio_buffer.clear (interrupt)")
        ws.send(grokJson.encodeToString(InputAudioClear.serializer(), InputAudioClear()))
        channel.trySend(AgentEvent.TurnEnded)
    }

    override suspend fun close() {
        runCatching { ws.close() }
        scope.cancel()
        channel.close()
    }
}

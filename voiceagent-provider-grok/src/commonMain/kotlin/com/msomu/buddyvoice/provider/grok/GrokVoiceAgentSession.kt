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
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalEncodingApi::class)
internal class GrokVoiceAgentSession(
    private val ws: WebSocketConnection,
    private val config: VoiceAgentConfig,
) : VoiceAgentSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<AgentEvent>(Channel.BUFFERED)
    private val assistantText = StringBuilder()

    override val events: Flow<AgentEvent> = channel.receiveAsFlow()

    /** Configures the Grok session and starts pumping server events. */
    fun start() {
        scope.launch {
            try {
                ws.send(
                    grokJson.encodeToString(
                        SessionUpdate.serializer(),
                        SessionUpdate(
                            session = SessionConfig(
                                instructions = config.systemPrompt,
                                voice = config.voice,
                            ),
                        ),
                    ),
                )
                ws.incoming.collect { raw ->
                    val event = runCatching { grokJson.parseToJsonElement(raw).jsonObject }
                        .getOrNull() ?: return@collect // non-JSON frames are dropped
                    mapServerEvent(event, assistantText).forEach { channel.send(it) }
                }
                channel.close()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                channel.trySend(AgentEvent.Error(t))
                channel.close()
            }
        }
    }

    override suspend fun sendAudio(chunk: ByteArray) {
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
        ws.send(grokJson.encodeToString(InputAudioClear.serializer(), InputAudioClear()))
        channel.trySend(AgentEvent.TurnEnded)
    }

    override suspend fun close() {
        runCatching { ws.close() }
        scope.cancel()
        channel.close()
    }
}

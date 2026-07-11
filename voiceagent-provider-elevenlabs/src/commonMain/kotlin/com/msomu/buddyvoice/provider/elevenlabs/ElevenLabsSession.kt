package com.msomu.buddyvoice.provider.elevenlabs

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
internal class ElevenLabsSession(
    private val ws: WebSocketConnection,
    private val config: VoiceAgentConfig,
) : VoiceAgentSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<AgentEvent>(Channel.BUFFERED)
    private val turn = ElevenLabsTurnState()

    /** Wire diagnostics on stdout (logcat tag `System.out`). Payloads are truncated; never logs credentials. */
    private fun logWire(direction: String, message: String) {
        println("BuddyVoice/Wire $direction $message")
    }

    override val events: Flow<AgentEvent> = channel.receiveAsFlow()

    /** Sends the conversation initiation overrides and starts pumping server events. */
    fun start() {
        scope.launch {
            try {
                val initiation = elevenLabsJson.encodeToString(
                    ConversationInitiation.serializer(),
                    ConversationInitiation(
                        configOverride = ConfigOverride(
                            agent = AgentOverride(
                                prompt = PromptOverride(prompt = config.systemPrompt),
                                language = config.extra["language"],
                                firstMessage = config.extra["firstMessage"],
                            ),
                            tts = config.voice?.let { TtsOverride(voiceId = it) },
                        ),
                    ),
                )
                logWire(">>", initiation)
                ws.send(initiation)
                ws.incoming.collect { raw ->
                    val event = runCatching { elevenLabsJson.parseToJsonElement(raw).jsonObject }
                        .getOrNull() ?: return@collect // non-JSON frames are dropped
                    val type = event["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                    logWire("<<", if (type == "audio") "$type (${raw.length} chars)" else raw.take(500))
                    pongFor(event)?.let { pong ->
                        ws.send(pong)
                        return@collect
                    }
                    mapServerEvent(event, turn).forEach { channel.send(it) }
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
        ws.send(
            elevenLabsJson.encodeToString(
                UserAudioChunk.serializer(),
                UserAudioChunk(userAudioChunk = Base64.encode(chunk)),
            ),
        )
    }

    override suspend fun interrupt() {
        // The protocol has no client-initiated cancel: barge-in happens server-side
        // when its VAD hears the user over the agent. A synthetic TurnEnded lets the
        // app flush local playback immediately regardless.
        if (turn.agentTurnActive) {
            turn.agentTurnActive = false
            channel.trySend(AgentEvent.TurnEnded)
        }
    }

    override suspend fun close() {
        runCatching { ws.close() }
        scope.cancel()
        channel.close()
    }
}

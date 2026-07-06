package com.msomu.buddyvoice.voice

import com.msomu.buddyvoice.provider.elevenlabs.ElevenLabsVoiceAgentProvider
import com.msomu.buddyvoice.provider.grok.GrokVoiceAgentProvider
import com.msomu.buddyvoice.provider.openai.OpenAIRealtimeProvider
import com.msomu.buddyvoice.voiceagent.audio.AudioEngine
import com.msomu.buddyvoice.voiceagent.core.AgentEvent
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentProvider
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * iOS [VoiceAgentController]: wires the shared UI to [AudioEngine] and a
 * [VoiceAgentProvider], mirroring `AndroidVoiceAgentController`. It lives in
 * sharedUI's iosMain (not the Swift app) because the app layer on iOS cannot
 * host Kotlin the way androidApp does.
 */
class IosVoiceAgentController(
    private val config: VoiceAgentConfig,
    private val scope: CoroutineScope,
    private val providers: List<VoiceAgentProvider> = listOf(
        GrokVoiceAgentProvider(),
        OpenAIRealtimeProvider(),
        ElevenLabsVoiceAgentProvider(),
    ),
) : VoiceAgentController {

    init {
        require(providers.isNotEmpty()) { "at least one provider is required" }
    }

    private val audioEngine = AudioEngine()
    private val _state = MutableStateFlow(VoiceAgentUiState(selectedProviderId = providers.first().id))
    override val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    private var session: VoiceAgentSession? = null
    private var captureJob: Job? = null

    override val availableProviders: List<String> = providers.map { it.id }

    override fun selectProvider(id: String) {
        if (_state.value.connection != ConnectionState.Disconnected &&
            _state.value.connection != ConnectionState.Error
        ) {
            return // switching mid-session is not supported; disconnect first
        }
        if (providers.none { it.id == id }) return
        _state.update { it.copy(selectedProviderId = id) }
    }

    override fun connect() {
        if (_state.value.connection == ConnectionState.Connecting ||
            _state.value.connection == ConnectionState.Connected
        ) {
            return
        }
        _state.update { it.copy(connection = ConnectionState.Connecting, errorMessage = null) }
        val provider = providers.firstOrNull { it.id == _state.value.selectedProviderId }
            ?: providers.first()
        scope.launch {
            try {
                val opened = provider.connect(config)
                session = opened
                _state.update { it.copy(connection = ConnectionState.Connected) }
                // Open-mic UX: listen continuously and let the provider's server-side
                // VAD segment turns; no hold-to-talk gesture.
                startTalking()
                opened.events.collect(::onAgentEvent)
                // Events flow completed: the session is over.
                onDisconnected()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                println("BuddyVoice: connect/session failed: $t")
                session = null
                _state.update {
                    it.copy(
                        connection = ConnectionState.Error,
                        errorMessage = t.message ?: t.toString(),
                    )
                }
            }
        }
    }

    override fun startTalking() {
        val active = session ?: return
        if (captureJob != null) return
        _state.update { it.copy(userIsTalking = true) }
        captureJob = scope.launch {
            try {
                audioEngine.startCapture().collect { chunk ->
                    // Half-duplex gate: drop mic audio while the agent is speaking so
                    // it doesn't hear its own reply through the speaker.
                    if (!_state.value.agentIsTalking) active.sendAudio(chunk)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(errorMessage = t.message ?: t.toString()) }
            } finally {
                _state.update { it.copy(userIsTalking = false) }
            }
        }
    }

    override fun stopTalking() {
        captureJob?.cancel()
        captureJob = null
        _state.update { it.copy(userIsTalking = false) }
    }

    override fun interrupt() {
        audioEngine.stopPlayback()
        scope.launch { session?.interrupt() }
    }

    override fun disconnect() {
        stopTalking()
        audioEngine.stopPlayback()
        val closing = session
        session = null
        scope.launch { closing?.close() }
        onDisconnected()
    }

    private suspend fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AudioChunk -> audioEngine.play(event.data)

            AgentEvent.TurnStarted -> _state.update { it.copy(agentIsTalking = true) }

            AgentEvent.TurnEnded -> _state.update { it.copy(agentIsTalking = false) }

            is AgentEvent.PartialTranscript -> _state.update { current ->
                // The core event model carries no speaker; agent text arrives only
                // between TurnStarted/TurnEnded, user ASR outside of it.
                val speaker =
                    if (current.agentIsTalking) TranscriptLine.Speaker.Agent
                    else TranscriptLine.Speaker.User
                current.copy(transcript = current.transcript.upsert(speaker, event))
            }

            is AgentEvent.Error -> _state.update {
                it.copy(errorMessage = event.cause.message ?: event.cause.toString())
            }
        }
    }

    override fun clearConversation() {
        disconnect()
        _state.update { it.copy(transcript = emptyList(), errorMessage = null) }
    }

    private fun onDisconnected() {
        _state.update {
            it.copy(
                connection = ConnectionState.Disconnected,
                agentIsTalking = false,
                userIsTalking = false,
            )
        }
    }
}

/** Replaces the trailing non-final line for [speaker] (transcripts are cumulative) or appends. */
private fun List<TranscriptLine>.upsert(
    speaker: TranscriptLine.Speaker,
    event: AgentEvent.PartialTranscript,
): List<TranscriptLine> {
    val line = TranscriptLine(speaker, event.text, event.isFinal)
    val last = lastOrNull()
    return if (last != null && last.speaker == speaker && !last.isFinal) {
        dropLast(1) + line
    } else {
        this + line
    }
}

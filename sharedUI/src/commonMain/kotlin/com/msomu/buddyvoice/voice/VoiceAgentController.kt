package com.msomu.buddyvoice.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing contract for a voice agent conversation.
 *
 * Deliberately defined here (not in the voiceagent modules) so sharedUI stays free
 * of audio/provider dependencies: each platform app implements it by wiring
 * `AudioEngine` + a `VoiceAgentProvider`, and platforms without those pieces pass
 * `null` to keep compiling.
 */
interface VoiceAgentController {
    val state: StateFlow<VoiceAgentUiState>

    /** Connects to the agent via the configured proxy. */
    fun connect()

    /** Starts streaming mic audio (hold-to-talk press). */
    fun startTalking()

    /** Stops streaming mic audio (hold-to-talk release). */
    fun stopTalking()

    /** Barge-in: stop the agent's current reply and flush playback. */
    fun interrupt()

    fun disconnect()
}

enum class ConnectionState { Disconnected, Connecting, Connected, Error }

data class TranscriptLine(
    val speaker: Speaker,
    val text: String,
    val isFinal: Boolean,
) {
    enum class Speaker { User, Agent }
}

data class VoiceAgentUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val userIsTalking: Boolean = false,
    val agentIsTalking: Boolean = false,
    val transcript: List<TranscriptLine> = emptyList(),
    val errorMessage: String? = null,
)

package com.msomu.buddyvoice.voiceagent.core

/**
 * Provider-agnostic events emitted by a [VoiceAgentSession].
 *
 * Provider modules map their wire protocol onto these; app code never sees
 * provider-specific event shapes.
 */
sealed interface AgentEvent {

    /**
     * A transcript update, for either the user's speech or the agent's reply.
     *
     * [text] is cumulative for the current turn (each event carries the full text
     * so far, not a delta). [isFinal] is `true` once the turn's text is complete.
     */
    data class PartialTranscript(val text: String, val isFinal: Boolean) : AgentEvent

    /**
     * A chunk of agent speech, 16 kHz PCM16 little-endian mono — ready to feed to
     * `voiceagent-audio`'s `AudioEngine.play()`.
     */
    data class AudioChunk(val data: ByteArray) : AgentEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioChunk && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    /** The agent started a reply turn. */
    data object TurnStarted : AgentEvent

    /** The agent finished (or was interrupted in) its reply turn. */
    data object TurnEnded : AgentEvent

    /** Something went wrong; the session may or may not still be usable. */
    data class Error(val cause: Throwable) : AgentEvent
}

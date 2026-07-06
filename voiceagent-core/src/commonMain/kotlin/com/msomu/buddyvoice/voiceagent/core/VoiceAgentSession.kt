package com.msomu.buddyvoice.voiceagent.core

import kotlinx.coroutines.flow.Flow

/**
 * A live, bidirectional conversation with a voice agent.
 *
 * Obtained from [VoiceAgentProvider.connect]. Close it with [close] when done;
 * a closed session cannot be reused.
 */
interface VoiceAgentSession {

    /**
     * Events emitted by the agent: transcripts, audio to play, turn boundaries, errors.
     *
     * Single-collector: events are buffered until one collector attaches and are
     * consumed by it. The flow completes when the session ends.
     */
    val events: Flow<AgentEvent>

    /**
     * Streams one chunk of caller audio to the agent.
     *
     * [chunk] must be 16 kHz PCM16 little-endian mono — the format produced by
     * `voiceagent-audio`'s `AudioEngine.startCapture()`.
     */
    suspend fun sendAudio(chunk: ByteArray)

    /**
     * Barge-in: stop the agent's current reply.
     *
     * Discards provider-side buffered input and emits [AgentEvent.TurnEnded] so the
     * app can flush local playback.
     */
    suspend fun interrupt()

    /** Closes the connection and completes [events]. Idempotent. */
    suspend fun close()
}

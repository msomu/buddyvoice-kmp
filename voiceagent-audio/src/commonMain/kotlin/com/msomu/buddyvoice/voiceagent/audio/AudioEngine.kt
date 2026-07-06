package com.msomu.buddyvoice.voiceagent.audio

import kotlinx.coroutines.flow.Flow

/** Sample rate at the library boundary. Everything is 16 kHz PCM16 little-endian mono. */
const val SAMPLE_RATE: Int = 16_000

/** Capture chunk size: 40 ms of 16 kHz PCM16 mono. */
const val CHUNK_BYTES: Int = 1_280

/**
 * Microphone capture and speaker playback, normalized to 16 kHz PCM16
 * little-endian mono so provider modules never touch platform audio formats.
 *
 * Platform availability: Android (Phase 1), iOS (Phase 2). Desktop and Web
 * actuals arrive in later phases and currently throw [UnsupportedOperationException].
 *
 * The app owns runtime permissions: on Android, request `RECORD_AUDIO` before
 * collecting [startCapture] (the library manifest only declares the permission);
 * on iOS, declare `NSMicrophoneUsageDescription` and request record permission.
 */
expect class AudioEngine() {

    /**
     * Cold flow of microphone audio in ~40 ms ([CHUNK_BYTES]) chunks.
     * Collection starts the mic; cancelling the collector (or [stopCapture]) stops it.
     */
    fun startCapture(): Flow<ByteArray>

    /** Stops an active capture, completing the flow returned by [startCapture]. */
    fun stopCapture()

    /** Enqueues a chunk for playback. Suspends while the playback buffer is full. */
    suspend fun play(chunk: ByteArray)

    /** Drops all queued playback immediately (barge-in). Playback can resume with [play]. */
    fun stopPlayback()

    /** Releases platform audio resources. The engine is unusable afterwards. */
    fun release()
}

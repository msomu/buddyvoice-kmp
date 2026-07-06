package com.msomu.buddyvoice.voiceagent.audio

import kotlinx.coroutines.flow.Flow

// Phase 2 will implement this with AVAudioEngine.
actual class AudioEngine actual constructor() {

    actual fun startCapture(): Flow<ByteArray> = unsupported()

    actual fun stopCapture(): Unit = unsupported()

    actual suspend fun play(chunk: ByteArray): Unit = unsupported()

    actual fun stopPlayback(): Unit = unsupported()

    actual fun release(): Unit = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("AudioEngine is not implemented on iOS yet (planned for Phase 2)")
}

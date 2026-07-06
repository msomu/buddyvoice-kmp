package com.msomu.buddyvoice.voiceagent.audio

import kotlinx.coroutines.flow.Flow

// Phase 4 will implement this with javax.sound.sampled.
actual class AudioEngine actual constructor() {

    actual fun startCapture(): Flow<ByteArray> = unsupported()

    actual fun stopCapture(): Unit = unsupported()

    actual suspend fun play(chunk: ByteArray): Unit = unsupported()

    actual fun stopPlayback(): Unit = unsupported()

    actual fun release(): Unit = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("AudioEngine is not implemented on Desktop/JVM yet (planned for Phase 4)")
}

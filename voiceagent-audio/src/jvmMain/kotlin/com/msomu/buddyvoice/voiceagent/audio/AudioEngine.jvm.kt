package com.msomu.buddyvoice.voiceagent.audio

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual class AudioEngine actual constructor() {

    private val captureActive = AtomicBoolean(false)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackChannel = Channel<ByteArray>(capacity = PLAYBACK_QUEUE_CHUNKS)
    private var line: SourceDataLine? = null
    private var drainJob: Job? = null

    actual fun startCapture(): Flow<ByteArray> = callbackFlow {
        val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
        check(AudioSystem.isLineSupported(info)) {
            "No microphone line supports 16 kHz PCM16 mono — is an input device connected?"
        }
        val target = AudioSystem.getLine(info) as TargetDataLine
        target.open(FORMAT, CHUNK_BYTES * 8)
        target.start()
        captureActive.set(true)

        val reader = launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_BYTES)
            while (isActive && captureActive.get()) {
                val read = target.read(buffer, 0, buffer.size) // blocking read paces the loop
                if (read > 0) trySend(buffer.copyOf(read))
            }
            this@callbackFlow.close()
        }

        awaitClose {
            captureActive.set(false)
            reader.cancel()
            runCatching { target.stop() } // unblocks a pending read
            target.close()
        }
    }.flowOn(Dispatchers.IO)

    actual fun stopCapture() {
        captureActive.set(false)
    }

    actual suspend fun play(chunk: ByteArray) {
        ensureLine()
        playbackChannel.send(chunk)
    }

    actual fun stopPlayback() {
        val stale = playbackChannel
        playbackChannel = Channel(capacity = PLAYBACK_QUEUE_CHUNKS)
        stale.cancel()
        drainJob?.cancel()
        drainJob = null
        line?.let {
            runCatching {
                it.stop()
                it.flush()
            }
        }
    }

    actual fun release() {
        stopCapture()
        stopPlayback()
        playbackScope.cancel()
        line?.close()
        line = null
    }

    private fun ensureLine() {
        if (line == null) {
            val info = DataLine.Info(SourceDataLine::class.java, FORMAT)
            check(AudioSystem.isLineSupported(info)) {
                "No speaker line supports 16 kHz PCM16 mono — is an output device connected?"
            }
            line = (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(FORMAT, CHUNK_BYTES * 16)
            }
        }
        if (drainJob == null) {
            val channel = playbackChannel
            drainJob = playbackScope.launch {
                val l = line ?: return@launch
                l.start()
                for (chunk in channel) {
                    l.write(chunk, 0, chunk.size) // blocking write paces the drain
                }
            }
        }
    }

    private companion object {
        /** ~2.5 s of queued audio before play() applies backpressure. */
        const val PLAYBACK_QUEUE_CHUNKS = 64

        /** The library boundary format: 16 kHz PCM16 little-endian mono. */
        val FORMAT = AudioFormat(
            SAMPLE_RATE.toFloat(),
            /* sampleSizeInBits = */ 16,
            /* channels = */ 1,
            /* signed = */ true,
            /* bigEndian = */ false,
        )
    }
}

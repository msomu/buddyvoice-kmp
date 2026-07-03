package com.msomu.buddyvoice.voiceagent.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
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
    private var track: AudioTrack? = null
    private var drainJob: Job? = null

    // The app must hold RECORD_AUDIO before collecting; AudioRecord's constructor
    // is what the permission actually gates.
    @SuppressLint("MissingPermission")
    actual fun startCapture(): Flow<ByteArray> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, CHUNK_BYTES * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize — is the RECORD_AUDIO permission granted?"
        }
        record.startRecording()
        captureActive.set(true)

        val reader = launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_BYTES)
            while (isActive && captureActive.get()) {
                val read = record.read(buffer, 0, buffer.size) // blocking read paces the loop
                if (read > 0) trySend(buffer.copyOf(read))
            }
            this@callbackFlow.close()
        }

        awaitClose {
            captureActive.set(false)
            reader.cancel()
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    actual fun stopCapture() {
        captureActive.set(false)
    }

    actual suspend fun play(chunk: ByteArray) {
        ensureTrack()
        playbackChannel.send(chunk)
    }

    actual fun stopPlayback() {
        val stale = playbackChannel
        playbackChannel = Channel(capacity = PLAYBACK_QUEUE_CHUNKS)
        stale.cancel()
        drainJob?.cancel()
        drainJob = null
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
    }

    actual fun release() {
        stopCapture()
        stopPlayback()
        playbackScope.cancel()
        track?.release()
        track = null
    }

    private fun ensureTrack() {
        if (track == null) {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                maxOf(minBuffer * 4, CHUNK_BYTES * 8),
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }
        if (drainJob == null) {
            val channel = playbackChannel
            drainJob = playbackScope.launch {
                val t = track ?: return@launch
                t.play()
                for (chunk in channel) {
                    t.write(chunk, 0, chunk.size)
                }
            }
        }
    }

    private companion object {
        /** ~2.5 s of queued audio before play() applies backpressure. */
        const val PLAYBACK_QUEUE_CHUNKS = 64
    }
}

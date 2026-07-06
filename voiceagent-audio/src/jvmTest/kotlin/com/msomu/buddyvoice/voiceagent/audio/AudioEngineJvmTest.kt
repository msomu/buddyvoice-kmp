package com.msomu.buddyvoice.voiceagent.audio

import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Headless-safe smoke tests: nothing here requires a real microphone or speaker.
 * Hardware-touching paths are exercised only when the host actually has a
 * matching audio line (skipped gracefully on CI).
 */
class AudioEngineJvmTest {

    @Test
    fun `construction and idempotent teardown never touch hardware`() {
        val engine = AudioEngine()
        engine.stopCapture()
        engine.stopPlayback()
        engine.release()
        // release() is terminal but must stay safe to call again.
        engine.release()
    }

    @Test
    fun `startCapture is cold — creating the flow opens no line`() {
        val engine = AudioEngine()
        assertNotNull(engine.startCapture()) // not collected, so no TargetDataLine is opened
        engine.release()
    }

    @Test
    fun `play then stopPlayback drains cleanly when a speaker line exists`() {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val speakerAvailable =
            runCatching { AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format)) }
                .getOrDefault(false)
        if (!speakerAvailable) {
            println("Skipping playback smoke test: no 16 kHz PCM16 mono output line on this host")
            return
        }

        val engine = AudioEngine()
        try {
            runBlocking {
                repeat(3) { engine.play(ByteArray(CHUNK_BYTES)) } // 120 ms of silence
            }
            engine.stopPlayback()
        } finally {
            engine.release()
        }
    }

    /**
     * Barge-in regression: stopPlayback() cancels the stale queue, which resumes a
     * play() suspended in send() with a CancellationException unrelated to job
     * cancellation. play() must swallow it (dropping the flushed chunk), not let it
     * cancel the calling coroutine — that killed the session collector.
     */
    @Test
    fun `stopPlayback while play is suspended on a full queue does not cancel the caller`() {
        if (!speakerAvailable()) {
            println("Skipping barge-in flush test: no 16 kHz PCM16 mono output line on this host")
            return
        }

        val engine = AudioEngine()
        try {
            runBlocking {
                val sent = AtomicInteger(0)
                val sender = launch(Dispatchers.Default) {
                    while (isActive) {
                        engine.play(ByteArray(CHUNK_BYTES)) // silence
                        sent.incrementAndGet()
                    }
                }

                repeat(3) {
                    // Wait until the queue is saturated: once `sent` has advanced 64
                    // (the queue capacity) + a few paced chunks past the last flush,
                    // the sender only moves when the drain frees a slot every ~40 ms,
                    // i.e. it sits suspended in send() essentially all the time.
                    val saturated = sent.get() + 64 + 8
                    withTimeout(20_000) { while (sent.get() < saturated) delay(10) }

                    engine.stopPlayback() // resumes the suspended send with the stale queue's cancellation

                    // With the fix the sender drops the flushed chunk and keeps going on
                    // the fresh queue; without it the CancellationException cancels it.
                    val before = sent.get()
                    withTimeout(5_000) { while (sent.get() <= before && sender.isActive) delay(10) }
                    assertFalse(sender.isCancelled, "barge-in flush must not cancel the play() caller")
                }
                sender.cancelAndJoin()
            }
        } finally {
            engine.release()
        }
    }

    private fun speakerAvailable(): Boolean {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        return runCatching {
            AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
        }.getOrDefault(false)
    }
}

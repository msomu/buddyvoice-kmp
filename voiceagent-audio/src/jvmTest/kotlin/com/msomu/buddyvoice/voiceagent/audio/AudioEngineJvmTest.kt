package com.msomu.buddyvoice.voiceagent.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

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
}

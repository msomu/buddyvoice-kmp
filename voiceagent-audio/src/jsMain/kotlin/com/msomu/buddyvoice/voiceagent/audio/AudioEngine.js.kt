package com.msomu.buddyvoice.voiceagent.audio

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.khronos.webgl.Float32Array
import org.w3c.dom.events.Event
import org.w3c.dom.mediacapture.MediaStreamConstraints
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * Web Audio API [AudioEngine].
 *
 * Capture: `getUserMedia` feeds an `AudioWorklet` (or a `ScriptProcessorNode`
 * where worklets are unavailable) whose Float32 blocks at the hardware rate are
 * downsampled by [PcmChunker] to 16 kHz PCM16 LE mono chunks.
 *
 * Playback: chunks become 16 kHz `AudioBuffer`s scheduled back to back on a
 * shared `AudioContext` (the browser resamples to the hardware rate). [play]
 * suspends once ~2.5 s is queued, mirroring the Android engine's bounded queue,
 * and [stopPlayback] stops every scheduled source (barge-in flush).
 *
 * Autoplay policy: browsers keep an `AudioContext` suspended until a user
 * gesture. The engine calls `resume()` on every capture/play entry point and
 * additionally resumes on the first click/touch/key gesture after creation.
 */
actual class AudioEngine actual constructor() {

    private var context: AudioContext? = null
    private var captureClose: (() -> Unit)? = null
    private val scheduled = mutableListOf<AudioBufferSourceNode>()
    private var nextStartTime = 0.0
    private var released = false

    actual fun startCapture(): Flow<ByteArray> = callbackFlow {
        val ctx = ensureContext()
        ctx.resume()
        val stream = window.navigator.mediaDevices
            .getUserMedia(MediaStreamConstraints(audio = CAPTURE_CONSTRAINTS()))
            .await()
        // The mic is live from here on: every exit path below — including setup
        // failures before awaitClose registers — must run teardown(), or the
        // browser keeps the mic hot for the page lifetime with no engine API
        // able to release it.
        var source: AudioNode? = null
        var sink: GainNode? = null
        var workletNode: AudioWorkletNode? = null
        var scriptNode: ScriptProcessorNode? = null

        fun teardown() {
            workletNode?.let {
                it.port.onmessage = null
                runCatching { it.disconnect() }
            }
            scriptNode?.let {
                it.onaudioprocess = null
                runCatching { it.disconnect() }
            }
            source?.let { runCatching { it.disconnect() } }
            sink?.let { runCatching { it.disconnect() } }
            stream.getTracks().forEach { runCatching { it.stop() } }
        }

        try {
            val chunker = PcmChunker(ctx.sampleRate.toDouble()) { chunk -> trySend(chunk) }
            val src = ctx.createMediaStreamSource(stream)
            source = src
            // Muted sink keeps the graph pulled without echoing the mic to the speakers.
            val muted = ctx.createGain().apply { gain.value = 0f }
            muted.connect(ctx.destination)
            sink = muted

            val worklet = if (ctx.asDynamic().audioWorklet != null) createWorkletNode(ctx) else null
            if (worklet != null) {
                worklet.port.onmessage = { event ->
                    chunker.accept(event.data.unsafeCast<Float32Array>().unsafeCast<FloatArray>())
                }
                src.connect(worklet)
                worklet.connect(muted)
                workletNode = worklet
            } else {
                val node = ctx.createScriptProcessor(SCRIPT_PROCESSOR_BLOCK, 1, 1)
                node.onaudioprocess = { event ->
                    chunker.accept(event.inputBuffer.getChannelData(0).unsafeCast<FloatArray>())
                }
                src.connect(node)
                node.connect(muted)
                scriptNode = node
            }
        } catch (t: Throwable) {
            teardown()
            throw t
        }

        captureClose = { close() }
        awaitClose {
            captureClose = null
            teardown()
        }
    }

    /**
     * Registers the capture worklet module and constructs its node, or returns
     * null to fall back to `ScriptProcessorNode` when the worklet path fails —
     * realistically a strict host-page CSP (`script-src`/`worker-src` without
     * `blob:`) rejecting [captureModuleUrl].
     */
    private suspend fun createWorkletNode(ctx: AudioContext): AudioWorkletNode? = try {
        ctx.audioWorklet.addModule(captureModuleUrl).await()
        AudioWorkletNode(ctx, CAPTURE_PROCESSOR_NAME)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        console.warn("BuddyVoice: AudioWorklet unavailable, using ScriptProcessorNode", e)
        null
    }

    actual fun stopCapture() {
        captureClose?.invoke()
    }

    actual suspend fun play(chunk: ByteArray) {
        if (chunk.size < 2) return
        val ctx = ensureContext()
        ctx.resume()
        // Backpressure: cap the scheduled-ahead audio like Android's bounded queue.
        while (nextStartTime - ctx.currentTime > MAX_BUFFERED_SECONDS) delay(POLL_MILLIS)

        val frames = chunk.size / 2
        val buffer = ctx.createBuffer(1, frames, SAMPLE_RATE.toFloat())
        buffer.copyToChannel(chunk.toFloat32(), 0)
        val source = ctx.createBufferSource()
        source.buffer = buffer
        source.connect(ctx.destination)
        source.onended = {
            scheduled.remove(source)
            source.disconnect()
        }
        val startAt = maxOf(ctx.currentTime, nextStartTime)
        nextStartTime = startAt + frames.toDouble() / SAMPLE_RATE
        scheduled.add(source)
        source.start(startAt)
    }

    actual fun stopPlayback() {
        val stale = scheduled.toList()
        scheduled.clear()
        stale.forEach { runCatching { it.stop() } }
        nextStartTime = 0.0
    }

    actual fun release() {
        stopCapture()
        stopPlayback()
        released = true
        context?.let { runCatching { it.close() } }
        context = null
    }

    private fun ensureContext(): AudioContext {
        check(!released) { "AudioEngine has been released" }
        context?.takeIf { it.state != "closed" }?.let { return it }
        return AudioContext().also {
            context = it
            resumeOnFirstGesture(it)
        }
    }

    private fun resumeOnFirstGesture(ctx: AudioContext) {
        val listener: (Event) -> Unit = {
            if (ctx.state == "suspended") ctx.resume()
        }
        GESTURE_EVENTS.forEach { type ->
            document.addEventListener(type, listener, js("({ once: true, passive: true })"))
        }
    }

    private companion object {
        /** ~2.5 s of queued audio before play() applies backpressure. */
        const val MAX_BUFFERED_SECONDS = 2.5
        const val POLL_MILLIS = 40L
        const val SCRIPT_PROCESSOR_BLOCK = 4096
        const val CAPTURE_PROCESSOR_NAME = "buddyvoice-capture"
        val GESTURE_EVENTS = listOf("click", "touchend", "keydown")

        /** Mono voice capture with the browser's echo cancellation stack enabled. */
        val CAPTURE_CONSTRAINTS: () -> dynamic = {
            js("({ channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true })")
        }

        /**
         * The worklet processor, registered from a Blob URL so the library needs
         * no separately hosted JS asset. It forwards each 128-frame capture block
         * to the main thread; downsampling happens Kotlin-side in [PcmChunker].
         */
        val captureModuleUrl: String by lazy {
            val code = """
                class BuddyVoiceCapture extends AudioWorkletProcessor {
                  process(inputs) {
                    const channel = inputs[0] && inputs[0][0];
                    if (channel && channel.length > 0) {
                      const copy = new Float32Array(channel);
                      this.port.postMessage(copy, [copy.buffer]);
                    }
                    return true;
                  }
                }
                registerProcessor('$CAPTURE_PROCESSOR_NAME', BuddyVoiceCapture);
            """.trimIndent()
            URL.createObjectURL(Blob(arrayOf(code), BlobPropertyBag(type = "text/javascript")))
        }
    }
}

/** Decodes 16-bit little-endian PCM into normalized Float32 samples. */
private fun ByteArray.toFloat32(): Float32Array {
    val frames = size / 2
    val floats = FloatArray(frames)
    for (i in 0 until frames) {
        val lo = this[2 * i].toInt() and 0xFF
        val hi = this[2 * i + 1].toInt()
        floats[i] = ((hi shl 8) or lo) / 32768f
    }
    return floats.unsafeCast<Float32Array>()
}

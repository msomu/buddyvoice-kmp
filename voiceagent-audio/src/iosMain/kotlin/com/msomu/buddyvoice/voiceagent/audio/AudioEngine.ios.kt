package com.msomu.buddyvoice.voiceagent.audio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Semaphore
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioConverterOutputStatus_Error
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.posix.memcpy

/**
 * iOS [AudioEngine] backed by a single [AVAudioEngine]: an input-node tap for
 * capture and an [AVAudioPlayerNode] for playback.
 *
 * The hardware runs at its native rate (typically 48 kHz float32); an
 * [AVAudioConverter] resamples capture down to the library's 16 kHz PCM16 LE
 * mono boundary, and playback chunks are expanded to float32 with the engine
 * resampling up to hardware rate.
 *
 * The input node opts into Apple voice processing so the mic gets echo
 * cancellation against the loudspeaker — the iOS counterpart of the hardware
 * AEC Android inherits from `VOICE_COMMUNICATION`.
 *
 * The app owns the mic permission: request `NSMicrophoneUsageDescription`-backed
 * record permission before collecting [startCapture].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class AudioEngine actual constructor() {

    private var engine: AVAudioEngine? = null
    private var player: AVAudioPlayerNode? = null

    /** Bounds scheduled-but-unplayed audio to ~2.5 s, mirroring the Android queue. */
    private val playbackGate = Semaphore(PLAYBACK_QUEUE_CHUNKS)

    @Volatile
    private var captureSink: SendChannel<ByteArray>? = null

    /** 16 kHz PCM16 mono — the library boundary format, produced by the capture converter. */
    private val captureFormat: AVAudioFormat by lazy {
        AVAudioFormat(AVAudioPCMFormatInt16, SAMPLE_RATE.toDouble(), 1u, true)
    }

    /** 16 kHz float32 mono — what the player node feeds the mixer, which resamples to hardware. */
    private val playbackFormat: AVAudioFormat by lazy {
        AVAudioFormat(AVAudioPCMFormatFloat32, SAMPLE_RATE.toDouble(), 1u, false)
    }

    actual fun startCapture(): Flow<ByteArray> = callbackFlow {
        check(captureSink == null) { "startCapture() is already active; stop it before collecting again" }
        val engine = ensureEngine()
        val input = engine.inputNode
        val hardwareFormat = input.outputFormatForBus(0u)
        check(hardwareFormat.sampleRate > 0.0) {
            "Microphone unavailable — is the record permission granted?"
        }
        val converter = AVAudioConverter(fromFormat = hardwareFormat, toFormat = captureFormat)
        val chunker = Pcm16Chunker()
        captureSink = this
        try {
            input.installTapOnBus(0u, TAP_BUFFER_FRAMES, hardwareFormat) { buffer, _ ->
                val inBuffer = buffer ?: return@installTapOnBus
                val bytes = convertToBoundaryFormat(converter, inBuffer) ?: return@installTapOnBus
                chunker.push(bytes) { chunk -> trySend(chunk) }
            }
            startEngine(engine)
        } catch (t: Throwable) {
            // awaitClose is not registered yet, so undo the partial setup here.
            // Otherwise a failed engine start (audio-session interruption, another
            // app holding the session) leaves the tap installed and captureSink
            // non-null, bricking every later startCapture() until process restart.
            input.removeTapOnBus(0u)
            captureSink = null
            throw t
        }
        awaitClose {
            captureSink = null
            input.removeTapOnBus(0u)
        }
    }

    actual fun stopCapture() {
        captureSink?.close()
    }

    actual suspend fun play(chunk: ByteArray) {
        val engine = ensureEngine()
        val node = player ?: return
        val buffer = pcm16ToFloatBuffer(chunk) ?: return
        playbackGate.acquire()
        startEngine(engine)
        if (!node.playing) node.play()
        node.scheduleBuffer(buffer) { playbackGate.release() }
    }

    actual fun stopPlayback() {
        // stop() drops every scheduled buffer and fires their completion handlers,
        // returning all playbackGate permits. play() restarts the node afterwards.
        player?.stop()
    }

    actual fun release() {
        stopCapture()
        player?.stop()
        engine?.stop()
        player = null
        engine = null
    }

    /** Creates the engine + player graph on first use, configuring the shared audio session. */
    private fun ensureEngine(): AVAudioEngine {
        engine?.let { return it }
        configureSession()
        val newEngine = AVAudioEngine()
        enableEchoCancellation(newEngine)
        val newPlayer = AVAudioPlayerNode()
        newEngine.attachNode(newPlayer)
        newEngine.connect(newPlayer, to = newEngine.mainMixerNode, format = playbackFormat)
        engine = newEngine
        player = newPlayer
        return newEngine
    }

    private fun configureSession() {
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker or
                    AVAudioSessionCategoryOptionAllowBluetooth,
                error = error.ptr,
            )
            session.setActive(true, error.ptr)
        }
    }

    /**
     * Opts the engine's I/O nodes into Apple voice processing (echo cancellation).
     *
     * Android gets hardware AEC implicitly via `VOICE_COMMUNICATION`; iOS must
     * enable it explicitly. Without it the open mic hears the agent's own playback
     * tail — the half-duplex gate opens on TurnEnded (wire `response.done`) while
     * up to ~2.5 s ([PLAYBACK_QUEUE_CHUNKS]) of scheduled audio is still coming out
     * of the loudspeaker, so server VAD segments phantom user turns and the agent
     * answers itself. Must run before the engine starts; enabling it on the input
     * node ties the output node into the same voice-processing unit.
     */
    private fun enableEchoCancellation(engine: AVAudioEngine) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!engine.inputNode.setVoiceProcessingEnabled(true, error.ptr)) {
                // Degraded but functional (e.g. simulator): capture still works,
                // only the AEC is missing.
                println("BuddyVoice: echo cancellation unavailable: ${error.value?.localizedDescription}")
            }
        }
    }

    private fun startEngine(engine: AVAudioEngine) {
        if (engine.running) return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            check(engine.startAndReturnError(error.ptr)) {
                "AVAudioEngine failed to start: ${error.value?.localizedDescription}"
            }
        }
    }

    /** Resamples one hardware-rate tap buffer to 16 kHz PCM16 LE mono bytes. */
    private fun convertToBoundaryFormat(
        converter: AVAudioConverter,
        input: AVAudioPCMBuffer,
    ): ByteArray? {
        val ratio = SAMPLE_RATE.toDouble() / input.format.sampleRate
        val capacity = (input.frameLength.toDouble() * ratio).toUInt() + 64u
        val output = AVAudioPCMBuffer(pCMFormat = captureFormat, frameCapacity = capacity)
        var pending: AVAudioPCMBuffer? = input
        val status = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            converter.convertToBuffer(output, error.ptr) { _, outStatus ->
                val next = pending
                if (next != null) {
                    pending = null
                    outStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                    next
                } else {
                    // Keep the converter's resampler state alive across tap callbacks.
                    outStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                    null
                }
            }
        }
        if (status == AVAudioConverterOutputStatus_Error) return null
        val byteCount = output.frameLength.toInt() * BYTES_PER_SAMPLE
        if (byteCount == 0) return null
        val source = output.int16ChannelData?.get(0) ?: return null
        val bytes = ByteArray(byteCount)
        bytes.usePinned { memcpy(it.addressOf(0), source, byteCount.convert()) }
        return bytes
    }

    /** Expands 16 kHz PCM16 LE mono bytes into a float32 buffer the player node accepts. */
    private fun pcm16ToFloatBuffer(chunk: ByteArray): AVAudioPCMBuffer? {
        val frames = chunk.size / BYTES_PER_SAMPLE
        if (frames == 0) return null
        val buffer = AVAudioPCMBuffer(pCMFormat = playbackFormat, frameCapacity = frames.toUInt())
        buffer.frameLength = frames.toUInt()
        val samples = buffer.floatChannelData?.get(0) ?: return null
        for (i in 0 until frames) {
            val low = chunk[2 * i].toInt() and 0xFF
            val high = chunk[2 * i + 1].toInt()
            samples[i] = ((high shl 8) or low) / 32768.0f
        }
        return buffer
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2

        /** ~2.5 s of queued audio before play() applies backpressure. */
        const val PLAYBACK_QUEUE_CHUNKS = 64

        /** Tap granularity at hardware rate (~85 ms at 48 kHz); rechunked to [CHUNK_BYTES] after resampling. */
        const val TAP_BUFFER_FRAMES = 4096u
    }
}

/** Reslices arbitrary-size converter output into exact [CHUNK_BYTES] chunks. Tap-thread only. */
private class Pcm16Chunker {
    private var pending = ByteArray(0)

    fun push(data: ByteArray, emit: (ByteArray) -> Unit) {
        val buffer = if (pending.isEmpty()) data else pending + data
        var offset = 0
        while (buffer.size - offset >= CHUNK_BYTES) {
            emit(buffer.copyOfRange(offset, offset + CHUNK_BYTES))
            offset += CHUNK_BYTES
        }
        pending = buffer.copyOfRange(offset, buffer.size)
    }
}

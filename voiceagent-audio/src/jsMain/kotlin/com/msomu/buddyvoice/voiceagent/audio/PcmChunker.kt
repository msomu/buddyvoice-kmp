package com.msomu.buddyvoice.voiceagent.audio

/**
 * Streams Float32 microphone blocks at the browser's native rate into fixed
 * [CHUNK_BYTES] chunks of 16 kHz PCM16 little-endian mono.
 *
 * Browsers pin `AudioContext` to the hardware rate (typically 44.1/48 kHz), so
 * capture is downsampled here with linear interpolation. Fractional read
 * position carries across blocks so no samples are dropped at block edges.
 */
internal class PcmChunker(
    sourceRate: Double,
    private val onChunk: (ByteArray) -> Unit,
) {
    private val step = sourceRate / SAMPLE_RATE
    private var carry = FloatArray(0)
    private var position = 0.0
    private val out = ByteArray(CHUNK_BYTES)
    private var outIndex = 0

    /** Feeds one capture block; invokes [onChunk] for every completed chunk. */
    fun accept(samples: FloatArray) {
        val data = if (carry.isEmpty()) samples else carry + samples
        var p = position
        while (p + 1 < data.size) {
            val i = p.toInt()
            val frac = (p - i).toFloat()
            write(data[i] + (data[i + 1] - data[i]) * frac)
            p += step
        }
        // Keep the yet-unread tail (plus one sample for interpolation continuity).
        val keepFrom = p.toInt().coerceAtMost(data.size)
        carry = data.copyOfRange(keepFrom, data.size)
        position = p - keepFrom
    }

    private fun write(sample: Float) {
        val s = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
        out[outIndex++] = (s and 0xFF).toByte()
        out[outIndex++] = ((s shr 8) and 0xFF).toByte()
        if (outIndex == CHUNK_BYTES) {
            onChunk(out.copyOf())
            outIndex = 0
        }
    }
}

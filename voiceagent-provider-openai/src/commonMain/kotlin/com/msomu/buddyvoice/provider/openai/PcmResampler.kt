package com.msomu.buddyvoice.provider.openai

import kotlin.math.roundToInt

/**
 * Linear resampler for PCM16 little-endian mono audio.
 *
 * BuddyVoice's audio boundary is 16 kHz but OpenAI's realtime API only accepts and
 * emits 24 kHz `audio/pcm`, so the provider converts each chunk at the wire:
 * 16 kHz -> 24 kHz on the way up, 24 kHz -> 16 kHz on the way down. Linear
 * interpolation is plenty for speech; chunks are resampled independently, which is
 * inaudible at the ~40 ms chunk sizes used here.
 */
internal fun resamplePcm16(input: ByteArray, fromRate: Int, toRate: Int): ByteArray {
    require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
    if (fromRate == toRate) return input
    val inSamples = input.size / 2
    if (inSamples == 0) return ByteArray(0)

    val outSamples = (inSamples.toLong() * toRate / fromRate).toInt()
    val out = ByteArray(outSamples * 2)
    val step = fromRate.toDouble() / toRate
    for (i in 0 until outSamples) {
        val srcPos = i * step
        val i0 = srcPos.toInt().coerceAtMost(inSamples - 1)
        val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
        val frac = srcPos - i0
        val s0 = sampleAt(input, i0)
        val s1 = sampleAt(input, i1)
        val value = (s0 + (s1 - s0) * frac).roundToInt().coerceIn(-32768, 32767)
        out[2 * i] = (value and 0xFF).toByte()
        out[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
    }
    return out
}

private fun sampleAt(pcm: ByteArray, index: Int): Int {
    val lo = pcm[2 * index].toInt() and 0xFF
    val hi = pcm[2 * index + 1].toInt() // sign-extends
    return (hi shl 8) or lo
}

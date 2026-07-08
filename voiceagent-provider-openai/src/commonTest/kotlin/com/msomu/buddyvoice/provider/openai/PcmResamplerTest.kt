package com.msomu.buddyvoice.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PcmResamplerTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(pcm: ByteArray): List<Int> =
        (0 until pcm.size / 2).map { i ->
            ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF))
        }

    @Test
    fun sameRateIsIdentity() {
        val input = pcm(1, -2, 3)
        assertSame(input, resamplePcm16(input, 16_000, 16_000))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(0, resamplePcm16(ByteArray(0), 16_000, 24_000).size)
    }

    @Test
    fun upsamplingBoundaryChunkTo24kGrowsByHalf() {
        // A 40 ms boundary chunk: 640 samples at 16 kHz -> 960 samples at 24 kHz.
        val input = ByteArray(1280)
        val output = resamplePcm16(input, 16_000, 24_000)
        assertEquals(1920, output.size)
    }

    @Test
    fun downsampling24kChunkTo16kShrinksByThird() {
        val input = ByteArray(1920)
        val output = resamplePcm16(input, 24_000, 16_000)
        assertEquals(1280, output.size)
    }

    @Test
    fun constantSignalStaysConstant() {
        val input = pcm(1000, 1000, 1000, 1000)
        val up = resamplePcm16(input, 16_000, 24_000)
        assertTrue(samples(up).all { it == 1000 })
        val down = resamplePcm16(input, 24_000, 16_000)
        assertTrue(samples(down).all { it == 1000 })
    }

    @Test
    fun upsamplingInterpolatesLinearly() {
        // 2 -> 3 samples per input pair: positions 0, 2/3, 4/3 of the input ramp.
        val input = pcm(0, 300, 600, 900)
        val output = samples(resamplePcm16(input, 16_000, 24_000))
        assertEquals(listOf(0, 200, 400, 600, 800, 900), output)
    }

    @Test
    fun downsamplingInterpolatesLinearly() {
        // 3 -> 2: positions 0 and 1.5 of the input.
        val input = pcm(0, 300, 600, 900, 1200, 1500)
        val output = samples(resamplePcm16(input, 24_000, 16_000))
        assertEquals(listOf(0, 450, 900, 1350), output)
    }

    @Test
    fun negativeSamplesSurviveRoundTrip() {
        val input = pcm(-32768, -16384, 0, 16384, 32767, 32767)
        val output = samples(resamplePcm16(input, 24_000, 16_000))
        assertEquals(4, output.size)
        assertEquals(-32768, output.first())
        assertTrue(output.all { it in -32768..32767 })
    }

    @Test
    fun firstSampleIsAlwaysPreserved() {
        val input = pcm(1234, 5678, -4321)
        assertEquals(1234, samples(resamplePcm16(input, 16_000, 24_000)).first())
        assertEquals(1234, samples(resamplePcm16(input, 24_000, 16_000)).first())
    }

    @Test
    fun oddTrailingByteIsDropped() {
        // Malformed input (not sample-aligned) must not crash.
        val input = byteArrayOf(0, 1, 2)
        val output = resamplePcm16(input, 16_000, 24_000)
        assertEquals(2, output.size)
        assertEquals(256, samples(output).single())
    }
}

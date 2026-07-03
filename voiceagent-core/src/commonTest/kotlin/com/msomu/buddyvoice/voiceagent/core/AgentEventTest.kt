package com.msomu.buddyvoice.voiceagent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentEventTest {

    @Test
    fun audioChunkEqualityIsByContent() {
        val a = AgentEvent.AudioChunk(byteArrayOf(1, 2, 3))
        val b = AgentEvent.AudioChunk(byteArrayOf(1, 2, 3))
        val c = AgentEvent.AudioChunk(byteArrayOf(9))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun configDefaultsAreSafe() {
        val config = VoiceAgentConfig(
            proxyBaseUrl = "https://proxy.example.dev",
            systemPrompt = "You are Buddy.",
        )

        assertNull(config.proxyKey)
        assertNull(config.voice)
        assertTrue(config.extra.isEmpty())
    }
}

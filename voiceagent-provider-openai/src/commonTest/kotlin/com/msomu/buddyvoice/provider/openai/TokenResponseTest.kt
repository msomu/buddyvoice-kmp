package com.msomu.buddyvoice.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenResponseTest {

    @Test
    fun parsesFlatValueShape() {
        // GA client_secrets response: {"value": "ek_...", "expires_at": ..., "session": {...}}
        assertEquals(
            "ek-abc",
            parseEphemeralToken("""{"value":"ek-abc","expires_at":1234567890,"session":{"id":"s1"}}"""),
        )
    }

    @Test
    fun parsesNestedClientSecretShape() {
        // Older sessions-endpoint shape: {"client_secret": {"value": ..., "expires_at": ...}}
        assertEquals(
            "ek-nested",
            parseEphemeralToken("""{"client_secret":{"value":"ek-nested","expires_at":1}}"""),
        )
    }

    @Test
    fun failsOnUnknownShape() {
        assertFailsWith<OpenAIConnectException> {
            parseEphemeralToken("""{"token":"nope"}""")
        }
    }

    @Test
    fun failsOnNonJson() {
        assertFailsWith<OpenAIConnectException> {
            parseEphemeralToken("<html>gateway error</html>")
        }
    }
}

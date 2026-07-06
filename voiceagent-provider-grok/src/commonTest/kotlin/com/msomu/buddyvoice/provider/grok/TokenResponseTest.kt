package com.msomu.buddyvoice.provider.grok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenResponseTest {

    @Test
    fun parsesFlatValueShape() {
        assertEquals(
            "tok-abc",
            parseEphemeralToken("""{"value":"tok-abc","expires_at":1234567890}"""),
        )
    }

    @Test
    fun parsesNestedClientSecretShape() {
        assertEquals(
            "tok-nested",
            parseEphemeralToken("""{"client_secret":{"value":"tok-nested","expires_at":1}}"""),
        )
    }

    @Test
    fun failsOnUnknownShape() {
        assertFailsWith<GrokConnectException> {
            parseEphemeralToken("""{"token":"nope"}""")
        }
    }

    @Test
    fun failsOnNonJson() {
        assertFailsWith<GrokConnectException> {
            parseEphemeralToken("<html>gateway error</html>")
        }
    }
}

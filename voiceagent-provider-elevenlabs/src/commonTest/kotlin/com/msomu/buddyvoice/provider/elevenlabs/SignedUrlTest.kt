package com.msomu.buddyvoice.provider.elevenlabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignedUrlTest {

    @Test
    fun parsesSignedUrl() {
        assertEquals(
            "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=a&conversation_signature=sig",
            parseSignedUrl(
                """{"signed_url":"wss://api.elevenlabs.io/v1/convai/conversation?agent_id=a&conversation_signature=sig"}""",
            ),
        )
    }

    @Test
    fun failsOnUnknownShape() {
        assertFailsWith<ElevenLabsConnectException> {
            parseSignedUrl("""{"url":"nope"}""")
        }
    }

    @Test
    fun failsOnNonJson() {
        assertFailsWith<ElevenLabsConnectException> {
            parseSignedUrl("<html>bad gateway</html>")
        }
    }
}

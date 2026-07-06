package com.msomu.buddyvoice

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.msomu.buddyvoice.voice.JsVoiceAgentController
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import org.w3c.dom.url.URLSearchParams

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val config = readProxyConfig()
    if (config == null) {
        console.warn(
            "BuddyVoice: no proxy configured. Pass ?proxyUrl=...&proxyKey=... query params " +
                "or copy local.config.example.js to local.config.js (gitignored) and fill it in.",
        )
    }
    val controller = config?.let { JsVoiceAgentController(it, MainScope()) }
    ComposeViewport(document.body!!) {
        App(voiceAgentController = controller)
    }
}

/**
 * Proxy settings for the sample, in priority order:
 * 1. URL query params `?proxyUrl=...&proxyKey=...` (quick dev override),
 * 2. `window.BUDDYVOICE_CONFIG` from the untracked `local.config.js`
 *    (see `local.config.example.js`).
 *
 * Only the proxy's base URL and its shared-secret gate ever reach the browser —
 * no provider key is ever part of this app.
 */
private fun readProxyConfig(): VoiceAgentConfig? {
    val params = URLSearchParams(window.location.search)
    val injected = window.asDynamic().BUDDYVOICE_CONFIG
    val proxyBaseUrl = params.get("proxyUrl")
        ?: (injected?.proxyBaseUrl as? String)
    if (proxyBaseUrl.isNullOrBlank() || proxyBaseUrl.startsWith("https://your-worker")) return null
    val proxyKey = params.get("proxyKey") ?: (injected?.proxyKey as? String)
    return VoiceAgentConfig(
        proxyBaseUrl = proxyBaseUrl,
        proxyKey = proxyKey?.takeUnless { it.isBlank() || it == "YOUR_PROXY_SHARED_SECRET" },
        systemPrompt = "You are Buddy, a friendly and concise voice assistant.",
    )
}

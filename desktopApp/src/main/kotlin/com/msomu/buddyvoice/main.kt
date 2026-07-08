package com.msomu.buddyvoice

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.msomu.buddyvoice.voice.DesktopProxyConfig
import com.msomu.buddyvoice.voice.DesktopVoiceAgentController
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    // Proxy settings are resolved at runtime (local.properties or BUDDYVOICE_* env
    // vars — see README.md). No provider key is ever part of this app.
    val proxy = DesktopProxyConfig.load()
    if (proxy == null) {
        System.err.println(
            "BuddyVoice: no proxy configured — set buddyvoice.proxyBaseUrl in local.properties " +
                "or the BUDDYVOICE_PROXY_BASE_URL env var. Showing the template demo instead.",
        )
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val controller = proxy?.let {
        DesktopVoiceAgentController(
            config = VoiceAgentConfig(
                proxyBaseUrl = it.proxyBaseUrl,
                proxyKey = it.proxyKey,
                systemPrompt = "You are Buddy, a friendly and concise voice assistant.",
            ),
            scope = scope,
        )
    }

    application {
        Window(
            onCloseRequest = {
                controller?.disconnect()
                exitApplication()
            },
            title = "Buddy Voice",
        ) {
            App(voiceAgentController = controller)
        }
    }
}

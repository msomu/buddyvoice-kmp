package com.msomu.buddyvoice

import androidx.compose.ui.window.ComposeUIViewController
import com.msomu.buddyvoice.voice.IosVoiceAgentController
import com.msomu.buddyvoice.voice.VoiceAgentController
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig
import kotlinx.coroutines.MainScope
import platform.UIKit.UIViewController

/**
 * Builds the iOS controller from proxy settings the Swift shell reads out of its
 * gitignored `BuddyVoiceConfig.plist` — no provider key is ever part of this app.
 * The controller outlives reconnects; keep one instance per app.
 */
fun createVoiceAgentController(proxyBaseUrl: String, proxyKey: String?): VoiceAgentController =
    IosVoiceAgentController(
        config = VoiceAgentConfig(
            proxyBaseUrl = proxyBaseUrl,
            proxyKey = proxyKey?.takeIf { it.isNotBlank() },
            systemPrompt = "You are Buddy, a friendly and concise voice assistant.",
        ),
        scope = MainScope(),
    )

/** Entry point for the SwiftUI shell: hosts the shared Compose [App]. */
fun MainViewController(controller: VoiceAgentController?): UIViewController =
    ComposeUIViewController { App(voiceAgentController = controller) }

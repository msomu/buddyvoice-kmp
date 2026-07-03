package com.msomu.buddyvoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.msomu.buddyvoice.voice.AndroidVoiceAgentController
import com.msomu.buddyvoice.voiceagent.core.VoiceAgentConfig

class MainActivity : ComponentActivity() {

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* state read lazily */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Proxy settings come from local.properties (gitignored) via BuildConfig —
        // see server-proxy/README.md. No provider key is ever part of this app.
        val controller = AndroidVoiceAgentController(
            config = VoiceAgentConfig(
                proxyBaseUrl = BuildConfig.BUDDYVOICE_PROXY_BASE_URL,
                proxyKey = BuildConfig.BUDDYVOICE_PROXY_KEY.ifEmpty { null },
                systemPrompt = "You are Buddy, a friendly and concise voice assistant.",
            ),
            scope = lifecycleScope,
        )

        setContent {
            App(voiceAgentController = controller)
        }
    }
}

package com.msomu.buddyvoice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.resources.painterResource

import buddyvoice.sharedui.generated.resources.Res
import buddyvoice.sharedui.generated.resources.compose_multiplatform
import com.msomu.buddyvoice.voice.VoiceAgentController
import com.msomu.buddyvoice.voice.VoiceAgentScreen

/**
 * Sample-app root. Platforms that have wired up voice support (Phase 1: Android)
 * pass a [VoiceAgentController]; the rest pass `null` and keep the template demo
 * until their phase lands.
 */
@Composable
@Preview
fun App(voiceAgentController: VoiceAgentController? = null) {
    MaterialTheme {
        if (voiceAgentController != null) {
            // Surface paints the themed background: on web/desktop the canvas has
            // no window background behind it, unlike an Android Activity.
            Surface(modifier = Modifier.fillMaxSize()) {
                VoiceAgentScreen(voiceAgentController, Modifier.safeContentPadding())
            }
            return@MaterialTheme
        }
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")
                }
            }
        }
    }
}
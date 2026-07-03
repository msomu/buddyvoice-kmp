package com.msomu.buddyvoice.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Phase 1 demo screen: connection controls, live transcript, hold-to-talk.
 */
@Composable
fun VoiceAgentScreen(controller: VoiceAgentController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectionBar(state, controller)

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Transcript(
            lines = state.transcript,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
        )

        if (state.agentIsTalking) {
            OutlinedButton(onClick = controller::interrupt) {
                Text("Interrupt")
            }
        }

        HoldToTalkButton(
            enabled = state.connection == ConnectionState.Connected,
            isTalking = state.userIsTalking,
            onPress = controller::startTalking,
            onRelease = controller::stopTalking,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ConnectionBar(state: VoiceAgentUiState, controller: VoiceAgentController) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(state.connection)
        when (state.connection) {
            ConnectionState.Connected, ConnectionState.Connecting ->
                OutlinedButton(onClick = controller::disconnect) { Text("Disconnect") }
            ConnectionState.Disconnected, ConnectionState.Error ->
                Button(onClick = controller::connect) { Text("Connect") }
        }
    }
}

@Composable
private fun StatusChip(connection: ConnectionState) {
    val (label, color) = when (connection) {
        ConnectionState.Disconnected -> "Disconnected" to MaterialTheme.colorScheme.surfaceVariant
        ConnectionState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.tertiaryContainer
        ConnectionState.Connected -> "Connected" to MaterialTheme.colorScheme.primaryContainer
        ConnectionState.Error -> "Error" to MaterialTheme.colorScheme.errorContainer
    }
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Transcript(lines: List<TranscriptLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    LazyColumn(state = listState, modifier = modifier) {
        items(lines) { line ->
            TranscriptBubble(line)
        }
    }
}

@Composable
private fun TranscriptBubble(line: TranscriptLine) {
    val isUser = line.speaker == TranscriptLine.Speaker.User
    val background =
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = background,
            tonalElevation = if (line.isFinal) 0.dp else 2.dp,
        ) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (line.isFinal) 1f else 0.6f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun HoldToTalkButton(
    enabled: Boolean,
    isTalking: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isTalking -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(color)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            onPress()
                            try {
                                awaitRelease()
                            } finally {
                                onRelease()
                            }
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isTalking) "Listening…" else "Hold to talk",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}

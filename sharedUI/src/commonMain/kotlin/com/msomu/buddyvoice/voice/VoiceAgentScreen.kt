package com.msomu.buddyvoice.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Phase 1 demo screen, Grok-app style: one orb does everything.
 *
 * Tap the orb to start a session (open mic, server VAD segments turns); tap it
 * while the agent is speaking to barge in; tap ✕ to end the session.
 */
@Composable
fun VoiceAgentScreen(controller: VoiceAgentController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "BuddyVoice",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Grok voice agent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.transcript.isNotEmpty()) {
                Text(
                    text = "New chat",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(CircleShape)
                        .clickable(onClick = controller::clearConversation)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        if (state.transcript.isEmpty()) {
            EmptyHint(modifier = Modifier.weight(1f))
        } else {
            Transcript(
                lines = state.transcript,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
            )
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Orb(
            connection = state.connection,
            agentIsTalking = state.agentIsTalking,
            onTap = {
                when {
                    state.connection == ConnectionState.Disconnected ||
                        state.connection == ConnectionState.Error -> controller.connect()
                    state.agentIsTalking -> controller.interrupt()
                    // Already listening: taps are a no-op, just talk.
                    else -> Unit
                }
            },
        )

        Text(
            text = when {
                state.connection == ConnectionState.Connecting -> "Connecting…"
                state.connection != ConnectionState.Connected -> "Tap to talk"
                state.agentIsTalking -> "Speaking — tap to interrupt"
                else -> "Listening — go ahead"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )

        Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            if (state.connection == ConnectionState.Connected ||
                state.connection == ConnectionState.Connecting
            ) {
                Text(
                    text = "✕  End",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = controller::disconnect)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Chat",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Tap the orb and start talking",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Orb(
    connection: ConnectionState,
    agentIsTalking: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = connection == ConnectionState.Connected || connection == ConnectionState.Connecting
    val pulse by rememberInfiniteTransition(label = "orb-pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "orb-scale",
    )
    val body = when {
        agentIsTalking -> MaterialTheme.colorScheme.tertiary
        active -> Color(0xFF0E7490) // listening teal
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = modifier
            .size(96.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.85f), body, body),
                    radius = 130f,
                ),
            )
            .clickable(onClick = onTap),
    )
}

@Composable
private fun Transcript(lines: List<TranscriptLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(0)
    }
    // ChatGPT-style: conversation is anchored to the bottom and grows upward.
    // With reverseLayout, index 0 sits at the bottom, so the list is fed newest-first.
    LazyColumn(state = listState, reverseLayout = true, modifier = modifier) {
        items(lines.asReversed()) { line ->
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

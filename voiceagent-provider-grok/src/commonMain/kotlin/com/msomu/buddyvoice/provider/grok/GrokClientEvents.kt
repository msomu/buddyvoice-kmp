package com.msomu.buddyvoice.provider.grok

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Outbound (client -> Grok) events. Kept as typed DTOs so session configuration
// is testable; inbound events are parsed dynamically in GrokServerEventMapper.

/** Matches voiceagent-audio's boundary format (16 kHz PCM16 mono) without depending on it. */
internal const val GROK_AUDIO_SAMPLE_RATE = 16_000

@Serializable
internal data class SessionUpdate(
    val type: String = "session.update",
    val session: SessionConfig,
)

@Serializable
internal data class SessionConfig(
    val instructions: String,
    val voice: String? = null,
    @SerialName("turn_detection") val turnDetection: TurnDetection = TurnDetection(),
    val audio: AudioConfig = AudioConfig(),
)

@Serializable
internal data class TurnDetection(
    val type: String = "server_vad",
)

@Serializable
internal data class AudioConfig(
    val input: AudioSide = AudioSide(),
    val output: AudioSide = AudioSide(),
)

@Serializable
internal data class AudioSide(
    val format: PcmFormat = PcmFormat(),
)

@Serializable
internal data class PcmFormat(
    val type: String = "audio/pcm",
    val rate: Int = GROK_AUDIO_SAMPLE_RATE,
)

@Serializable
internal data class InputAudioAppend(
    val type: String = "input_audio_buffer.append",
    val audio: String,
)

@Serializable
internal data class InputAudioClear(
    val type: String = "input_audio_buffer.clear",
)

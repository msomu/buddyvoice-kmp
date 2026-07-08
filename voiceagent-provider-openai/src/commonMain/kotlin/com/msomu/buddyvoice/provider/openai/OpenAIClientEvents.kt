package com.msomu.buddyvoice.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Outbound (client -> OpenAI) events, GA realtime schema. Kept as typed DTOs so
// session configuration is testable; inbound events are parsed dynamically in
// OpenAIServerEventMapper.

/** BuddyVoice's audio boundary format (16 kHz PCM16 mono), see docs/architecture.md. */
internal const val BOUNDARY_SAMPLE_RATE = 16_000

/**
 * OpenAI's realtime API only speaks 24 kHz for `audio/pcm` (the docs allow no other
 * rate), so this provider resamples 16 kHz boundary audio to and from 24 kHz
 * internally — see [resamplePcm16].
 */
internal const val OPENAI_WIRE_SAMPLE_RATE = 24_000

@Serializable
internal data class SessionUpdate(
    val type: String = "session.update",
    val session: SessionConfig,
)

@Serializable
internal data class SessionConfig(
    /** GA discriminator; the beta schema ignores unknown fields, so it is safe to always send. */
    val type: String = "realtime",
    val instructions: String,
    val audio: AudioConfig = AudioConfig(),
)

@Serializable
internal data class AudioConfig(
    val input: AudioInput = AudioInput(),
    val output: AudioOutput = AudioOutput(),
)

@Serializable
internal data class AudioInput(
    val format: PcmFormat = PcmFormat(),
    @SerialName("turn_detection") val turnDetection: TurnDetection = TurnDetection(),
    val transcription: TranscriptionConfig? = null,
)

@Serializable
internal data class AudioOutput(
    val format: PcmFormat = PcmFormat(),
    val voice: String? = null,
)

@Serializable
internal data class PcmFormat(
    val type: String = "audio/pcm",
    val rate: Int = OPENAI_WIRE_SAMPLE_RATE,
)

@Serializable
internal data class TurnDetection(
    val type: String = "server_vad",
)

@Serializable
internal data class TranscriptionConfig(
    val model: String,
)

@Serializable
internal data class InputAudioAppend(
    val type: String = "input_audio_buffer.append",
    val audio: String,
)

@Serializable
internal data class ResponseCancel(
    val type: String = "response.cancel",
)

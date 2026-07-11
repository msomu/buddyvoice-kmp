package com.msomu.buddyvoice.provider.elevenlabs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Outbound (client -> ElevenLabs) events. Kept as typed DTOs so session
// configuration is testable; inbound events are parsed dynamically in
// ElevenLabsServerEventMapper.

/** The audio format the library speaks at its boundary. The agent must be configured to match. */
internal const val REQUIRED_AUDIO_FORMAT = "pcm_16000"

/**
 * First message after connect. The overrides only take effect if the agent's
 * security settings allow them (Agent > Security > Overrides in the ElevenLabs
 * dashboard); otherwise the agent's dashboard configuration wins silently.
 */
@Serializable
internal data class ConversationInitiation(
    val type: String = "conversation_initiation_client_data",
    @SerialName("conversation_config_override")
    val configOverride: ConfigOverride? = null,
)

@Serializable
internal data class ConfigOverride(
    val agent: AgentOverride? = null,
    val tts: TtsOverride? = null,
)

@Serializable
internal data class AgentOverride(
    val prompt: PromptOverride? = null,
    val language: String? = null,
    @SerialName("first_message") val firstMessage: String? = null,
)

@Serializable
internal data class PromptOverride(
    val prompt: String,
)

@Serializable
internal data class TtsOverride(
    @SerialName("voice_id") val voiceId: String? = null,
)

/** User audio is the one client event with no `type` discriminator. */
@Serializable
internal data class UserAudioChunk(
    @SerialName("user_audio_chunk") val userAudioChunk: String,
)

/** Keepalive reply to the server's `ping` event. */
@Serializable
internal data class Pong(
    val type: String = "pong",
    @SerialName("event_id") val eventId: Long,
)

# BuddyVoice Architecture

Status: Phase 4 (Android + iOS + Desktop + Grok + OpenAI Realtime). See [PRD.md](PRD.md) for the full roadmap.

## Module graph

```
                    ┌──────────────────────┐
                    │   voiceagent-core     │  interfaces + events only
                    └─────▲──────────▲─────┘
                          │          │
        ┌─────────────────┴───┐  ┌───┴───────────────────────┐
        │ voiceagent-transport │  │  voiceagent-provider-grok │
        │ (Ktor WS/HTTP)       │◄─┤  (wire protocol mapping)  │
        └─────────────────────┘  └───────────▲───────────────┘
                                             │
   ┌──────────────────┐                      │
   │ voiceagent-audio  │◄──────────┐         │
   │ (expect/actual)   │           │         │
   └──────────────────┘        ┌───┴─────────┴───┐
                               │    androidApp    │  wires audio + provider
                               └───────▲─────────┘   into the shared UI
                                       │
                          ┌────────────┴────────────┐
                          │        sharedUI          │  VoiceAgentScreen +
                          │ (VoiceAgentController    │  VoiceAgentController
                          │  interface, no voiceagent│  (pure UI seam)
                          │  dependencies)           │
                          └─────────────────────────┘
```

Key seams:

- **Provider isolation** — only `voiceagent-provider-grok` knows Grok's wire format. App code sees `VoiceAgentProvider` / `VoiceAgentSession` / `AgentEvent`.
- **UI seam** — `sharedUI` defines a small `VoiceAgentController` interface and state model; its commonMain does not depend on any voiceagent module. `androidApp` and `desktopApp` implement the controller by wiring `AudioEngine` + `GrokVoiceAgentProvider`. Platforms whose phase hasn't landed keep compiling with `controller = null`.
- **iOS wiring exception** — the Swift shell cannot host Kotlin the way `androidApp` does, so `sharedUI`'s **iosMain** (and only iosMain) plays the app-layer role: it holds `IosVoiceAgentController` (a mirror of `AndroidVoiceAgentController`), the `MainViewController` entry point, and the framework's voiceagent dependencies. `iosApp` stays a thin SwiftUI shell that reads proxy config from the gitignored `BuddyVoiceConfig.plist` and hands it to the Kotlin side.
- **Audio isolation** — providers never touch `voiceagent-audio`. Audio bytes cross the boundary as plain `ByteArray` at the app layer.

## Audio contract

Everything at the interface boundary is **16 kHz, PCM16, little-endian, mono**:

- `AudioEngine.startCapture()` emits ~40 ms chunks (1280 bytes) in that format.
- `VoiceAgentSession.sendAudio(chunk)` expects that format; the Grok provider base64-encodes it into `input_audio_buffer.append` and configures the session for 16 kHz PCM in/out via `session.update`.
- `AgentEvent.AudioChunk.data` is in that format, ready for `AudioEngine.play()`.

## Connection sequence (token-mint proxy, Phase 1)

```
androidApp                server-proxy (CF Worker)          api.x.ai
    │                            │                             │
    │ POST /session/grok         │                             │
    │ X-BuddyVoice-Proxy-Key ───►│  validate shared secret     │
    │                            │  POST /v1/realtime/         │
    │                            │  client_secrets ───────────►│
    │                            │◄── ephemeral token (≤5 min) │
    │◄── token ──────────────────│                             │
    │                                                          │
    │ wss://api.x.ai/v1/realtime?model=grok-voice-latest       │
    │ Authorization: Bearer <ephemeral token> ────────────────►│
    │◄──────────── realtime events (audio + transcripts) ────►│
```

The Worker never relays audio — it only mints tokens. The long-lived `XAI_API_KEY`
exists solely as a Worker Secret. The client holds only a token that expires in
minutes. Browsers (Phase 5) will pass the token as the WebSocket subprotocol
`xai-client-secret.<token>` since they cannot set headers.

## Grok event mapping

| Grok server event | AgentEvent |
|---|---|
| `response.created` | `TurnStarted` |
| `response.output_audio.delta` | `AudioChunk` (base64-decoded) |
| `response.text.delta` | `PartialTranscript(cumulative, isFinal=false)` |
| `response.done` | final `PartialTranscript` + `TurnEnded` |
| `conversation.item.input_audio_transcription.updated` | `PartialTranscript` (user speech, cumulative) |
| `error` | `Error` |
| anything else | ignored (schema is evolving; parsing is lenient) |

`interrupt()` sends `input_audio_buffer.clear` and emits a synthetic `TurnEnded`
locally (xAI documents no `response.cancel` yet); the app flushes local playback.

## OpenAI Realtime event mapping (Phase 3)

`voiceagent-provider-openai` follows the same shape (POST `/session/openai` for an
ephemeral token, then `wss://api.openai.com/v1/realtime?model=gpt-realtime`). Two
provider-internal differences, both invisible outside the module:

- **Audio rate** — OpenAI only speaks 24 kHz `audio/pcm`, so the session resamples
  16 kHz boundary audio up on `sendAudio` and 24 kHz wire audio down before emitting
  `AgentEvent.AudioChunk` (linear interpolation).
- **User ASR is incremental** — OpenAI streams `conversation.item.input_audio_transcription.delta`
  as increments (xAI sends cumulative text), so the mapper accumulates before emitting
  the cumulative `PartialTranscript`.

| OpenAI server event | AgentEvent |
|---|---|
| `response.created` | `TurnStarted` |
| `response.output_audio.delta` (beta: `response.audio.delta`) | `AudioChunk` (base64-decoded, resampled to 16 kHz) |
| `response.output_audio_transcript.delta` (beta: `response.audio_transcript.delta`) | `PartialTranscript(cumulative, isFinal=false)` |
| `response.done` | final `PartialTranscript` + `TurnEnded` |
| `conversation.item.input_audio_transcription.delta` | `PartialTranscript` (user speech, accumulated) |
| `conversation.item.input_audio_transcription.completed` | final user `PartialTranscript` |
| `error` | `Error` |
| anything else | ignored (lenient by design) |

`interrupt()` sends `response.cancel` plus a synthetic local `TurnEnded`.

## Support matrix

| | Grok | OpenAI Realtime | ElevenLabs |
|---|---|---|---|
| Android | ✅ Phase 1 | ✅ Phase 3 | Phase 5 |
| iOS | ✅ Phase 2 | Phase 3 | Phase 5 |
| Desktop (JVM) | ✅ Phase 4 | Phase 4 | Phase 5 |
| Web | Phase 5 | Phase 5 | Phase 5 |

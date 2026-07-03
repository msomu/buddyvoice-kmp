# PRD: BuddyVoice KMP

**Owner:** Somu (Somasundaram Mahesh)
**License:** MIT
**Maven coordinates:** `io.github.msomu:buddyvoice-*`
**Goal:** A Kotlin Multiplatform library that connects an app to any realtime voice AI provider (Grok, OpenAI Realtime, ElevenLabs, others later) through one common interface, running on Android, iOS, Desktop (JVM), and Web.

---

## 1. Problem and Goal

Voice agent demos today are hardcoded to one provider. Swapping providers means rewriting the client. This library fixes that by putting a provider-agnostic interface between the app and the voice API, so switching from Grok to OpenAI to ElevenLabs is a config change, not a rewrite.

**Non-goals for v1:**
- Not building a hosted product — this is a library.
- Not solving telephony/IVR use cases.
- Not targeting watchOS/tvOS in v1.

---

## 2. Hard Security Requirement (non-negotiable, applies to every phase)

This repo is public, so this rule overrides convenience at every step:

- **No provider API key ever ships inside client code, client bundle, or a built binary (APK, IPA, web bundle, JAR).** Not even for "just testing."
- All provider credentials live behind a backend proxy (`server-proxy/`) that holds real keys as server-side secrets.
- Client apps authenticate to the proxy with a **shared secret header** (`X-BuddyVoice-Proxy-Key`) — a fixed token set as a Worker Secret. This is a basic gate against casual scraping, not full user auth; upgrade to per-user auth later if needed.
- `.env`, `local.properties`, and any secrets file must be gitignored. Placeholder-only `*.example` files are provided instead.
- A pre-commit hook + gitleaks in CI fail the build if anything matching a secret pattern is staged/committed.
- CI secrets live only in GitHub repo Settings → Secrets, never echoed to logs.
- Only obvious placeholders like `YOUR_GROK_KEY_HERE` appear in the repo.

---

## 3. Architecture Overview

### 3.1 Module structure

Existing app modules stay as the sample apps (JetBrains' default KMP structure: shared modules + per-platform app modules). Library modules are separate top-level publishable Gradle modules with flat, prefixed names (the convention used by Coil, Apollo, Ktor):

```
buddyvoice-kmp/
├── voiceagent-core/            // interfaces, domain models; no platform code, no provider code
├── voiceagent-audio/           // expect/actual mic capture + playback per platform
├── voiceagent-transport/       // shared Ktor WebSocket/HTTP client used by WS-based providers
├── voiceagent-provider-grok/   // Grok Voice Agent API implementation
│   (future: voiceagent-provider-openai, voiceagent-provider-elevenlabs)
├── server-proxy/               // Cloudflare Worker: holds real keys, mints ephemeral tokens
├── sharedLogic/, sharedUI/     // sample apps' shared code (demo UI lives in sharedUI)
├── androidApp/, iosApp/, desktopApp/, webApp/   // sample apps, one per platform
└── docs/                       // architecture docs, ADRs, build-in-public log
```

### 3.2 Core interfaces (the contract everything else implements)

These live in `voiceagent-core` and are the only thing app code and provider modules both depend on.

```kotlin
interface VoiceAgentProvider {
    val id: String // "grok", "openai-realtime", "elevenlabs"
    suspend fun connect(config: VoiceAgentConfig): VoiceAgentSession
}

interface VoiceAgentSession {
    val events: Flow<AgentEvent>
    suspend fun sendAudio(chunk: ByteArray)
    suspend fun interrupt()
    suspend fun close()
}

sealed interface AgentEvent {
    data class PartialTranscript(val text: String, val isFinal: Boolean) : AgentEvent
    data class AudioChunk(val data: ByteArray) : AgentEvent
    data object TurnStarted : AgentEvent
    data object TurnEnded : AgentEvent
    data class Error(val cause: Throwable) : AgentEvent
}

data class VoiceAgentConfig(
    val proxyBaseUrl: String,     // your backend, never a provider URL with a key in it
    val proxyKey: String? = null, // shared secret sent as X-BuddyVoice-Proxy-Key
    val systemPrompt: String,
    val voice: String? = null,
    val extra: Map<String, String> = emptyMap() // provider-specific overrides
)
```

Design rule: nothing outside `voiceagent-core` and a specific provider module knows the shape of that provider's wire protocol.

### 3.3 Audio layer

`expect class AudioEngine` in `voiceagent-audio`, with `actual` per platform:

| Platform | API used | Status |
|---|---|---|
| Android | `AudioRecord` / `AudioTrack` | Phase 1 |
| iOS | `AVAudioEngine` | Phase 2 |
| Desktop (JVM) | `javax.sound.sampled` | Phase 4 |
| Web | Web Audio API | Phase 5 |

All actuals expose the same shape: `fun startCapture(): Flow<ByteArray>` and `suspend fun play(chunk: ByteArray)`. Sample rate and encoding are normalized to **16 kHz PCM16 LE mono** at the interface boundary so provider modules never deal with platform audio formats.

### 3.4 Transport layer

- `voiceagent-transport` wraps Ktor's WebSocket/HTTP client, used by WebSocket-based providers (Grok, OpenAI Realtime).
- ElevenLabs uses WebRTC, not WebSocket. Its future provider module owns its own transport and does not use `voiceagent-transport`.

### 3.5 Server proxy

- Deployed on **Cloudflare Workers** (TypeScript, `wrangler`).
- Every route first validates `X-BuddyVoice-Proxy-Key` against a Worker Secret (constant-time compare). 401 before touching any provider API.
- **Token-mint design**: `/session/grok` calls xAI's `POST /v1/realtime/client_secrets` and returns the short-lived (~5 min) ephemeral token. The client then connects **directly** to `wss://api.x.ai/v1/realtime` with that token — no long-lived key ever reaches the device, and no audio flows through the Worker.
- Real keys are **Worker Secrets** (`wrangler secret put`), never in `wrangler.toml`, never in code.
- Contributors deploy their own Worker with their own secrets — that is what makes this repo safe to run end-to-end.

### 3.6 Distribution

- Published to **Maven Central** via `com.vanniktech.maven.publish` under `io.github.msomu`.
- Only `voiceagent-core`, `voiceagent-audio`, `voiceagent-transport`, and each `voiceagent-provider-*` module are published. `server-proxy` and the sample apps stay source-only.
- Semantic versioning starting at `0.1.0`; bump minor for each phase that adds a platform or provider.

---

## 4. Build Phases

### Phase 1 (this build): Core interfaces + Grok provider + Android audio + minimal proxy
One real end-to-end voice conversation: Android sample app → own proxy → Grok, no key on device. Includes repo hygiene (.gitignore, gitleaks, CI) and the Compose push-to-talk + transcript demo UI.

**Acceptance:** talk to Grok through the Android sample app, see live transcript, hear the response, with zero API keys anywhere in the client or repo history.

### Phase 2: iOS audio actual
`AVAudioEngine` actual + SwiftUI shell in `iosApp` consuming the shared modules. Zero changes to core/transport/grok allowed — if iOS forces a core change, that is a design smell to flag, not patch around.

### Phase 3: OpenAI Realtime as second provider
`voiceagent-provider-openai` + proxy route + provider picker in the sample UI. Breaking interface changes get an ADR in `docs/adr/`.

### Phase 4: Desktop (JVM) audio actual
`javax.sound.sampled` actual; desktopApp gets the voice UI.

### Phase 5: Web audio actual + ElevenLabs provider
Web Audio interop + `voiceagent-provider-elevenlabs` (own WebRTC transport) + WebRTC signaling in the proxy. May ship as experimental if web audio tooling drags.

---

## 5. Instructions for contributors (and Claude Code)

- Follow phases in order; do not start Phase N+1 until Phase N acceptance criteria are met.
- Never write a real API key into any file — placeholders only.
- Keep `voiceagent-core` free of any provider-specific or platform-specific code.
- When a phase forces a change to a core interface, stop and flag it (ADR), don't quietly patch.
- Prefer small, reviewable commits — commit history is part of the build-in-public story.

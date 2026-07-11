# BuddyVoice KMP

A Kotlin Multiplatform library that connects your app to **any realtime voice AI
provider** (Grok, OpenAI Realtime, and ElevenLabs Agents) through one common
interface. Swapping providers is a config change, not a rewrite — and **no provider
API key ever ships in client code**.

```kotlin
val session = GrokVoiceAgentProvider().connect(
    VoiceAgentConfig(
        proxyBaseUrl = "https://your-proxy.workers.dev", // your own token-mint proxy
        proxyKey = yourSharedSecret,
        systemPrompt = "You are Buddy, a friendly voice assistant.",
    ),
)
session.events.collect { event ->
    when (event) {
        is AgentEvent.AudioChunk -> audioEngine.play(event.data)
        is AgentEvent.PartialTranscript -> showTranscript(event.text)
        // ...
    }
}
```

## Support matrix

| | Grok | OpenAI Realtime | ElevenLabs |
|---|---|---|---|
| Android | ✅ | ✅ | 🔌 |
| iOS | ✅ | 🔌 | 🔌 |
| Desktop (JVM) | ✅ | 🔌 | 🔌 |
| Web | ✅ | 🔌 | 🔌 |

✅ verified end-to-end on a real device · 🔌 fully wired (provider + sample
picker), awaiting a real-device conversation to earn its check mark.

The PRD assumed ElevenLabs would need WebRTC; on inspection ElevenLabs Agents
has a first-class WebSocket API with server-minted signed URLs, so its provider
reuses `voiceagent-transport` like the others. Your ElevenLabs agent must be
configured for **PCM 16000 Hz** input and output (see
[server-proxy/README.md](./server-proxy/README.md)).

See [docs/PRD.md](docs/PRD.md) for the roadmap and
[docs/architecture.md](docs/architecture.md) for how the pieces fit.

## Modules

**Library (published as `io.github.msomu:buddyvoice-*`):**

* [voiceagent-core](./voiceagent-core/src) — `VoiceAgentProvider` / `VoiceAgentSession` /
  `AgentEvent` interfaces. No platform code, no provider code. The contract everything implements.
* [voiceagent-audio](./voiceagent-audio/src) — `expect/actual AudioEngine`: mic capture and
  playback normalized to 16 kHz PCM16 mono (Android: `AudioRecord`/`AudioTrack`; iOS:
  `AVAudioEngine`; Desktop: `javax.sound.sampled`; other platforms land per phase).
* [voiceagent-transport](./voiceagent-transport/src) — shared Ktor WebSocket/HTTP client used by
  WebSocket-based providers.
* [voiceagent-provider-grok](./voiceagent-provider-grok/src) — xAI Grok Voice Agent API
  implementation. The only module that knows Grok's wire format.
* [voiceagent-provider-openai](./voiceagent-provider-openai/src) — OpenAI Realtime API
  implementation. Resamples 16 kHz boundary audio to/from OpenAI's 24 kHz wire format internally.
* [voiceagent-provider-elevenlabs](./voiceagent-provider-elevenlabs/src) — ElevenLabs Agents
  implementation over its signed-URL WebSocket API. Agents are configured in the ElevenLabs
  dashboard; the library's prompt/voice apply only if the agent allows overrides.

**Backend:**

* [server-proxy](./server-proxy) — Cloudflare Worker that holds real provider keys as Worker
  Secrets and mints short-lived ephemeral tokens for clients. Source-only, never published.

**Sample apps** (source-only):

* [androidApp](./androidApp) — Compose Android app with the Phase 1 voice demo (push-to-talk +
  live transcript).
* [iosApp](./iosApp) — SwiftUI shell hosting the same shared Compose voice demo (Phase 2).
* [webApp](./webApp) — Kotlin/JS Compose app with the same voice demo running in the browser
  (Phase 5).
* [desktopApp](./desktopApp) — per-platform sample shells; they gain voice
  support as their phase lands.
* [sharedLogic](./sharedLogic/src) / [sharedUI](./sharedUI/src) — code shared by the sample apps,
  including the `VoiceAgentScreen` Compose UI.

## Run the Android demo end-to-end

1. **Deploy your own proxy (~5 minutes)** — follow [server-proxy/README.md](./server-proxy/README.md).
   Your xAI key stays server-side as a Worker Secret; clients only ever hold ~5-minute tokens.
2. **Point the app at it** — add to `local.properties` (gitignored, never commit it):
   ```properties
   buddyvoice.proxyBaseUrl=https://buddyvoice-proxy.YOUR_SUBDOMAIN.workers.dev
   buddyvoice.proxyKey=YOUR_LONG_RANDOM_STRING_HERE
   ```
3. **Install and talk** — `./gradlew :androidApp:installDebug`, grant the microphone permission,
   tap Connect, hold the button and talk.

## Run the iOS demo end-to-end

1. Deploy your proxy as above.
2. Copy `iosApp/iosApp/BuddyVoiceConfig.example.plist` to `iosApp/iosApp/BuddyVoiceConfig.plist`
   (gitignored, never commit it) and fill in your proxy URL and shared secret.
3. Open [iosApp/iosApp.xcodeproj](./iosApp) in Xcode and run on a simulator or device —
   the build invokes Gradle to compile the shared Kotlin framework automatically.

## Security model

* No provider API key in any client, binary, or this repo's history — ever.
* Clients authenticate to the proxy with a shared secret header (`X-BuddyVoice-Proxy-Key`);
  the proxy mints ephemeral (~5 min) provider tokens and the client connects to the provider
  directly. This gate stops casual scraping; add real user auth before production use.
* `gitleaks` runs in CI on every push, and `scripts/pre-commit` runs it locally
  (`git config core.hooksPath scripts` to enable).

## Building

```shell
./gradlew build                          # everything buildable on your OS
./gradlew :androidApp:assembleDebug      # Android sample
./gradlew :desktopApp:run                # Desktop sample (template demo until Phase 4)
./gradlew :voiceagent-provider-grok:jvmTest   # wire-protocol mapping tests
```

Web sample: `./gradlew :webApp:jsBrowserDevelopmentRun`, then either pass
`?proxyUrl=...&proxyKey=...` as query params or copy
[webApp/src/jsMain/resources/local.config.example.js](./webApp/src/jsMain/resources/local.config.example.js)
to `local.config.js` (gitignored) next to it. Chrome/Edge/Firefox use an
`AudioWorklet` for capture; the mic prompt appears on the first orb tap.

## License

[MIT](LICENSE)

# desktopApp

Desktop (JVM) sample app for BuddyVoice — the Phase 4 voice demo. Wires
`AudioEngine` (javax.sound.sampled) + `GrokVoiceAgentProvider` into the shared
`VoiceAgentScreen` via `DesktopVoiceAgentController`, mirroring the Android sample.

## Run

```bash
./gradlew :desktopApp:run
```

## Proxy configuration (runtime-only, never baked in)

The app talks to your own [server-proxy](../server-proxy) — no provider API key
ever reaches the client. Settings are resolved **at app startup**, in this order:

1. **`local.properties`** at the repo root (gitignored) — same keys the Android app uses:

   ```properties
   buddyvoice.proxyBaseUrl=https://your-proxy.workers.dev
   buddyvoice.proxyKey=YOUR_SHARED_SECRET_HERE
   ```

2. **Environment variables** — the only option for a packaged app launched
   outside the repo:

   ```bash
   BUDDYVOICE_PROXY_BASE_URL=https://your-proxy.workers.dev \
   BUDDYVOICE_PROXY_KEY=YOUR_SHARED_SECRET_HERE \
   ./gradlew :desktopApp:run
   ```

Unlike the Android app (which injects these via `BuildConfig` at compile time),
the desktop app reads them at runtime on purpose: a distributable
(`packageDistributionForCurrentOS`) must never contain your proxy URL or shared
secret. If nothing is configured, the app starts with the template demo screen
and prints a hint to stderr.

## Packaging

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

Recipients configure their own proxy via the `BUDDYVOICE_*` environment variables.

## Microphone permission (macOS)

macOS gates the microphone per app. When running via Gradle, grant the terminal
(or IDE) microphone access under System Settings → Privacy & Security →
Microphone the first time capture starts.

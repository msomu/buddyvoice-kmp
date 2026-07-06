# ADR 0001: `api` visibility for dependencies that appear in public signatures

**Status:** Accepted
**Date:** 2026-07-07 (Phase 5)
**Modules touched:** `voiceagent-transport`, `voiceagent-provider-grok` (build scripts only — no Kotlin source or interface changed)

## Context

Phase 5 adds the first Kotlin/JS *executable* (`webApp`). Loading it in a browser
crashed at startup:

```
IrLinkageError: Constructor 'RealtimeClient.<init>' can not be called:
No constructor found for symbol
'com.msomu.buddyvoice.voiceagent.transport/RealtimeClient.<init>|<init>(io.ktor.client.engine.HttpClientEngineFactory){}[0]'
```

`RealtimeClient`'s public constructor takes `HttpClientEngineFactory<*>`, but
`voiceagent-transport` declared Ktor as `implementation`. On JVM/Android this is
survivable — binary references resolve at runtime, so Phase 1 never noticed. In
the Kotlin/JS klib world it is not: when `voiceagent-provider-grok` compiles
against the transport klib without Ktor on its compile classpath, the reference
to that constructor cannot be resolved, and partial linkage replaces the call
site (`RealtimeClient()` as a default argument) with a runtime `IrLinkageError`
stub. The same failure repeats one level up: `GrokVoiceAgentProvider`'s public
constructor takes a `RealtimeClient`, so consumers of the provider must be able
to resolve the transport klib too.

## Decision

Types that appear in a module's public API surface must come from `api`
dependencies:

- `voiceagent-transport`: `ktor-client-core` `implementation` → `api`
  (`HttpClientEngine`/`HttpClientEngineFactory` are public constructor params).
- `voiceagent-provider-grok`: `projects.voiceagentTransport` `implementation` →
  `api` (`RealtimeClient` is a public constructor param).

## Alternatives considered

- **Patch consumers**: add Ktor + transport directly to `webApp`'s (and every
  future JS consumer's) dependencies. Rejected: the unresolved symbol is baked
  into each intermediate klib at compile time, so the fix has to happen in the
  module that owns the public signature — consumer-side additions are
  whack-a-mole and would leak into the published artifacts' usage docs.
- **Hide the types**: make the engine/client constructor params `internal` and
  expose factory functions without Ktor types. Rejected for this phase: it is a
  breaking API change to published modules for zero caller benefit — injecting
  an engine (tests use Ktor's `MockEngine`) is a supported, documented use case.

## Consequences

- No interface, wire-protocol, or behavior change on any platform; Android,
  JVM and iOS compile output is unchanged in practice.
- Ktor's client-core API becomes part of `buddyvoice-transport`'s advertised
  (Maven `compile` scope) surface, and `buddyvoice-transport` part of
  `buddyvoice-provider-grok`'s — which is truthful: they already were part of
  the public signatures.
- Rule for future provider/transport modules: if a public declaration mentions
  a type from a dependency, that dependency is `api`. Kotlin/JS enforces what
  the JVM merely forgives.

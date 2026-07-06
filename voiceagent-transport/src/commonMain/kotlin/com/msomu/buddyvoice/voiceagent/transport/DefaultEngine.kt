package com.msomu.buddyvoice.voiceagent.transport

import io.ktor.client.engine.HttpClientEngineFactory

/** The Ktor engine used by [RealtimeClient] when none is injected. */
internal expect fun defaultEngine(): HttpClientEngineFactory<*>

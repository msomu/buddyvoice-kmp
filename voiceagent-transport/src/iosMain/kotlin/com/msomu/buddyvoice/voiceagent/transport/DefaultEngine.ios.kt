package com.msomu.buddyvoice.voiceagent.transport

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun defaultEngine(): HttpClientEngineFactory<*> = Darwin

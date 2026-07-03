package com.msomu.buddyvoice.voiceagent.transport

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun defaultEngine(): HttpClientEngineFactory<*> = OkHttp

package com.msomu.buddyvoice.voiceagent.transport

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

internal actual fun defaultEngine(): HttpClientEngineFactory<*> = Js

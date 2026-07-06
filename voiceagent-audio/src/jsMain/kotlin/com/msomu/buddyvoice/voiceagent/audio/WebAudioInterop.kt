package com.msomu.buddyvoice.voiceagent.audio

import kotlin.js.Promise
import org.khronos.webgl.Float32Array
import org.w3c.dom.MessagePort
import org.w3c.dom.mediacapture.MediaStream

/*
 * Minimal hand-written Web Audio API declarations. The Kotlin/JS stdlib covers
 * DOM and getUserMedia but not Web Audio, and pulling a wrappers library into a
 * published artifact for a handful of externals is not worth the dependency.
 * Only the members AudioEngine actually uses are declared.
 */

internal external class AudioContext {
    val sampleRate: Float
    val currentTime: Double
    val state: String
    val destination: AudioNode
    val audioWorklet: AudioWorklet
    fun resume(): Promise<*>
    fun close(): Promise<*>
    fun createBuffer(numberOfChannels: Int, length: Int, sampleRate: Float): AudioBuffer
    fun createBufferSource(): AudioBufferSourceNode
    fun createMediaStreamSource(mediaStream: MediaStream): AudioNode
    fun createScriptProcessor(
        bufferSize: Int,
        numberOfInputChannels: Int,
        numberOfOutputChannels: Int,
    ): ScriptProcessorNode

    fun createGain(): GainNode
}

internal open external class AudioNode {
    fun connect(destination: AudioNode): AudioNode
    fun disconnect()
}

internal external class GainNode : AudioNode {
    val gain: AudioParam
}

internal external class AudioParam {
    var value: Float
}

internal external class AudioBuffer {
    fun copyToChannel(source: Float32Array, channelNumber: Int)
    fun getChannelData(channel: Int): Float32Array
}

internal external class AudioBufferSourceNode : AudioNode {
    var buffer: AudioBuffer?
    var onended: (() -> Unit)?
    fun start(`when`: Double = definedExternally)
    fun stop()
}

internal external class ScriptProcessorNode : AudioNode {
    var onaudioprocess: ((AudioProcessingEvent) -> Unit)?
}

internal external class AudioProcessingEvent {
    val inputBuffer: AudioBuffer
}

internal external class AudioWorklet {
    fun addModule(moduleURL: String): Promise<*>
}

internal external class AudioWorkletNode(context: AudioContext, name: String) : AudioNode {
    val port: MessagePort
}

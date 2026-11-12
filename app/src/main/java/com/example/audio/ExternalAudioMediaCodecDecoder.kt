package com.example.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

interface AudioDecoderCallback {
    fun onAudioFormatChanged(sampleRate: Int, channelCount: Int)
    fun onPcmDataDecoded(pcmData: ByteArray, presentationTimeUs: Long)
    fun onDecoderError(error: Exception)
}

class ExternalAudioMediaCodecDecoder(
    private val callback: AudioDecoderCallback
) {
    private val tag = "SONIXAudioDecoder"
    private var codec: MediaCodec? = null
    private var isConfigured = false
    private var currentSampleRate = 0
    private var currentChannelCount = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun configure(sampleRate: Int, channelCount: Int, csd0: ByteArray): Boolean {
        if (isConfigured && currentSampleRate == sampleRate && currentChannelCount == channelCount) {
            return true
        }

        release()

        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AAC-LC
            }

            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder.configure(format, null, null, 0)
            decoder.start()

            codec = decoder
            currentSampleRate = sampleRate
            currentChannelCount = channelCount
            isConfigured = true
            callback.onAudioFormatChanged(sampleRate, channelCount)
            Log.i(tag, "Configured AAC MediaCodec: ${sampleRate}Hz, ${channelCount}ch")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to configure MediaCodec", e)
            callback.onDecoderError(e)
            false
        }
    }

    @Synchronized
    fun decodeFrame(adtsFrame: AdtsFrame, presentationTimeUs: Long = 0L) {
        val decoder = codec ?: return
        if (!isConfigured) return

        try {
            // 1. Submit input frame
            val inIndex = decoder.dequeueInputBuffer(10000L) // 10ms timeout
            if (inIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(adtsFrame.frameData)
                    decoder.queueInputBuffer(
                        inIndex,
                        0,
                        adtsFrame.frameData.size,
                        presentationTimeUs,
                        0
                    )
                }
            }

            // 2. Drain output buffers
            drainOutputBuffers(decoder)
        } catch (e: Exception) {
            Log.e(tag, "Error during audio decoding", e)
            callback.onDecoderError(e)
        }
    }

    private fun drainOutputBuffers(decoder: MediaCodec) {
        while (true) {
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            when {
                outIndex >= 0 -> {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val pcmBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmBytes)
                        callback.onPcmDataDecoded(pcmBytes, bufferInfo.presentationTimeUs)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = decoder.outputFormat
                    val newSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    Log.i(tag, "MediaCodec format changed: ${newSampleRate}Hz, ${newChannels}ch")
                    currentSampleRate = newSampleRate
                    currentChannelCount = newChannels
                    callback.onAudioFormatChanged(newSampleRate, newChannels)
                }
                else -> break
            }
        }
    }

    @Synchronized
    fun flush() {
        try {
            codec?.flush()
        } catch (e: Exception) {
            Log.w(tag, "Failed to flush MediaCodec", e)
        }
    }

    @Synchronized
    fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        isConfigured = false
    }
}

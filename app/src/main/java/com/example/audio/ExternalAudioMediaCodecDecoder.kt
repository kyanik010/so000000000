package com.example.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

interface AudioDecoderCallback {
    fun onAudioFormatChanged(sampleRate: Int, channelCount: Int)
    fun onPcmDataDecoded(pcmData: ByteArray, presentationTimeUs: Long)
    fun onDecoderError(error: Exception)
}

class ExternalAudioMediaCodecDecoder(private val callback: AudioDecoderCallback) {
    private val tag = "SONIXAudioDecoder"
    private var codec: MediaCodec? = null
    private var configuredSignature: String? = null
    private val info = MediaCodec.BufferInfo()

    @Synchronized fun configure(format: MediaFormat): Boolean {
        return try {
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("مسار الصوت لا يحدد نوع الترميز")
            require(mime.startsWith("audio/")) { "المسار المحدد ليس صوتياً: $mime" }
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val signature = buildString {
                append(mime).append('|').append(rate).append('|').append(channels)
                for (key in listOf("csd-0", "csd-1", "csd-2")) {
                    if (format.containsKey(key)) {
                        val buffer = format.getByteBuffer(key)?.duplicate()
                        if (buffer != null) {
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            append('|').append(bytes.contentHashCode())
                        }
                    }
                }
            }
            if (codec != null && configuredSignature == signature) return true
            releaseCodec()
            val created = MediaCodec.createDecoderByType(mime)
            created.configure(format, null, null, 0)
            created.start()
            codec = created
            configuredSignature = signature
            callback.onAudioFormatChanged(rate, channels)
            Log.i(tag, "Configured audio decoder $mime at ${rate}Hz / ${channels}ch")
            true
        } catch (error: Exception) {
            Log.e(tag, "MediaCodec configuration failed", error)
            callback.onDecoderError(error)
            false
        }
    }

    @Synchronized fun decodeSample(data: ByteArray, ptsUs: Long) {
        val active = codec ?: throw IllegalStateException("مفكك ترميز الصوت غير مهيأ")
        require(data.isNotEmpty()) { "عينة صوت فارغة" }
        try {
            var index = active.dequeueInputBuffer(10_000L)
            if (index < 0) {
                drain(active)
                index = active.dequeueInputBuffer(10_000L)
            }
            if (index < 0) throw IllegalStateException("انتهت مهلة إدخال عينة الصوت إلى MediaCodec")
            val input = active.getInputBuffer(index)
                ?: throw IllegalStateException("مخزن إدخال MediaCodec غير متاح")
            input.clear()
            if (data.size > input.remaining()) {
                active.queueInputBuffer(index, 0, 0, ptsUs, 0)
                throw IllegalArgumentException("عينة الصوت أكبر من مخزن MediaCodec")
            }
            input.put(data)
            active.queueInputBuffer(index, 0, data.size, ptsUs, 0)
            drain(active)
        } catch (error: Exception) {
            Log.e(tag, "Compressed audio sample decode failed", error)
            callback.onDecoderError(error)
            throw error
        }
    }

    private fun drain(active: MediaCodec) {
        while (true) {
            when (val index = active.dequeueOutputBuffer(info, 0L)) {
                in 0..Int.MAX_VALUE -> {
                    try {
                        val output = active.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val pcm = ByteArray(info.size)
                            output.get(pcm)
                            callback.onPcmDataDecoded(pcm, info.presentationTimeUs)
                        }
                    } finally {
                        active.releaseOutputBuffer(index, false)
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = active.outputFormat
                    callback.onAudioFormatChanged(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                }
                else -> return
            }
        }
    }

    @Synchronized fun release() = releaseCodec()
    private fun releaseCodec() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        configuredSignature = null
    }
}

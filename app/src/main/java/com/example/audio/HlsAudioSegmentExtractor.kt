package com.example.audio

import android.media.MediaExtractor
import android.media.MediaDataSource
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Uses Android's container demuxer for each HLS segment and submits only compressed audio
 * samples to MediaCodec. Video tracks are never selected or decoded.
 */
class HlsAudioSegmentExtractor(private val decoder: ExternalAudioMediaCodecDecoder) {
    fun decode(segmentUri: String): Int {
        val extractor = MediaExtractor()
        var count = 0
        try {
            extractor.setDataSource(segmentUri, mapOf("User-Agent" to "SONIX-TV/1.0"))
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    audioFormat = format
                    break
                }
            }
            val format = audioFormat
                ?: throw IllegalArgumentException("مقطع HLS لا يحتوي على مسار صوت")
            if (!decoder.configure(format)) {
                throw IllegalStateException("تعذرت تهيئة MediaCodec لمسار الصوت")
            }
            extractor.selectTrack(audioTrack)
            val declaredMax = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val sampleBuffer = ByteBuffer.allocateDirect(
                maxOf(256 * 1024, declaredMax).coerceAtMost(4 * 1024 * 1024)
            )
            while (true) {
                sampleBuffer.clear()
                val size = extractor.readSampleData(sampleBuffer, 0)
                if (size < 0) break
                if (size > sampleBuffer.capacity()) {
                    throw IllegalArgumentException("عينة صوت تتجاوز حجم المخزن المدعوم")
                }
                val sample = ByteArray(size)
                sampleBuffer.position(0)
                sampleBuffer.get(sample, 0, size)
                decoder.decodeSample(sample, extractor.sampleTime.coerceAtLeast(0L))
                count++
                if (!extractor.advance()) break
            }
            if (count == 0) throw IllegalArgumentException("مقطع HLS لا يحتوي عينات صوت قابلة للفك")
            return count
        } finally {
            extractor.release()
            source.close()
        }
    }

    private class ByteArraySegmentDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = data.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun close() = Unit
    }
}

package com.example.audio

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Demuxes a single downloaded HLS segment using Android's platform extractor.
 * Only the audio track is selected and sent as compressed samples to MediaCodec.
 */
class HlsAudioSegmentExtractor(private val decoder: ExternalAudioMediaCodecDecoder) {
    fun decode(segmentBytes: ByteArray): Int {
        require(segmentBytes.isNotEmpty()) { "مقطع HLS فارغ" }
        val extractor = MediaExtractor()
        val source = ByteArraySegmentDataSource(segmentBytes)
        var count = 0
        try {
            extractor.setDataSource(source)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    audioFormat = candidate
                    break
                }
            }
            val format = audioFormat ?: throw IllegalArgumentException("مقطع HLS لا يحتوي على مسار صوت")
            if (!decoder.configure(format)) throw IllegalStateException("تعذرت تهيئة MediaCodec لمسار الصوت")
            extractor.selectTrack(audioTrack)
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocateDirect(maxOf(256 * 1024, maxInput).coerceAtMost(4 * 1024 * 1024))
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (size > buffer.capacity()) throw IllegalArgumentException("عينة صوت تتجاوز الحد المدعوم")
                val sample = ByteArray(size)
                buffer.position(0)
                buffer.get(sample, 0, size)
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

    private class ByteArraySegmentDataSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = bytes.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L) return -1
            if (position >= bytes.size.toLong()) return -1
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun close() = Unit
    }
}

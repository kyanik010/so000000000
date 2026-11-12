package com.example.audio

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class HlsAudioSegment(
    val uri: String,
    val durationSec: Double,
    val sequenceNumber: Long
)

data class HlsAudioMediaPlaylist(
    val targetDurationSec: Double,
    val mediaSequence: Long,
    val segments: List<HlsAudioSegment>,
    val isEndList: Boolean,
    val unsupportedReason: String? = null
)

object HlsAudioPlaylistParser {

    fun parseMediaPlaylist(baseUri: String, content: String): HlsAudioMediaPlaylist {
        var targetDuration = 5.0
        var mediaSequence = 0L
        var isEndList = false
        var unsupportedReason: String? = null
        val segments = mutableListOf<HlsAudioSegment>()

        var currentDuration = 0.0
        var currentSequence = 0L

        val lines = content.lines()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXT-X-KEY:") &&
                !line.substringAfter(":").contains("METHOD=NONE", ignoreCase = true)) {
                unsupportedReason = "تشفير HLS غير مدعوم في مسار الصوت الخارجي"
            } else if (line.startsWith("#EXT-X-MAP:")) {
                unsupportedReason = "مقاطع fMP4 التي تتطلب EXT-X-MAP غير مدعومة حالياً"
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                unsupportedReason = "مقاطع BYTERANGE غير مدعومة حالياً"
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = line.substringAfter(":").trim().toDoubleOrNull() ?: 5.0
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                currentSequence = mediaSequence
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                isEndList = true
            } else if (line.startsWith("#EXTINF:")) {
                val durationPart = line.substringAfter(":").substringBefore(",").trim()
                currentDuration = durationPart.toDoubleOrNull() ?: 0.0
            } else if (!line.startsWith("#")) {
                val segmentUri = HlsMasterPlaylistParser.resolveUri(baseUri, line)
                segments.add(
                    HlsAudioSegment(
                        uri = segmentUri,
                        durationSec = currentDuration,
                        sequenceNumber = currentSequence
                    )
                )
                currentSequence++
                currentDuration = 0.0
            }
        }

        return HlsAudioMediaPlaylist(
            targetDurationSec = targetDuration,
            mediaSequence = mediaSequence,
            segments = segments,
            isEndList = isEndList,
            unsupportedReason = unsupportedReason
        )
    }
}

class HlsAudioSegmentDownloader(
    private val userAgent: String = "SONIX-TV/1.0",
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000
) {

    fun downloadText(urlString: String): Result<String> {
        return try {
            val connection = createConnection(urlString)
            connection.requestMethod = "GET"
            connection.connect()
            val code = connection.responseCode
            if (code in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                Result.success(text)
            } else {
                connection.disconnect()
                Result.failure(Exception("HTTP error $code while loading $urlString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun downloadBytes(urlString: String): Result<ByteArray> {
        return try {
            val connection = createConnection(urlString)
            connection.requestMethod = "GET"
            connection.connect()
            val code = connection.responseCode
            if (code in 200..299) {
                val bytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()
                Result.success(bytes)
            } else {
                connection.disconnect()
                Result.failure(Exception("HTTP error $code while loading segment $urlString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "*/*")
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        return conn
    }
}

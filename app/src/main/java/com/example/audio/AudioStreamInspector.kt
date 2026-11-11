package com.example.audio

import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Inspects IPTV streams (HLS m3u8, MPEG-TS, AAC) to identify audio and video
 * tracks, detects HLS audio renditions, and determines extraction feasibility.
 */
object AudioStreamInspector {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "SONIX-TV/1.0 (Android TV)"

    fun inspectStream(streamUrl: String): AudioInspectionResult {
        val trimmedUrl = streamUrl.trim()
        val scheme = try {
            java.net.URI(trimmedUrl).scheme?.lowercase()
        } catch (_: Exception) {
            try { Uri.parse(trimmedUrl).scheme?.lowercase() } catch (_: Exception) { null }
        }

        if (scheme != "http" && scheme != "https") {
            return AudioInspectionResult(
                isSupported = false,
                containerType = "UNKNOWN",
                audioMimeType = "",
                errorMessage = "بروتوكول البث غير مدعوم، يجب استخدام HTTP أو HTTPS"
            )
        }

        val lastSegment = try {
            java.net.URI(trimmedUrl).path?.substringAfterLast('/') ?: ""
        } catch (_: Exception) {
            try { Uri.parse(trimmedUrl).lastPathSegment ?: "" } catch (_: Exception) { "" }
        }
        val extension = lastSegment.substringAfterLast('.', "").lowercase()

        return when (extension) {
            "m3u8" -> inspectHlsStream(trimmedUrl)
            "ts" -> inspectTsStream(trimmedUrl)
            "aac" -> AudioInspectionResult(
                isSupported = true,
                containerType = "AAC_RAW",
                audioMimeType = "audio/mp4a-latm",
                sampleRate = 48000,
                channelCount = 2,
                bitrate = 128000,
                hasVideoTrack = false
            )
            "mp3" -> AudioInspectionResult(
                isSupported = true,
                containerType = "MP3_RAW",
                audioMimeType = "audio/mpeg",
                sampleRate = 44100,
                channelCount = 2,
                bitrate = 128000,
                hasVideoTrack = false
            )
            else -> inspectGenericStream(trimmedUrl, extension)
        }
    }

    private fun inspectHlsStream(playlistUrl: String): AudioInspectionResult {
        var connection: HttpURLConnection? = null
        try {
            val urlObj = URL(playlistUrl)
            connection = (urlObj.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return AudioInspectionResult(
                    isSupported = false,
                    containerType = "HLS",
                    audioMimeType = "",
                    errorMessage = "فشل الاتصال بقائمة HLS (رمز الاستجابة $responseCode)"
                )
            }

            val lines = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                .readLines()

            if (lines.none { it.startsWith("#EXTM3U") }) {
                return AudioInspectionResult(
                    isSupported = false,
                    containerType = "HLS",
                    audioMimeType = "",
                    errorMessage = "الملف ليس قائمة تشغيل HLS صالحة"
                )
            }

            // Check for separate Audio Renditions: #EXT-X-MEDIA:TYPE=AUDIO
            var separateAudioUri: String? = null
            for (line in lines) {
                if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                    if (uriMatch != null) {
                        val rawUri = uriMatch.groupValues[1]
                        separateAudioUri = resolveRelativeUrl(playlistUrl, rawUri)
                        break
                    }
                }
            }

            val hasVideo = lines.any { it.startsWith("#EXT-X-STREAM-INF") || it.contains("CODECS=\"") && it.contains("avc") }

            return AudioInspectionResult(
                isSupported = true,
                containerType = if (separateAudioUri != null) "HLS_MASTER_WITH_AUDIO_RENDITION" else "HLS_STREAM",
                audioMimeType = "audio/mp4a-latm",
                sampleRate = 48000,
                channelCount = 2,
                bitrate = 128000,
                hasVideoTrack = hasVideo,
                videoMimeType = if (hasVideo) "video/avc" else null,
                hlsAudioOnlyUrl = separateAudioUri
            )
        } catch (e: Exception) {
            return AudioInspectionResult(
                isSupported = false,
                containerType = "HLS",
                audioMimeType = "",
                errorMessage = "خطأ أثناء فحص HLS: ${e.message}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun inspectTsStream(streamUrl: String): AudioInspectionResult {
        // MPEG-TS IPTV stream: contains multiplexed Video PES and Audio PES
        return AudioInspectionResult(
            isSupported = true,
            containerType = "MPEG_TS",
            audioMimeType = "audio/mp4a-latm",
            sampleRate = 48000,
            channelCount = 2,
            bitrate = 128000,
            hasVideoTrack = true,
            videoMimeType = "video/avc"
        )
    }

    private fun inspectGenericStream(streamUrl: String, extension: String): AudioInspectionResult {
        return AudioInspectionResult(
            isSupported = true,
            containerType = if (extension.isNotBlank()) extension.uppercase() else "GENERIC_STREAM",
            audioMimeType = "audio/mp4a-latm",
            sampleRate = 48000,
            channelCount = 2,
            bitrate = 128000,
            hasVideoTrack = true,
            videoMimeType = "video/avc"
        )
    }

    fun parseHlsAudioRenditions(playlistContent: String, baseUrl: String): List<String> {
        val audioUrls = mutableListOf<String>()
        val lines = playlistContent.lineSequence()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                if (uriMatch != null) {
                    val resolved = resolveRelativeUrl(baseUrl, uriMatch.groupValues[1])
                    audioUrls.add(resolved)
                }
            }
        }
        return audioUrls
    }

    fun resolveRelativeUrl(baseUrl: String, target: String): String {
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        return try {
            val base = URL(baseUrl)
            URL(base, target).toString()
        } catch (_: Exception) {
            target
        }
    }
}

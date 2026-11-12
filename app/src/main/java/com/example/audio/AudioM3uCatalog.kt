package com.example.audio

import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads only the M3U text playlist (never channel media segments), extracts
 * beIN/Alwan entries, and persists the small channel index for offline reuse.
 */
class AudioM3uCatalog(context: Context) {
    private val app = context.applicationContext
    private val cacheFile = File(app.filesDir, "sonix_audio_sources_v1.json")

    fun loadCached(): JSONObject {
        return try {
            if (!cacheFile.isFile || cacheFile.length() <= 0L) {
                return JSONObject().put("success", false).put("cached", false)
                    .put("error", "لا توجد مصادر صوت محفوظة بعد")
            }
            val cached = JSONObject(cacheFile.readText(Charsets.UTF_8))
            val channels = cached.optJSONArray("channels") ?: JSONArray()
            if (channels.length() == 0) {
                JSONObject().put("success", false).put("cached", false)
                    .put("error", "لا توجد قنوات صوت محفوظة")
            } else {
                cached.put("success", true).put("cached", true)
            }
        } catch (_: Exception) {
            JSONObject().put("success", false).put("cached", false)
                .put("error", "تعذر قراءة ذاكرة مصادر الصوت")
        }
    }

    fun sync(m3uUrl: String, updatedAt: String): JSONObject {
        val url = m3uUrl.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return JSONObject().put("success", false).put("error", "رابط قائمة الصوت غير مضبوط في لوحة SONIX")
        }
        val existing = loadCached()
        if (existing.optBoolean("success") &&
            existing.optString("updated_at") == updatedAt &&
            existing.optString("source_hash") == sha256(url) &&
            existing.optJSONArray("channels")?.length()?.let { it > 0 } == true
        ) {
            return existing.put("cached", true)
        }

        return try {
            val channels = downloadAndParse(url)
            if (channels.length() == 0) {
                throw IllegalStateException("لم يتم العثور على قنوات beIN SPORTS أو ALWAN داخل قائمة M3U")
            }
            val payload = JSONObject()
                .put("success", true)
                .put("cached", false)
                .put("updated_at", updatedAt)
                .put("source_hash", sha256(url))
                .put("count", channels.length())
                .put("channels", channels)
            persist(payload)
            payload
        } catch (error: Exception) {
            val fallback = loadCached()
            if (fallback.optBoolean("success")) {
                fallback.put("cached", true)
                    .put("warning", "تعذر تحديث القائمة؛ تم الاحتفاظ بالمصادر المحفوظة")
            } else {
                JSONObject().put("success", false)
                    .put("error", error.message ?: "تعذر مزامنة قائمة M3U")
            }
        }
    }

    private fun downloadAndParse(rawUrl: String): JSONArray {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SONIX-TV/1.0")
            setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("فشل جلب قائمة M3U (HTTP $code)")
            val result = JSONArray()
            var pendingInfo: String? = null
            var pendingGroup = ""
            var lineCount = 0
            var channelNumber = 0
            var totalChars = 0

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val rawLine = reader.readLine() ?: break
                    lineCount++
                    totalChars += rawLine.length
                    if (totalChars > 128 * 1024 * 1024) throw IllegalStateException("حجم قائمة M3U تجاوز الحد الآمن (128 ميغابايت)")
                    val line = rawLine.trim().removePrefix("\uFEFF")
                    if (line.isEmpty()) continue

                    when {
                        line.startsWith("#EXTINF", true) -> {
                            pendingInfo = line
                            pendingGroup = attribute(line, "group-title")
                        }
                        line.startsWith("#EXTGRP:", true) -> {
                            pendingGroup = line.substringAfter(':').trim()
                        }
                        line.startsWith("#") -> Unit
                        pendingInfo != null -> {
                            val absoluteUrl = try { URL(URL(rawUrl), line).toString() } catch (_: Exception) { "" }
                            val uri = try { URL(absoluteUrl) } catch (_: Exception) { null }
                            if (uri != null && (uri.protocol == "http" || uri.protocol == "https") && uri.host.isNotBlank()) {
                                val info = pendingInfo.orEmpty()
                                val displayName = info.substringAfterLast(',', "").trim()
                                val name = attribute(info, "tvg-name").ifBlank { displayName }
                                val group = pendingGroup.ifBlank { attribute(info, "group-title") }
                                val classification = AudioSourceClassifier.classify(name, group)
                                if (classification != AudioChannelGroup.OTHER) {
                                    channelNumber++
                                    val extension = uri.path.substringAfterLast('.', "").lowercase()
                                        .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "m3u8"
                                    result.put(JSONObject()
                                        .put("id", absoluteUrl)
                                        .put("url", absoluteUrl)
                                        .put("name", name.ifBlank { "قناة صوتية $channelNumber" })
                                        .put("number", attribute(info, "tvg-chno").ifBlank { channelNumber.toString() })
                                        .put("group", classification.code)
                                        .put("categoryName", group.ifBlank { classification.displayName })
                                        .put("categoryId", group)
                                        .put("containerExtension", extension)
                                    )
                                }
                            }
                            pendingInfo = null
                            pendingGroup = ""
                        }
                    }
                    if (lineCount > 250000) throw IllegalStateException("عدد أسطر قائمة M3U تجاوز الحد المسموح")
                }
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun attribute(line: String, name: String): String {
        val match = Regex("""(?:^|\s)$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun persist(payload: JSONObject) {
        val parent = cacheFile.parentFile ?: throw IllegalStateException("تعذر تحديد مجلد ذاكرة الصوت")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد ذاكرة الصوت")
        val temp = File(parent, cacheFile.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, cacheFile.absolutePath)
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

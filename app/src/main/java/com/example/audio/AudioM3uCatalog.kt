package com.example.audio

import android.content.Context
import android.system.Os
import com.example.NetworkSecurityValidator
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
            val cached = readCachePayload()
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
        val connection = openSafeM3uConnection(rawUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("فشل جلب قائمة M3U (HTTP $code)")
            val result = JSONArray()
            var pendingInfo: String? = null
            var pendingGroup = ""
            var channelNumber = 0
            var totalChars = 0
            val validatedStreamHosts = HashSet<String>()

            val responseStream: InputStream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(connection.inputStream, 64 * 1024)
            } else {
                connection.inputStream
            }
            BufferedReader(InputStreamReader(responseStream, Charsets.UTF_8), 64 * 1024).use { reader ->
                while (true) {
                    val rawLine = reader.readLine() ?: break
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
                            val info = pendingInfo.orEmpty()
                            val displayName = info.substringAfterLast(',', "").trim()
                            val name = attribute(info, "tvg-name").ifBlank { displayName }
                            val group = pendingGroup.ifBlank { attribute(info, "group-title") }
                            val classification = AudioSourceClassifier.classify(name, group)

                            // Filter unrelated IPTV entries before the expensive URL parsing.
                            if (classification != AudioChannelGroup.OTHER) {
                                val absoluteUrl = try { URL(URL(rawUrl), line).toString() } catch (_: Exception) { "" }
                                val uri = try { URL(absoluteUrl) } catch (_: Exception) { null }
                                if (uri != null && (uri.protocol == "http" || uri.protocol == "https") && uri.host.isNotBlank()) {
                                    if (validatedStreamHosts.add(uri.host.lowercase())) {
                                        NetworkSecurityValidator.assertSafeUrl(absoluteUrl)
                                    }
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
                }
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Follow M3U redirects manually so every destination is checked before a request.
     * HTTP remains supported for IPTV providers; only unsafe local/metadata targets are blocked.
     */
    private fun openSafeM3uConnection(rawUrl: String): HttpURLConnection {
        var current = URL(rawUrl)
        repeat(6) { redirectIndex ->
            NetworkSecurityValidator.assertSafeUrl(current.toString())
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 25000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SONIX-TV/1.0")
                setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val status = try { connection.responseCode } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("أعاد خادم قائمة M3U تحويلاً دون وجهة")
                if (redirectIndex == 5) throw IllegalStateException("تجاوزت قائمة M3U عدد التحويلات المسموح")
                current = URL(current, location)
            } else {
                return connection
            }
        }
        throw IllegalStateException("تعذر فتح قائمة M3U بعد التحويلات")
    }

    private fun attribute(line: String, name: String): String {
        val match = Regex("""(?:^|\s)$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    /** Read encrypted cache; transparently migrate the previous plaintext JSON once. */
    private fun readCachePayload(): JSONObject {
        val raw = JSONObject(cacheFile.readText(Charsets.UTF_8))
        if (raw.optInt("_sonix_encrypted_version") == 1) {
            val iv = Base64.decode(raw.getString("iv"), Base64.NO_WRAP)
            val encrypted = Base64.decode(raw.getString("ciphertext"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateCacheKey(), GCMParameterSpec(128, iv))
            return JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        }

        // Legacy cache remains readable, but is immediately rewritten using Keystore AES-GCM.
        try {
            persist(raw)
        } catch (_: Exception) {
            // Keep the current session functional if migration is temporarily unavailable.
        }
        return raw
    }

    private fun persist(payload: JSONObject) {
        val parent = cacheFile.parentFile ?: throw IllegalStateException("تعذر تحديد مجلد ذاكرة الصوت")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد ذاكرة الصوت")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCacheKey())
        val envelope = JSONObject()
            .put("_sonix_encrypted_version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))

        val temp = File(parent, cacheFile.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(envelope.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, cacheFile.absolutePath)
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    private fun getOrCreateCacheKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(CACHE_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                CACHE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val CACHE_KEY_ALIAS = "sonix_audio_catalog_v1"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

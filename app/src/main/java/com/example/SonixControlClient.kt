package com.example

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Lightweight SONIX CONTROL sync. Uses a stable installation ID and the limited
 * sonix-device-api endpoint; it never touches IPTV credentials or playback.
 */
class SonixControlClient(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sonix_control_identity", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val deviceUid: String by lazy {
        prefs.getString("device_uid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_uid", it).apply()
        }
    }

    @Volatile var onSync: ((JSONObject) -> Unit)? = null
    @Volatile var onAudioSourcesSync: ((JSONObject) -> Unit)? = null
    @Volatile var onUpdateCheck: ((JSONObject) -> Unit)? = null
    private val audioM3uCatalog by lazy { com.example.audio.AudioM3uCatalog(app) }

    fun sync() {
        executor.execute {
            try {
                val result = request(JSONObject()
                    .put("action", "sync")
                    .put("device_uid", deviceUid)
                    .put("device_name", Build.MODEL ?: "Android")
                    .put("platform", if (app.packageManager.hasSystemFeature("android.software.leanback")) "android_tv" else "android")
                    .put("manufacturer", Build.MANUFACTURER ?: "")
                    .put("model", Build.MODEL ?: "")
                    .put("os_version", Build.VERSION.RELEASE ?: "")
                    .put("app_version", app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "")
                    .put("app_version_code", currentVersionCode())
                    .put("app_abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    .put("app_abis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList())))
                onSync?.invoke(result)
                checkForAppUpdate()
            } catch (e: Exception) {
                android.util.Log.w("SONIXControl", "Control sync failed", e)
            }
        }
    }

    private fun currentVersionCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    private fun checkForAppUpdate() {
        try {
            val result = request(JSONObject()
                .put("action", "app.update.check")
                .put("device_uid", deviceUid)
                .put("device_name", Build.MODEL ?: "Android")
                .put("platform", if (app.packageManager.hasSystemFeature("android.software.leanback")) "android_tv" else "android")
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("model", Build.MODEL ?: "")
                .put("os_version", Build.VERSION.RELEASE ?: "")
                .put("app_version", app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "")
                .put("app_version_code", currentVersionCode())
                .put("app_abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put("channel", "stable"))
            if (result.optString("status") == "active") onUpdateCheck?.invoke(result)
        } catch (error: Exception) {
            android.util.Log.w("SONIXUpdate", "Update check failed; normal app startup continues", error)
        }
    }

    fun loadCachedAudioSources() {
        executor.execute {
            try { onAudioSourcesSync?.invoke(audioM3uCatalog.loadCached()) }
            catch (e: Exception) {
                onAudioSourcesSync?.invoke(JSONObject().put("success", false).put("error", e.message ?: "تعذر قراءة مصادر الصوت"))
            }
        }
    }

    fun syncAudioSources() {
        executor.execute {
            try {
                val config = request(JSONObject().put("action", "audio.sources").put("device_uid", deviceUid))
                if (config.optString("status") != "active") {
                    onAudioSourcesSync?.invoke(JSONObject().put("success", false).put("error", "الجهاز غير نشط"))
                    return@execute
                }
                val m3uUrl = config.optString("m3u_url", "")
                if (m3uUrl.isBlank()) {
                    onAudioSourcesSync?.invoke(JSONObject().put("success", false)
                        .put("error", "لم يضف المسؤول رابط الصوت M3U لهذا الجهاز بعد"))
                    return@execute
                }
                onAudioSourcesSync?.invoke(audioM3uCatalog.sync(m3uUrl, config.optString("updated_at", "")))
            } catch (e: Exception) {
                val cached = audioM3uCatalog.loadCached()
                if (cached.optBoolean("success")) {
                    cached.put("warning", "تعذر الاتصال بلوحة SONIX؛ تم تحميل المصادر المحفوظة")
                    onAudioSourcesSync?.invoke(cached)
                } else {
                    onAudioSourcesSync?.invoke(JSONObject().put("success", false)
                        .put("error", e.message ?: "تعذر مزامنة مصادر الصوت"))
                }
            }
        }
    }

    fun acknowledge(messageId: String) {
        executor.execute {
            try {
                request(JSONObject().put("action", "message.ack").put("device_uid", deviceUid).put("message_id", messageId))
            } catch (e: Exception) {
                android.util.Log.w("SONIXControl", "Message acknowledgement failed", e)
            }
        }
    }

    fun setTheme(themeId: String) {
        executor.execute {
            try {
                request(JSONObject().put("action", "theme.set").put("device_uid", deviceUid).put("theme_id", themeId))
            } catch (e: Exception) {
                android.util.Log.w("SONIXControl", "Theme selection failed", e)
            }
        }
    }

    private fun request(body: JSONObject): JSONObject {
        val connection = (URL("https://ganqdgstfriegfwainoz.supabase.co/functions/v1/sonix-device-api").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("apikey", "sb_publishable_yOn2frVZ8pJlzscXrzwZpw_8BeGXa1L")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    fun shutdown() { executor.shutdownNow() }
}

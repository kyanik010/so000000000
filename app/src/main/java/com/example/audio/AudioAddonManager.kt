package com.example.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SONIX audio-channel catalog only.
 *
 * Playback is handled directly by the dedicated Media3 ExoPlayer in MainActivity.
 * This manager must never inspect, demux, decode, encode, or repackage stream data.
 */
class AudioAddonManager(
    private val context: Context,
    private val xtreamBridge: com.example.XtreamBridge
) {
    private val discoveredChannels = CopyOnWriteArrayList<AudioChannelInfo>()
    @Volatile private var isAddonActive = false

    companion object {
        const val ADDON_ID = "com.sonix.addon.audio"
        const val ADDON_NAME = "SONIX AUDIO"
        const val ADDON_VERSION = "1.2.0"

        @Volatile private var instance: AudioAddonManager? = null

        fun getInstance(context: Context, xtreamBridge: com.example.XtreamBridge): AudioAddonManager {
            return instance ?: synchronized(this) {
                instance ?: AudioAddonManager(context.applicationContext, xtreamBridge).also { instance = it }
            }
        }
    }

    fun isAutoActivated(): Boolean = isAddonActive

    fun onSessionActivated() {
        isAddonActive = true
    }

    fun onSessionTerminated() {
        isAddonActive = false
        discoveredChannels.clear()
    }

    /** Finds candidate audio-channel names only; it never opens their stream URLs. */
    fun populateChannels(channelsJson: String): List<AudioChannelInfo> {
        val list = mutableListOf<AudioChannelInfo>()
        try {
            val array = JSONArray(channelsJson)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "")
                val categoryName = obj.optString("category_name", "")
                val categoryId = obj.optString("category_id", "")
                val streamId = obj.optString("stream_id", "")
                val num = obj.optString("num", (i + 1).toString())
                val group = AudioSourceClassifier.classify(name, categoryName, categoryId)
                if (group != AudioChannelGroup.OTHER && streamId.isNotBlank()) {
                    list.add(
                        AudioChannelInfo(
                            id = streamId,
                            name = name,
                            number = num,
                            group = group,
                            categoryName = categoryName,
                            categoryId = categoryId,
                            containerExtension = obj.optString("container_extension", "ts")
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        discoveredChannels.clear()
        discoveredChannels.addAll(list)
        return list
    }

    fun getDiscoveredChannels(): List<AudioChannelInfo> = discoveredChannels.toList()

    fun getAddonInfoJson(): String {
        return JSONObject()
            .put("id", ADDON_ID)
            .put("name", ADDON_NAME)
            .put("version", ADDON_VERSION)
            .put("description", "فهرسة قنوات الصوت المستقلة وتشغيلها مباشرة عبر Media3 دون تحويل أو إعادة ترميز")
            .put("icon", "audio_wave")
            .put("enabled", isAddonActive)
            .put("installed", true)
            .put("status", if (isAddonActive) "✓ مفعّلة وجاهزة" else "في انتظار تسجيل الدخول")
            .put("channelsCount", discoveredChannels.size)
            .put("playbackMode", "direct-media3")
            .put("transcoding", false)
            .toString()
    }

    fun shutdown() {
        discoveredChannels.clear()
        isAddonActive = false
    }
}

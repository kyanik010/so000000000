package com.example.audio

import android.content.Context
import com.example.XtreamBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Top-level manager for the SONIX AUDIO ADDON.
 *
 * Integrated into SONIX TV APK, binds to the customer's active IPTV session,
 * auto-activates upon login/restoreSession, and supplies verified independent
 * audio streams to Media3.
 */
class AudioAddonManager(
    private val context: Context,
    private val xtreamBridge: XtreamBridge
) {

    private val sessionManager = AudioSessionManager()
    private val discoveredChannels = CopyOnWriteArrayList<AudioChannelInfo>()
    @Volatile private var isAddonActive = false
    @Volatile private var currentExtractionState = AudioExtractionState.IDLE
    @Volatile private var lastErrorCode: AudioErrorCode? = null

    companion object {
        const val ADDON_ID = "com.sonix.addon.audio"
        const val ADDON_NAME = "SONIX AUDIO"
        const val ADDON_VERSION = "1.1.0"

        @Volatile private var instance: AudioAddonManager? = null

        fun getInstance(context: Context, xtreamBridge: XtreamBridge): AudioAddonManager {
            return instance ?: synchronized(this) {
                instance ?: AudioAddonManager(context.applicationContext, xtreamBridge).also { instance = it }
            }
        }
    }

    fun isAutoActivated(): Boolean = isAddonActive

    /**
     * Called automatically when customer logs in or restores their session.
     */
    fun onSessionActivated() {
        isAddonActive = true
        currentExtractionState = AudioExtractionState.IDLE
        lastErrorCode = null
    }

    /**
     * Called when customer logs out or switches accounts.
     */
    fun onSessionTerminated() {
        isAddonActive = false
        currentExtractionState = AudioExtractionState.STOPPED
        lastErrorCode = null
        discoveredChannels.clear()
        sessionManager.stopCurrentSession()
    }

    /**
     * Discovers beIN SPORTS and ALWAN audio candidates from provider live channel list.
     */
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

    /**
     * Requests independent audio extraction:
     * Resolves the real Xtream stream URL in native layer (never exposing password to JS),
     * analyzes stream, decodes to PCM, eliminates video, and produces verified local loopback audio URI.
     */
    fun startIndependentAudio(
        streamId: String,
        extension: String,
        onStateChange: (state: AudioExtractionState, message: String, errorCode: AudioErrorCode?, localAudioUrl: String?) -> Unit
    ): String {
        currentExtractionState = AudioExtractionState.STREAM_OPENING
        lastErrorCode = null
        onStateChange(AudioExtractionState.STREAM_OPENING, "جارٍ تجهيز مسار الصوت المستقل...", null, null)

        val playbackUri = try {
            xtreamBridge.buildPlaybackUri("live", streamId, extension)
        } catch (e: Exception) {
            currentExtractionState = AudioExtractionState.FAILED_GENERAL
            lastErrorCode = AudioErrorCode.SOURCE_AUTH_ERROR
            onStateChange(AudioExtractionState.FAILED_GENERAL, "تعذر الحصول على رابط البث من الاشتراك: ${e.message}", lastErrorCode, null)
            throw e
        }

        return sessionManager.startSession(
            streamId = streamId,
            sourceUrl = playbackUri.toString()
        ) { state, message, errCode, url ->
            currentExtractionState = state
            lastErrorCode = errCode
            onStateChange(state, message, errCode, url)
        }
    }

    /**
     * Backward compatible overload.
     */
    fun startIndependentAudio(
        streamId: String,
        extension: String,
        onStateChange: (AudioExtractionState, String) -> Unit
    ): String {
        return startIndependentAudio(streamId, extension) { state, message, _, _ ->
            onStateChange(state, message)
        }
    }

    fun stopIndependentAudio() {
        sessionManager.stopCurrentSession()
        currentExtractionState = AudioExtractionState.STOPPED
        lastErrorCode = null
    }

    fun getMetrics(): AudioEngineMetrics {
        val base = sessionManager.getSessionMetrics() ?: AudioEngineMetrics()
        return base.copy(
            lastState = currentExtractionState,
            lastErrorCode = lastErrorCode
        )
    }

    /**
     * Exposes SONIX AUDIO ADDON info to the Addons Marketplace / UI.
     */
    fun getAddonInfoJson(): String {
        return JSONObject()
            .put("id", ADDON_ID)
            .put("name", ADDON_NAME)
            .put("version", ADDON_VERSION)
            .put("description", "محرك داخلي لاستخراج وفك ترميز قنوات beIN SPORTS و ALWAN إلى موارد صوتية مستقلة")
            .put("icon", "audio_wave")
            .put("enabled", isAddonActive)
            .put("installed", true)
            .put("status", if (isAddonActive) "✓ مفعّلة وجاهزة" else "في انتظار تسجيل الدخول")
            .put("channelsCount", discoveredChannels.size)
            .put("currentState", currentExtractionState.code)
            .put("currentStateName", currentExtractionState.displayNameAr)
            .put("lastErrorCode", lastErrorCode?.code ?: "")
            .toString()
    }

    fun shutdown() {
        sessionManager.shutdown()
        discoveredChannels.clear()
        isAddonActive = false
    }
}

package com.example.audio

/**
 * State and data models for the SONIX AUDIO ADDON engine.
 */
enum class AudioExtractionState(val code: String, val displayNameAr: String) {
    IDLE("idle", "خامل"),
    CHANNEL_DISCOVERED("channel_discovered", "تم اكتشاف القناة"),
    STREAM_OPENING("stream_opening", "جارٍ فتح المصدر"),
    STREAM_INSPECTED("stream_inspected", "تم تحليل المسار الصوتي"),
    DECODING_PCM("decoding_pcm", "جارٍ فك ترميز الصوت إلى PCM"),
    OUTPUT_PRODUCING("output_producing", "جارٍ إنتاج المورد المستقل"),
    RESOURCE_READY("resource_ready", "المورد الصوتي المستقل جاهز"),
    PLAYING("playing", "يعمل"),
    STOPPED("stopped", "متوقف"),
    FAILED_INSPECTION("failed_inspection", "فشل فحص المصدر"),
    FAILED_DECODING("failed_decoding", "فشل فك ترميز الصوت"),
    FAILED_OUTPUT("failed_output", "فشل إنتاج المورد الصوتي"),
    FAILED_GENERAL("failed_general", "فشل الاتصال بالمصدر")
}

enum class AudioChannelGroup(val code: String, val displayName: String) {
    BEIN_SPORTS("BEIN_SPORTS", "beIN SPORTS"),
    ALWAN("ALWAN", "ALWAN"),
    OTHER("OTHER", "قناة أخرى")
}

data class AudioChannelInfo(
    val id: String,
    val name: String,
    val number: String = "",
    val group: AudioChannelGroup,
    val categoryName: String = "",
    val categoryId: String = "",
    val containerExtension: String = "ts"
)

data class AudioInspectionResult(
    val isSupported: Boolean,
    val containerType: String,
    val audioMimeType: String,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val bitrate: Int = 128000,
    val hasVideoTrack: Boolean = true,
    val videoMimeType: String? = null,
    val hlsAudioOnlyUrl: String? = null,
    val errorMessage: String? = null
)

data class AudioEngineMetrics(
    val pcmBytesProduced: Long = 0L,
    val pcmSamplesDecoded: Long = 0L,
    val encodedFramesCount: Long = 0L,
    val activeBitrate: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val audioCodec: String = "",
    val isHardwareAccelerated: Boolean = true,
    val lastState: AudioExtractionState = AudioExtractionState.IDLE,
    val lastError: String? = null
)

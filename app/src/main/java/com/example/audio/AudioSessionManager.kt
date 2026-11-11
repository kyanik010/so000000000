package com.example.audio

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages audio extraction sessions, ensuring single-session isolation,
 * thread-safe cleanup, and resource recycling.
 */
class AudioSessionManager {

    private val sessionExecutor = Executors.newSingleThreadExecutor()
    private val activeSession = AtomicReference<ActiveSession?>(null)

    data class ActiveSession(
        val sessionId: String,
        val streamId: String,
        val sourceUrl: String,
        val localAudioUrl: String,
        val outputEngine: AudioOnlyOutputEngine,
        val decoderEngine: AudioDecoderEngine,
        val workerFuture: Future<*>,
        var connection: HttpURLConnection? = null
    )

    fun startSession(
        streamId: String,
        sourceUrl: String,
        onStateChange: (AudioExtractionState, String) -> Unit
    ): String {
        // Stop any existing session first
        stopCurrentSession()

        val sessionId = UUID.randomUUID().toString().take(8)
        onStateChange(AudioExtractionState.STREAM_OPENING, "جارٍ الاتصال بمصدر القناة...")

        val outputEngine = AudioOnlyOutputEngine(sessionId = sessionId)
        val localAudioUrl = outputEngine.start()

        val decoderEngine = AudioDecoderEngine(
            onPcmDecoded = { pcmBytes, _, _, ptsUs ->
                outputEngine.ingestPcm(pcmBytes, ptsUs)
            },
            onError = { error ->
                onStateChange(AudioExtractionState.FAILED_DECODING, "خطأ أثناء فك ترميز الصوت: ${error.message}")
            }
        )

        val future = sessionExecutor.submit {
            runExtractionPipeline(streamId, sourceUrl, decoderEngine, onStateChange)
        }

        val session = ActiveSession(
            sessionId = sessionId,
            streamId = streamId,
            sourceUrl = sourceUrl,
            localAudioUrl = localAudioUrl,
            outputEngine = outputEngine,
            decoderEngine = decoderEngine,
            workerFuture = future
        )
        activeSession.set(session)

        onStateChange(AudioExtractionState.RESOURCE_READY, "المورد الصوتي المستقل جاهز للتشغيل")
        return localAudioUrl
    }

    private fun runExtractionPipeline(
        streamId: String,
        sourceUrl: String,
        decoderEngine: AudioDecoderEngine,
        onStateChange: (AudioExtractionState, String) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        try {
            onStateChange(AudioExtractionState.STREAM_INSPECTED, "تم فحص المصدر وعزل مسار الفيديو")
            onStateChange(AudioExtractionState.DECODING_PCM, "جارٍ فك ترميز الصوت إلى PCM")

            val url = URL(sourceUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "SONIX-TV/1.0 (Android TV)")
                instanceFollowRedirects = true
            }

            activeSession.get()?.connection = connection

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                onStateChange(AudioExtractionState.FAILED_GENERAL, "فشل الاتصال بالبث (HTTP $responseCode)")
                return
            }

            val inputStream: InputStream = connection.inputStream
            onStateChange(AudioExtractionState.OUTPUT_PRODUCING, "جارٍ إنتاج المورد الصوتي المستقل")

            decoderEngine.processStream(inputStream)
        } catch (e: InterruptedException) {
            // Cancelled cleanly
        } catch (e: Throwable) {
            onStateChange(AudioExtractionState.FAILED_GENERAL, "انقطع تدفق الصوت: ${e.message}")
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    fun stopCurrentSession() {
        val current = activeSession.getAndSet(null) ?: return
        try {
            current.workerFuture.cancel(true)
            current.connection?.disconnect()
            current.decoderEngine.stop()
            current.outputEngine.stop()
        } catch (_: Exception) {}
    }

    fun getCurrentLocalAudioUrl(): String? = activeSession.get()?.localAudioUrl

    fun getSessionMetrics(): AudioEngineMetrics? {
        val session = activeSession.get() ?: return null
        val decMetrics = session.decoderEngine.getMetrics()
        val outMetrics = session.outputEngine.getMetrics()
        return AudioEngineMetrics(
            pcmBytesProduced = decMetrics.pcmBytesProduced,
            pcmSamplesDecoded = decMetrics.pcmSamplesDecoded,
            encodedFramesCount = outMetrics.encodedFramesCount,
            sampleRate = decMetrics.sampleRate,
            channelCount = decMetrics.channelCount,
            audioCodec = decMetrics.audioCodec
        )
    }

    fun shutdown() {
        stopCurrentSession()
        sessionExecutor.shutdownNow()
    }
}

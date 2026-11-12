package com.example.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

interface ExternalAudioEventListener {
    fun onExternalAudioBuffering()
    fun onExternalAudioPlaying()
    fun onExternalAudioFirstPcmVerified()
    fun onExternalAudioError(message: String, willRetry: Boolean)
    fun onExternalAudioDetached()
}

class ExternalAudioController(
    private val listener: ExternalAudioEventListener
) : AudioDecoderCallback, AudioTrackPlaybackListener {

    private val tag = "SONIXExternalAudioCtrl"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pipelineExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentPipelineJob: Future<*>? = null

    private val downloader = HlsAudioSegmentDownloader()
    private val decoder = ExternalAudioMediaCodecDecoder(this)
    private val audioTrackOutput = ExternalAudioTrackOutput(this)

    @Volatile private var isRunning = false
    @Volatile private var isPaused = false
    @Volatile private var currentSourceUrl: String = ""
    @Volatile private var syncOffsetMs: Long = 0L
    @Volatile private var currentVolume: Float = 1.0f
    @Volatile private var retryAttempt: Int = 0
    @Volatile private var sessionGeneration: Long = 0L

    private val retryRunnable = AtomicBoolean(false)

    fun isActive(): Boolean = isRunning
    fun isPausedState(): Boolean = isPaused

    @Synchronized
    fun start(sourceUrl: String) {
        val trimmed = sourceUrl.trim()
        if (trimmed.isEmpty()) return

        sessionGeneration++
        val gen = sessionGeneration
        retryAttempt = 0
        currentSourceUrl = trimmed
        isRunning = true
        isPaused = false

        mainHandler.post { listener.onExternalAudioBuffering() }
        startPipelineSession(gen, trimmed)
    }

    private fun startPipelineSession(generation: Long, url: String) {
        currentPipelineJob?.cancel(true)
        currentPipelineJob = pipelineExecutor.submit {
            runPipeline(generation, url)
        }
    }

    private fun runPipeline(generation: Long, url: String) {
        if (!shouldContinue(generation)) return

        Log.i(tag, "Starting external audio session $generation for URL: $url")
        try {
            // Step 1: Fetch initial manifest / stream content
            val textResult = downloader.downloadText(url)
            if (!shouldContinue(generation)) return

            if (textResult.isFailure) {
                handleFailure(generation, "فشل الاتصال بمصدر الصوت الخارجي: ${textResult.exceptionOrNull()?.message}")
                return
            }

            val content = textResult.getOrThrow()
            if (!content.trimStart().startsWith("#EXTM3U")) {
                handleFailure(generation, "المصدر ليس قائمة HLS بصيغة m3u8؛ لا يمكن قراءة بث TS مباشر كنص", false)
                return
            }
            val targetMediaPlaylistUri = if (HlsMasterPlaylistParser.isMasterPlaylist(content)) {
                val masterPlaylist = HlsMasterPlaylistParser.parse(url, content)
                val bestAudio = HlsMasterPlaylistParser.selectBestAudioRendition(masterPlaylist)
                    ?: throw IllegalArgumentException("لا يوجد مسار صوت مستقل صالح مرتبط بمجموعة AUDIO في القائمة الرئيسية")
                Log.i(tag, "Discovered independent audio rendition: ${bestAudio.name} -> ${bestAudio.uri}")
                bestAudio.uri
            } else url

            // Step 2: Poll and play media playlist
            runMediaPlaylistLoop(generation, targetMediaPlaylistUri)
        } catch (e: Exception) {
            Log.e(tag, "Pipeline exception in session $generation", e)
            handleFailure(generation, e.message ?: "خطأ غير متوقع أثناء معالجة الصوت الخارجي")
        }
    }

    private fun runMediaPlaylistLoop(generation: Long, mediaPlaylistUri: String) {
        val processedSegments = HashSet<String>()
        var emptyPolls = 0

        while (shouldContinue(generation)) {
            while (isPaused && shouldContinue(generation)) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
            }

            val playlistResult = downloader.downloadText(mediaPlaylistUri)
            if (!shouldContinue(generation)) return

            if (playlistResult.isFailure) {
                handleFailure(generation, "تعذر تحميل قائمة مقاطع الصوت")
                return
            }

            val playlistContent = playlistResult.getOrThrow()
            if (!playlistContent.trimStart().startsWith("#EXTM3U")) {
                handleFailure(generation, "رابط مسار الصوت لا يعيد قائمة HLS صالحة", false)
                return
            }
            val playlist = HlsAudioPlaylistParser.parseMediaPlaylist(mediaPlaylistUri, playlistContent)
            playlist.unsupportedReason?.let { handleFailure(generation, it, false); return }
            val segments = playlist.segments

            val newSegments = segments.filter { it.uri !in processedSegments }
            if (newSegments.isEmpty() && !playlist.isEndList) {
                emptyPolls++
                if (emptyPolls >= 4) {
                    handleFailure(generation, "قائمة الصوت المباشر لا تقدم مقاطع جديدة", true)
                    return
                }
                val sleepTime = ((playlist.targetDurationSec * 1000) / 2).toLong().coerceIn(1000L, 5000L)
                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }

            if (newSegments.isNotEmpty()) emptyPolls = 0
            for (segment in newSegments) {
                if (!shouldContinue(generation)) return

                while (isPaused && shouldContinue(generation)) {
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        return
                    }
                }

                processedSegments.add(segment.uri)
                if (processedSegments.size > 200) {
                    processedSegments.clear()
                    processedSegments.add(segment.uri)
                }

                val segmentBytesResult = downloader.downloadBytes(segment.uri)
                if (!shouldContinue(generation)) return

                if (segmentBytesResult.isFailure) {
                    Log.w(tag, "Skipping failed segment: ${segment.uri}")
                    continue
                }

                val segmentBytes = segmentBytesResult.getOrThrow()
                val frames = AdtsAudioFrameExtractor.extractFrames(segmentBytes)
                if (frames.isEmpty()) {
                    handleFailure(generation, "المقطع ليس ADTS/AAC خاماً مدعوماً؛ فك TS اليدوي غير صالح لهذا المسار", false)
                    return
                }
                for (frame in frames) {
                    if (!shouldContinue(generation)) return
                    if (!decoder.configure(frame.sampleRate, frame.channelCount, frame.csd0)) return
                    decoder.decodeFrame(frame)
                }
            }

            if (playlist.isEndList) {
                Log.i(tag, "HLS Audio playlist ended (VOD/Finished)")
                break
            }

            val pollDelay = (playlist.targetDurationSec * 1000 * 0.75).toLong().coerceIn(1000L, 6000L)
            try {
                Thread.sleep(pollDelay)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun handleFailure(generation: Long, errorMessage: String) {
        if (!shouldContinue(generation)) return

        releaseComponents()
        val willRetry = retryAttempt < 3 && isRunning
        if (willRetry) retryAttempt++ else isRunning = false

        mainHandler.post {
            listener.onExternalAudioError(errorMessage, willRetry)
        }

        if (willRetry) {
            val delayMs = calculateRetryDelayMs(retryAttempt)
            Log.i(tag, "Scheduling audio retry #$retryAttempt in $delayMs ms")
            mainHandler.postDelayed({
                if (shouldContinue(generation) && isRunning) {
                    mainHandler.post { listener.onExternalAudioBuffering() }
                    startPipelineSession(generation, currentSourceUrl)
                }
            }, delayMs)
        } else {
            stop()
        }
    }

    private fun shouldContinue(generation: Long): Boolean {
        return generation == sessionGeneration && isRunning && !Thread.currentThread().isInterrupted
    }

    @Synchronized
    fun pause() {
        isPaused = true
        audioTrackOutput.pause()
    }

    @Synchronized
    fun resume() {
        isPaused = false
        if (isRunning) {
            audioTrackOutput.play()
        }
    }

    @Synchronized
    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0.0f, 1.0f)
        audioTrackOutput.setVolume(currentVolume)
    }

    @Synchronized
    fun setSyncOffset(offsetMs: Long) {
        syncOffsetMs = offsetMs.coerceIn(-10000L, 10000L)
    }

    @Synchronized
    fun stop() {
        sessionGeneration++
        isRunning = false
        isPaused = false
        currentPipelineJob?.cancel(true)
        currentPipelineJob = null
        releaseComponents()
        mainHandler.post { listener.onExternalAudioDetached() }
    }

    private fun releaseComponents() {
        decoder.release()
        audioTrackOutput.release()
    }

    // AudioDecoderCallback
    override fun onAudioFormatChanged(sampleRate: Int, channelCount: Int) {
        audioTrackOutput.initialize(sampleRate, channelCount)
        audioTrackOutput.setVolume(currentVolume)
    }

    override fun onPcmDataDecoded(pcmData: ByteArray, presentationTimeUs: Long) {
        if (!isPaused && isRunning) {
            audioTrackOutput.writePcm(pcmData)
        }
    }

    override fun onDecoderError(error: Exception) {
        handleFailure(sessionGeneration, "خطأ في فك ترميز الصوت: ${error.message}")
    }

    // AudioTrackPlaybackListener
    override fun onFirstPcmSamplePlayed() {
        mainHandler.post {
            listener.onExternalAudioFirstPcmVerified()
            listener.onExternalAudioPlaying()
        }
    }

    override fun onAudioTrackUnderrun() {
        Log.w(tag, "AudioTrack underrun detected")
    }

    companion object {
        fun calculateRetryDelayMs(attempt: Int): Long {
            return when {
                attempt <= 1 -> 1000L
                attempt == 2 -> 2000L
                attempt == 3 -> 4000L
                attempt == 4 -> 8000L
                attempt == 5 -> 16000L
                else -> 30000L
            }
        }
    }
}

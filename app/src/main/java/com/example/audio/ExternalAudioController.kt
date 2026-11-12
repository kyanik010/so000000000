package com.example.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

interface ExternalAudioEventListener {
    fun onExternalAudioBuffering()
    fun onExternalAudioPlaying()
    fun onExternalAudioFirstPcmVerified()
    fun onExternalAudioError(message: String, willRetry: Boolean)
    fun onExternalAudioDetached()
}

/**
 * Independent audio player for source B.
 *
 * Keep the source in Media3's native playback pipeline: do not download complete HLS
 * segments, decode them manually, or route PCM through AudioTrack. This preserves
 * Media3's support for HLS, MPEG-TS and ordinary audio URLs.
 */
@OptIn(UnstableApi::class)
class ExternalAudioController(
    context: Context,
    private val listener: ExternalAudioEventListener
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "SONIXExternalAudio"

    @Volatile private var isRunning = false
    @Volatile private var isPaused = false
    @Volatile private var currentSourceUrl = ""
    @Volatile private var currentVolume = 1f
    @Volatile private var syncOffsetMs = 0L
    private var retryAttempt = 0
    private var generation = 0L
    private var player: ExoPlayer? = null
    private var retryRunnable: Runnable? = null
    private var readyReported = false

    fun isActive(): Boolean = isRunning
    fun isPausedState(): Boolean = isPaused

    @Synchronized
    fun start(sourceUrl: String) {
        val url = sourceUrl.trim()
        if (url.isEmpty()) {
            listener.onExternalAudioError("رابط مصدر الصوت الخارجي فارغ", false)
            return
        }
        generation++
        val activeGeneration = generation
        currentSourceUrl = url
        retryAttempt = 0
        isRunning = true
        isPaused = false
        cancelRetry()
        releasePlayer()
        listener.onExternalAudioBuffering()
        startPlayer(activeGeneration, url)
    }

    private fun startPlayer(activeGeneration: Long, url: String) {
        if (!isCurrent(activeGeneration)) return
        releasePlayer()
        readyReported = false

        try {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("SONIX-TV/1.0")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 30_000, 1_500, 5_000)
                .build()

            val audioPlayer = ExoPlayer.Builder(appContext)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
                .build()

            player = audioPlayer
            audioPlayer.trackSelectionParameters =
                audioPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            audioPlayer.volume = currentVolume

            audioPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (!isCurrentPlayer(activeGeneration, audioPlayer)) return
                    when (state) {
                        Player.STATE_BUFFERING -> listener.onExternalAudioBuffering()
                        Player.STATE_READY -> {
                            cancelRetry()
                            retryAttempt = 0
                            if (!readyReported) {
                                readyReported = true
                                applySyncOffset(audioPlayer)
                                // Player B is prepared and its audio renderer/track is ready.
                                listener.onExternalAudioFirstPcmVerified()
                                if (!isPaused) listener.onExternalAudioPlaying()
                            }
                        }
                        Player.STATE_ENDED -> scheduleRetry(
                            activeGeneration,
                            "انتهى مصدر الصوت الخارجي"
                        )
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!isCurrentPlayer(activeGeneration, audioPlayer)) return
                    Log.w(tag, "Secondary audio playback failed: ${error.errorCodeName}", error)
                    scheduleRetry(
                        activeGeneration,
                        "تعذر تشغيل مصدر الصوت: ${error.errorCodeName}"
                    )
                }
            })

            val uri = Uri.parse(url)
            val extension = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase()
                .orEmpty()
            val mimeType = when (extension) {
                "m3u8" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                "ts", "mpegts" -> MimeTypes.VIDEO_MP2T
                "aac" -> MimeTypes.AUDIO_AAC
                "mp3" -> MimeTypes.AUDIO_MPEG
                else -> null
            }
            val item = MediaItem.Builder()
                .setUri(uri)
                .apply { if (mimeType != null) setMimeType(mimeType) }
                .build()

            audioPlayer.setMediaItem(item)
            audioPlayer.prepare()
            if (!isPaused) audioPlayer.play()
            Log.i(tag, "Started independent Media3 audio player for $url")
        } catch (error: Exception) {
            Log.e(tag, "Could not create secondary audio player", error)
            scheduleRetry(activeGeneration, error.message ?: "تعذر بدء مشغل الصوت")
        }
    }

    private fun scheduleRetry(activeGeneration: Long, message: String) {
        if (!isCurrent(activeGeneration)) return
        releasePlayer()
        retryAttempt++
        val willRetry = retryAttempt <= 3
        listener.onExternalAudioError(message, willRetry)
        if (!willRetry) {
            isRunning = false
            return
        }
        val delayMs = when (retryAttempt) {
            1 -> 1_000L
            2 -> 2_000L
            else -> 4_000L
        }
        listener.onExternalAudioBuffering()
        val retry = Runnable {
            retryRunnable = null
            if (isCurrent(activeGeneration)) startPlayer(activeGeneration, currentSourceUrl)
        }
        retryRunnable = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun applySyncOffset(activePlayer: ExoPlayer) {
        val offset = syncOffsetMs
        try {
            if (offset > 0L) {
                val position = activePlayer.currentPosition
                if (position > offset) activePlayer.seekTo(position - offset)
            } else if (offset < 0L) {
                activePlayer.seekTo(activePlayer.currentPosition + kotlin.math.abs(offset))
            }
        } catch (error: Exception) {
            Log.w(tag, "External audio sync offset could not be applied", error)
        }
    }

    @Synchronized
    fun pause() {
        isPaused = true
        player?.pause()
    }

    @Synchronized
    fun resume() {
        isPaused = false
        if (isRunning) player?.play()
    }

    @Synchronized
    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        player?.volume = currentVolume
    }

    @Synchronized
    fun setSyncOffset(offsetMs: Long) {
        syncOffsetMs = offsetMs.coerceIn(-10_000L, 10_000L)
        player?.let(::applySyncOffset)
    }

    @Synchronized
    fun stop() {
        generation++
        isRunning = false
        isPaused = false
        cancelRetry()
        releasePlayer()
        listener.onExternalAudioDetached()
    }

    private fun isCurrent(activeGeneration: Long): Boolean =
        activeGeneration == generation && isRunning

    private fun isCurrentPlayer(activeGeneration: Long, candidate: ExoPlayer): Boolean =
        isCurrent(activeGeneration) && player === candidate

    private fun cancelRetry() {
        retryRunnable?.let(mainHandler::removeCallbacks)
        retryRunnable = null
    }

    private fun releasePlayer() {
        val old = player
        player = null
        if (old != null) {
            try {
                old.stop()
                old.clearMediaItems()
            } catch (_: Exception) {
            }
            old.release()
        }
    }
}

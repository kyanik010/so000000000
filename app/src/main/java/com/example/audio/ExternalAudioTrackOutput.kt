package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

interface AudioTrackPlaybackListener {
    fun onFirstPcmSamplePlayed()
    fun onAudioTrackUnderrun()
}

class ExternalAudioTrackOutput(
    private val listener: AudioTrackPlaybackListener
) {
    private val tag = "SONIXAudioTrack"
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannelCount = 0
    private var volume = 1.0f
    private var hasReportedFirstSample = false
    private var isPlaying = false

    @Synchronized
    fun initialize(sampleRate: Int, channelCount: Int): Boolean {
        if (audioTrack != null && currentSampleRate == sampleRate && currentChannelCount == channelCount) {
            return true
        }

        release()

        if (sampleRate <= 0 || channelCount !in 1..2) {
            Log.e(tag, "Unsupported PCM output format: ${sampleRate}Hz / ${channelCount}ch")
            return false
        }
        val channelMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            Log.e(tag, "Invalid minBufferSize: $minBufSize for $sampleRate Hz, $channelCount ch")
            return false
        }

        val bufferSize = (minBufSize * 4).coerceAtLeast(16384)

        return try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return false
            }
            track.setVolume(volume)
            audioTrack = track
            currentSampleRate = sampleRate
            currentChannelCount = channelCount
            hasReportedFirstSample = false
            isPlaying = false
            Log.i(tag, "Initialized AudioTrack: ${sampleRate}Hz, ${channelCount}ch, buffer: $bufferSize")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize AudioTrack", e)
            false
        }
    }

    @Synchronized
    fun play() {
        val track = audioTrack ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
                isPlaying = true
            }
        } catch (e: Exception) {
            Log.e(tag, "Error starting AudioTrack playback", e)
        }
    }

    @Synchronized
    fun pause() {
        val track = audioTrack ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                isPlaying = false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error pausing AudioTrack", e)
        }
    }

    @Synchronized
    fun flush() {
        val track = audioTrack ?: return
        try {
            track.flush()
        } catch (e: Exception) {
            Log.w(tag, "Error flushing AudioTrack", e)
        }
    }

    @Synchronized
    fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        val track = audioTrack ?: return 0
        if (!isPlaying) {
            play()
        }

        var total = 0
        while (total < length) {
            val written = track.write(data, offset + total, length - total, AudioTrack.WRITE_BLOCKING)
            if (written < 0) throw IllegalStateException("AudioTrack.write failed: $written")
            if (written == 0) throw IllegalStateException("AudioTrack.write returned zero")
            total += written
            if (!hasReportedFirstSample) {
                hasReportedFirstSample = true
                listener.onFirstPcmSamplePlayed()
            }
        }
        return total
    }

    @Synchronized
    fun setVolume(newVolume: Float) {
        volume = newVolume.coerceIn(0.0f, 1.0f)
        audioTrack?.setVolume(volume)
    }

    @Synchronized
    fun release() {
        val track = audioTrack
        audioTrack = null
        isPlaying = false
        hasReportedFirstSample = false
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.flush()
                track.release()
            } catch (e: Exception) {
                Log.w(tag, "Error releasing AudioTrack", e)
            }
        }
    }
}

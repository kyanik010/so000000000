package com.example.audio

import java.nio.ByteBuffer

data class AdtsFrame(
    val sampleRate: Int,
    val channelCount: Int,
    val profile: Int, // AAC Audio Object Type (e.g. 2 for AAC-LC)
    val headerLength: Int,
    val frameLength: Int,
    val frameData: ByteArray,
    val csd0: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AdtsFrame
        return sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                frameLength == other.frameLength &&
                frameData.contentEquals(other.frameData)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channelCount
        result = 31 * result + frameLength
        result = 31 * result + frameData.contentHashCode()
        return result
    }
}

object AdtsAudioFrameExtractor {

    private val SAMPLING_FREQUENCY_TABLE = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    /**
     * Checks if the data begins with TS sync byte (0x47) repeating every 188 bytes.
     */
    fun isMpegTs(data: ByteArray): Boolean {
        if (data.size < 188) return false
        return data[0] == 0x47.toByte() && (data.size < 376 || data[188] == 0x47.toByte())
    }

    /**
     * Extracts all ADTS frames from raw bytes (either raw ADTS or TS elementary stream).
     */
    fun extractFrames(data: ByteArray): List<AdtsFrame> {
        val payload = if (isMpegTs(data)) {
            extractTsAudioPayload(data)
        } else {
            data
        }
        return parseAdtsFrames(payload)
    }

    /**
     * Parses contiguous ADTS frames from a byte array.
     */
    fun parseAdtsFrames(data: ByteArray): List<AdtsFrame> {
        val frames = mutableListOf<AdtsFrame>()
        var offset = 0
        val size = data.size

        while (offset <= size - 7) {
            val b0 = data[offset].toInt() and 0xFF
            val b1 = data[offset + 1].toInt() and 0xFF

            // Check ADTS syncword: 0xFF followed by 0xFx
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) {
                val protectionAbsent = b1 and 0x01
                val headerLength = if (protectionAbsent == 1) 7 else 9

                if (offset + headerLength > size) break

                val b2 = data[offset + 2].toInt() and 0xFF
                val b3 = data[offset + 3].toInt() and 0xFF
                val b4 = data[offset + 4].toInt() and 0xFF
                val b5 = data[offset + 5].toInt() and 0xFF

                val rawProfile = (b2 and 0xC0) shr 6
                val audioObjectType = rawProfile + 1 // AAC-LC is profile 1 -> Audio Object Type 2
                val freqIndex = (b2 and 0x3C) shr 2
                val sampleRate = if (freqIndex < SAMPLING_FREQUENCY_TABLE.size) {
                    SAMPLING_FREQUENCY_TABLE[freqIndex]
                } else {
                    48000
                }

                val channelConfig = ((b2 and 0x01) shl 2) or ((b3 and 0xC0) shr 6)
                val channelCount = when (channelConfig) {
                    1 -> 1
                    2 -> 2
                    3 -> 3
                    4 -> 4
                    5 -> 5
                    6 -> 6
                    7 -> 8
                    else -> 2
                }

                val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) shr 5)

                if (frameLength in headerLength..8192 && offset + frameLength <= size) {
                    val frameBytes = data.copyOfRange(offset, offset + frameLength)
                    val csd0 = buildAudioSpecificConfig(audioObjectType, freqIndex, channelConfig)
                    frames.add(
                        AdtsFrame(
                            sampleRate = sampleRate,
                            channelCount = channelCount,
                            profile = audioObjectType,
                            headerLength = headerLength,
                            frameLength = frameLength,
                            frameData = frameBytes,
                            csd0 = csd0
                        )
                    )
                    offset += frameLength
                    continue
                }
            }
            offset++
        }

        return frames
    }

    /**
     * Builds CSD-0 (AudioSpecificConfig) 2-byte header for MediaCodec initialization.
     */
    fun buildAudioSpecificConfig(audioObjectType: Int, freqIndex: Int, channelConfig: Int): ByteArray {
        val config = ByteArray(2)
        // 5 bits audioObjectType, 4 bits freqIndex, 4 bits channelConfig, 3 bits 0
        config[0] = (((audioObjectType and 0x1F) shl 3) or ((freqIndex and 0x0E) shr 1)).toByte()
        config[1] = (((freqIndex and 0x01) shl 7) or ((channelConfig and 0x0F) shl 3)).toByte()
        return config
    }

    /**
     * Minimal zero-overhead PES audio payload extractor for MPEG-TS audio streams.
     */
    private fun extractTsAudioPayload(data: ByteArray): ByteArray {
        val output = ArrayList<Byte>(data.size / 2)
        var offset = 0
        val packetSize = 188

        while (offset + packetSize <= data.size) {
            if (data[offset] == 0x47.toByte()) {
                val b1 = data[offset + 1].toInt() and 0xFF
                val b2 = data[offset + 2].toInt() and 0xFF
                val b3 = data[offset + 3].toInt() and 0xFF

                val payloadStart = (b1 and 0x40) != 0
                val pid = ((b1 and 0x1F) shl 8) or b2
                val adaptationControl = (b3 and 0x30) shr 4

                // Skip NULL packets (PID 0x1FFF) and PAT/PMT (PID 0, etc.)
                if (pid > 0x1F && pid != 0x1FFF && (adaptationControl == 1 || adaptationControl == 3)) {
                    var payloadOffset = offset + 4
                    if (adaptationControl == 3) {
                        val adaptationLength = data[payloadOffset].toInt() and 0xFF
                        payloadOffset += 1 + adaptationLength
                    }

                    if (payloadOffset < offset + packetSize) {
                        for (i in payloadOffset until (offset + packetSize)) {
                            output.add(data[i])
                        }
                    }
                }
            }
            offset += packetSize
        }

        val result = ByteArray(output.size)
        for (i in output.indices) {
            result[i] = output[i]
        }
        return result
    }
}

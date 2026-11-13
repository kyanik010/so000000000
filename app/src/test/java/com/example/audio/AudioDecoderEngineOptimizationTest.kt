package com.example.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderEngineOptimizationTest {

    @Test
    fun validAdtsFrameCanBePassedThroughWithoutCodecWork() {
        // ADTS header declares a 9-byte AAC frame.
        val frame = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(),
            0x80.toByte(), 0x01.toByte(), 0x3F.toByte(), 0xFC.toByte(),
            0x11.toByte(), 0x22.toByte()
        )

        assertTrue(AudioDecoderEngine.isValidAdtsFrame(frame))
    }

    @Test
    fun rejectsTruncatedAdtsFrame() {
        // Header declares 9 bytes, but only the 7-byte header is present.
        val truncated = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(),
            0x80.toByte(), 0x01.toByte(), 0x3F.toByte(), 0xFC.toByte()
        )

        assertFalse(AudioDecoderEngine.isValidAdtsFrame(truncated))
    }

    @Test
    fun rejectsNonAdtsPayload() {
        assertFalse(AudioDecoderEngine.isValidAdtsFrame(byteArrayOf(0x00, 0x01, 0x02)))
    }
}

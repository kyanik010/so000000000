package com.example

import com.example.audio.AudioChannelGroup
import com.example.audio.AudioDecoderEngine
import com.example.audio.AudioOnlyOutputEngine
import com.example.audio.AudioSourceClassifier
import com.example.audio.AudioStreamInspector
import com.example.audio.SimpleTsAudioDemuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioAddonEngineTest {

    // =========================================================================
    // 1. Channel Discovery and Classification Tests
    // =========================================================================

    @Test
    fun classify_beInSports_englishVariants() {
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("beIN SPORTS 1 HD", "SPORTS"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("beIN Sports 2 Premium", "VIP Sports"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("beINSPORT 3 4K", "beIN Package"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("BE IN SPORTS NEWS", "News"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("beIN SPORTS AFC 1", "AFC"))
    }

    @Test
    fun classify_beInSports_arabicVariants() {
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("بي ان سبورت 1", "الرياضة"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("بي إن سبورت 2 إتش دي", "باقة بي ان"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("بين سبورت الإخبارية", "أخبار"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("قناة بي ان ماكس 1", "كأس أفريقيا"))
    }

    @Test
    fun classify_alwan_englishAndArabic() {
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("ALWAN 1 HD", "Audio Commentary"))
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("Al Wan Audio Sport", "Audio Feeds"))
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("صوتية ألوان 1", "قنوات صوتية"))
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("راديو الوان سبورت 2", "صوت"))
    }

    @Test
    fun classify_excludesIrrelevantChannels() {
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Abu Dhabi Sports 1 HD", "UAE Sports"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("SSC 1 HD", "Saudi Sports"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("الكاس 1 HD", "قطر"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Rotana Cinema", "Movies"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Dubai Sports 2", "Dubai"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("MBC 1", "General"))
    }

    @Test
    fun classify_handlesDiacriticsAndSpaces() {
        // Tashkeel, Alef variants, punctuation
        val test1 = AudioSourceClassifier.classify("بَيْنَ سْبُورْتْ   ١   عَالِيَةُ الدِّقَّةِ")
        assertEquals(AudioChannelGroup.BEIN_SPORTS, test1)

        val test2 = AudioSourceClassifier.classify("  أَلْوَانْ - القَنَاةُ الصَّوْتِيَّةُ  ")
        assertEquals(AudioChannelGroup.ALWAN, test2)
    }

    // =========================================================================
    // 2. Stream Inspection and Container Parsing Tests
    // =========================================================================

    @Test
    fun inspectHls_detectsSeparateAudioRenditions() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-aac",NAME="Arabic",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="ar",URI="tracks/audio_ar.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1920x1080,AUDIO="audio-aac"
            video_1080p.m3u8
        """.trimIndent()

        val renditions = AudioStreamInspector.parseHlsAudioRenditions(masterPlaylist, "http://provider.tv/live/master.m3u8")
        assertEquals(1, renditions.size)
        assertEquals("http://provider.tv/live/tracks/audio_ar.m3u8", renditions[0])
    }

    @Test
    fun inspect_rejectsUnsafeSchemes() {
        val fileResult = AudioStreamInspector.inspectStream("file:///sdcard/stream.ts")
        assertFalse(fileResult.isSupported)
        assertTrue(fileResult.errorMessage?.contains("بروتوكول") == true)

        val contentResult = AudioStreamInspector.inspectStream("content://media/external/123")
        assertFalse(contentResult.isSupported)
    }

    // =========================================================================
    // 3. Audio Decoding to PCM and Video Elimination Tests
    // =========================================================================

    @Test
    fun decoder_demuxesTsAndProducesPcmBuffers() {
        val pcmProduced = AtomicBoolean(false)
        val totalPcmBytes = AtomicLong(0L)

        val decoder = AudioDecoderEngine(
            onPcmDecoded = { bytes, sampleRate, channels, _ ->
                pcmProduced.set(true)
                totalPcmBytes.addAndGet(bytes.size.toLong())
                assertEquals(48000, sampleRate)
                assertEquals(2, channels)
                assertTrue("Decoded PCM chunk must not be empty", bytes.isNotEmpty())
            },
            onError = { err ->
                throw AssertionError("Decoding failed: ${err.message}")
            }
        )

        // Generate synthetic audio ADTS frames
        val dummyAacPayload = ByteArray(128) { (it * 7).toByte() }
        val dummyAdtsFrame = AudioOnlyOutputEngine.addAdtsHeader(dummyAacPayload, 48000, 2)

        // Verify ADTS syncword (0xFFF1)
        assertEquals(0xFF.toByte(), dummyAdtsFrame[0])
        assertEquals(0xF1.toByte(), dummyAdtsFrame[1])

        val inputStream = ByteArrayInputStream(dummyAdtsFrame + dummyAdtsFrame + dummyAdtsFrame)
        decoder.processStream(inputStream)

        assertTrue("PCM must be produced by audio decoder engine", pcmProduced.get())
        assertTrue("PCM byte count must be positive", totalPcmBytes.get() > 0)
        val metrics = decoder.getMetrics()
        assertTrue("Metrics must reflect decoded samples", metrics.pcmSamplesDecoded > 0)
    }

    @Test
    fun tsDemuxer_discardsVideoPidsEntirely() {
        val demuxer = SimpleTsAudioDemuxer()
        // Create synthetic TS packet with Video PID 0x0100 (256)
        val videoPacket = ByteArray(188)
        videoPacket[0] = 0x47.toByte() // Sync byte
        videoPacket[1] = 0x01.toByte() // PID high bits (0x01)
        videoPacket[2] = 0x00.toByte() // PID low bits (0x00) -> PID 256
        videoPacket[3] = 0x10.toByte() // Payload only

        val extracted = demuxer.extractAudioPayloads(videoPacket, 188)
        assertTrue("Video PID packets must be completely discarded by audio demuxer", extracted.isEmpty())
    }

    // =========================================================================
    // 4. Independent Audio Output and Loopback Server Tests
    // =========================================================================

    @Test
    fun outputEngine_generatesPureAacAdtsStreamWithoutVideo() {
        val outputEngine = AudioOnlyOutputEngine(sessionId = "test1234", sampleRate = 48000, channels = 2)
        val streamUrl = outputEngine.start()

        assertTrue(streamUrl.startsWith("http://127.0.0.1:"))
        assertTrue(streamUrl.endsWith(".aac"))

        // Feed PCM chunks into output engine
        val testPcm = ByteArray(4096) { (it % 127).toByte() }
        outputEngine.ingestPcm(testPcm, ptsUs = 0L)
        outputEngine.ingestPcm(testPcm, ptsUs = 21333L)

        // Connect via HTTP client to verify pure audio/aac response
        val url = URL(streamUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.requestMethod = "GET"

        assertEquals(200, connection.responseCode)
        assertEquals("audio/aac", connection.contentType)

        val inStream = connection.inputStream
        val receivedHeader = ByteArray(7)
        val read = inStream.read(receivedHeader)
        assertEquals(7, read)

        // Verify standard ADTS syncword 0xFFF
        assertEquals(0xFF.toByte(), receivedHeader[0])
        assertEquals((0xF0).toByte(), (receivedHeader[1].toInt() and 0xF0).toByte())

        connection.disconnect()
        outputEngine.stop()
    }
}

package com.example

import com.example.audio.AdtsAudioFrameExtractor
import com.example.audio.AudioRendition
import com.example.audio.ExternalAudioController
import com.example.audio.HlsAudioPlaylistParser
import com.example.audio.HlsMasterPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioPipelineTest {

    @Test
    fun testHlsMasterPlaylistParsing_extractsAudioRenditions() {
        val baseUri = "http://iptv.server.net/live/user/pass/stream.m3u8"
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-aac",NAME="Arabic Commentary",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="ara",CHANNELS="2",URI="audio_ara.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-aac",NAME="English Commentary",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE="eng",CHANNELS="2",URI="audio_eng.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,AUDIO="audio-aac"
            video_1080p.m3u8
        """.trimIndent()

        assertTrue(HlsMasterPlaylistParser.isMasterPlaylist(manifest))

        val masterPlaylist = HlsMasterPlaylistParser.parse(baseUri, manifest)
        assertEquals(2, masterPlaylist.audioRenditions.size)

        val ara = masterPlaylist.audioRenditions[0]
        assertEquals("audio-aac", ara.groupId)
        assertEquals("Arabic Commentary", ara.name)
        assertEquals("ara", ara.language)
        assertTrue(ara.isDefault)
        assertTrue(ara.autoSelect)
        assertEquals(2, ara.channels)
        assertEquals("http://iptv.server.net/live/user/pass/audio_ara.m3u8", ara.uri)

        val eng = masterPlaylist.audioRenditions[1]
        assertEquals("English Commentary", eng.name)
        assertEquals("eng", eng.language)
        assertFalse(eng.isDefault)
        assertEquals("http://iptv.server.net/live/user/pass/audio_eng.m3u8", eng.uri)

        // Select best audio rendition
        val selectedDefault = HlsMasterPlaylistParser.selectBestAudioRendition(masterPlaylist)
        assertNotNull(selectedDefault)
        assertEquals("Arabic Commentary", selectedDefault?.name)

        val selectedEng = HlsMasterPlaylistParser.selectBestAudioRendition(masterPlaylist, preferredLanguage = "eng")
        assertNotNull(selectedEng)
        assertEquals("English Commentary", selectedEng?.name)
    }

    @Test
    fun testHlsMasterPlaylist_requiresAudioGroupLinkedToAStreamVariant() {
        val manifestWithoutVariantAudioGroup = """
            #EXTM3U
            #EXT-X-MEDIA:URI="audio.m3u8",NAME="Arabic",GROUP-ID="audio",TYPE=AUDIO
            #EXT-X-STREAM-INF:BANDWIDTH=1200000
            video.m3u8
        """.trimIndent()
        val parsed = HlsMasterPlaylistParser.parse(
            "https://example.test/master.m3u8",
            manifestWithoutVariantAudioGroup
        )
        assertTrue(parsed.audioRenditions.isNotEmpty())
        assertEquals(null, HlsMasterPlaylistParser.selectBestAudioRendition(parsed))
    }

    @Test
    fun testHlsMasterPlaylist_handlesAttributeOrderAndQuotedCommas() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="audio,main.m3u8",NAME="Arabic, Main",GROUP-ID="aud",TYPE=AUDIO,DEFAULT=YES
            #EXT-X-STREAM-INF:AUDIO="aud",BANDWIDTH=1200000
            video.m3u8
        """.trimIndent()
        val parsed = HlsMasterPlaylistParser.parse("https://example.test/master.m3u8", manifest)
        val selected = HlsMasterPlaylistParser.selectBestAudioRendition(parsed)
        assertNotNull(selected)
        assertEquals("Arabic, Main", selected?.name)
        assertEquals("https://example.test/audio,main.m3u8", selected?.uri)
    }

    @Test
    fun testHlsUriResolution_rfc8216() {
        val base = "http://cdn.example.com/live/ch1/master.m3u8"

        // Relative path
        assertEquals(
            "http://cdn.example.com/live/ch1/audio/index.m3u8",
            HlsMasterPlaylistParser.resolveUri(base, "audio/index.m3u8")
        )

        // Root relative path
        assertEquals(
            "http://cdn.example.com/audio/index.m3u8",
            HlsMasterPlaylistParser.resolveUri(base, "/audio/index.m3u8")
        )

        // Absolute URL
        assertEquals(
            "https://audio-cdn.other.com/live/audio.m3u8",
            HlsMasterPlaylistParser.resolveUri(base, "https://audio-cdn.other.com/live/audio.m3u8")
        )
    }

    @Test
    fun testHlsAudioMediaPlaylistParsing() {
        val base = "http://iptv.net/audio/playlist.m3u8"
        val playlistContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:1042
            #EXTINF:6.000,
            segment_1042.aac
            #EXTINF:5.980,
            segment_1043.aac
            #EXTINF:6.020,
            segment_1044.aac
        """.trimIndent()

        val parsed = HlsAudioPlaylistParser.parseMediaPlaylist(base, playlistContent)
        assertEquals(6.0, parsed.targetDurationSec, 0.001)
        assertEquals(1042L, parsed.mediaSequence)
        assertFalse(parsed.isEndList)
        assertEquals(3, parsed.segments.size)

        assertEquals("http://iptv.net/audio/segment_1042.aac", parsed.segments[0].uri)
        assertEquals(1042L, parsed.segments[0].sequenceNumber)
        assertEquals(6.0, parsed.segments[0].durationSec, 0.001)

        assertEquals("http://iptv.net/audio/segment_1043.aac", parsed.segments[1].uri)
        assertEquals(1043L, parsed.segments[1].sequenceNumber)

        assertEquals("http://iptv.net/audio/segment_1044.aac", parsed.segments[2].uri)
        assertEquals(1044L, parsed.segments[2].sequenceNumber)
    }

    @Test
    fun testAdtsAudioFrameExtractor_parsesSyntheticAdtsHeader() {
        // Construct a synthetic 7-byte ADTS header for AAC-LC, 48000Hz (freqIndex 3), Stereo (channelConfig 2)
        // Frame length = 128 bytes
        val frameLength = 128
        val frame = ByteArray(frameLength)

        // b0: 0xFF
        frame[0] = 0xFF.toByte()
        // b1: 0xF1 (sync 0xF0 | ID 0 (MPEG-4) | Layer 00 | Protection absent 1 (no CRC))
        frame[1] = 0xF1.toByte()
        // b2: profile 1 (AAC-LC, bits 7..6 = 01) | freqIndex 3 (48000Hz, bits 5..2 = 0011) | private 0 | channelConfig bit 2 (0)
        // (1 shl 6) | (3 shl 2) = 0x40 | 0x0C = 0x4C
        frame[2] = 0x4C.toByte()
        // b3: channelConfig bits 1..0 = 10 (stereo) -> bits 7..6 = 10 | original 0 | home 0 | frame length bits 12..11 = 00
        // (2 shl 6) = 0x80
        frame[3] = 0x80.toByte()
        // b4: frame length bits 10..3 -> 128 shr 3 = 16 (0x10)
        frame[4] = 0x10.toByte()
        // b5: frame length bits 2..0 (0) in bits 7..5 | buffer fullness bits 10..6
        frame[5] = 0x00.toByte()
        // b6: buffer fullness bits 5..0 | blocks in frame 0
        frame[6] = 0x00.toByte()

        val frames = AdtsAudioFrameExtractor.parseAdtsFrames(frame)
        assertEquals(1, frames.size)

        val extracted = frames[0]
        assertEquals(48000, extracted.sampleRate)
        assertEquals(2, extracted.channelCount)
        assertEquals(2, extracted.profile) // Audio Object Type 2 (AAC-LC)
        assertEquals(7, extracted.headerLength)
        assertEquals(128, extracted.frameLength)

        // Verify CSD-0
        val csd0 = extracted.csd0
        assertEquals(2, csd0.size)
        // audioObjectType=2, freqIndex=3, channelConfig=2
        // byte0: (2 shl 3) | (3 shr 1) = 16 | 1 = 17 (0x11)
        // byte1: ((3 and 1) shl 7) | (2 shl 3) = 128 | 16 = 144 (0x90)
        assertEquals(0x11.toByte(), csd0[0])
        assertEquals(0x90.toByte(), csd0[1])
    }

    @Test
    fun testRetryDelaySchedule_matchesSprtxxxBackoff() {
        assertEquals(1000L, ExternalAudioController.calculateRetryDelayMs(1))
        assertEquals(2000L, ExternalAudioController.calculateRetryDelayMs(2))
        assertEquals(4000L, ExternalAudioController.calculateRetryDelayMs(3))
        assertEquals(8000L, ExternalAudioController.calculateRetryDelayMs(4))
        assertEquals(16000L, ExternalAudioController.calculateRetryDelayMs(5))
        assertEquals(30000L, ExternalAudioController.calculateRetryDelayMs(6))
        assertEquals(30000L, ExternalAudioController.calculateRetryDelayMs(10))
    }
}

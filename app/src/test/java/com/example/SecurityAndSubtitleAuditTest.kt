package com.example

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class SecurityAndSubtitleAuditTest {

    // --- 1. Subtitle Pipeline Tests ---

    @Test
    fun srtParsing_standardWithBlankLines() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            مرحباً بكم في SONIX TV

            2
            00:00:05,500 --> 00:00:08,200
            المشهد الثاني
        """.trimIndent()

        val cues = SubtitlePipeline.parse(srt, "srt")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals("مرحباً بكم في SONIX TV", cues[0].text)

        assertEquals(5500L, cues[1].startMs)
        assertEquals(8200L, cues[1].endMs)
        assertEquals("المشهد الثاني", cues[1].text)
    }

    @Test
    fun srtParsing_withoutBlankLinesBetweenCues_parsedCorrectly() {
        // Critical real-world edge case: SRT cues not separated by blank lines
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            السطر الأول
            2
            00:00:03,500 --> 00:00:06,000
            السطر الثاني
            3
            00:00:07,000 --> 00:00:09,000
            السطر الثالث
        """.trimIndent()

        val cues = SubtitlePipeline.parse(srt, "srt")
        assertEquals("Should parse all 3 cues even without blank lines", 3, cues.size)
        assertEquals("السطر الأول", cues[0].text)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)

        assertEquals("السطر الثاني", cues[1].text)
        assertEquals(3500L, cues[1].startMs)
        assertEquals(6000L, cues[1].endMs)

        assertEquals("السطر الثالث", cues[2].text)
        assertEquals(7000L, cues[2].startMs)
        assertEquals(9000L, cues[2].endMs)
    }

    @Test
    fun vttParsing_withHeaderCommentsAndCueSettings() {
        val vtt = """
            WEBVTT
            NOTE This is a commentary block

            00:01.000 --> 00:04.000 position:10% align:left
            Welcome to SONIX

            01:02:03.500 --> 01:02:07.000 line:90%
            Multi-line
            Subtitle text
        """.trimIndent()

        val cues = SubtitlePipeline.parse(vtt, "vtt")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals("Welcome to SONIX", cues[0].text)

        val expectedStart = (1 * 3600 + 2 * 60 + 3) * 1000L + 500L
        val expectedEnd = (1 * 3600 + 2 * 60 + 7) * 1000L
        assertEquals(expectedStart, cues[1].startMs)
        assertEquals(expectedEnd, cues[1].endMs)
        assertEquals("Multi-line\nSubtitle text", cues[1].text)
    }

    @Test
    fun cleanCueText_decodesNamedAndNumericEntities() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            <b>Tom &amp; Jerry</b> &#39;Special&#39; &#x41;&#x42;&#x43;
        """.trimIndent()

        val cues = SubtitlePipeline.parse(srt, "srt")
        assertEquals(1, cues.size)
        assertEquals("Tom & Jerry 'Special' ABC", cues[0].text)
    }

    @Test
    fun zipSlip_isRejectedWithSecurityException() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.srt"))
            zos.write("1\n00:00:01,000 --> 00:00:02,000\nEvil\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipBytes = baos.toByteArray()

        try {
            SubtitlePipeline.decodePayload(zipBytes, null, "archive.zip")
            fail("Expected IllegalArgumentException for Zip Slip attempt")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("غير آمن") == true)
        }
    }

    @Test
    fun zipBomb_tooManyEntries_isRejected() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for (i in 1..105) {
                zos.putNextEntry(ZipEntry("file_$i.bin"))
                zos.write("data".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("subtitle.srt"))
            zos.write("1\n00:00:01,000 --> 00:00:02,000\nCue\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipBytes = baos.toByteArray()

        try {
            SubtitlePipeline.decodePayload(zipBytes, null, "bomb.zip")
            fail("Expected IllegalArgumentException for Zip with excessive entries")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("عدد كبير") == true)
        }
    }

    // --- 2. CredentialSanitizer Tests ---

    @Test
    fun credentialSanitizer_masksXtreamCredentials() {
        val rawUrl = "http://my-iptv.net:8080/live/user_alpha/secret_pass123/98765.m3u8"
        val sanitized = CredentialSanitizer.sanitizeUrl(rawUrl)
        assertFalse(sanitized.contains("secret_pass123"))
        assertFalse(sanitized.contains("user_alpha"))
        assertTrue(sanitized.contains("my-iptv.net:8080/live/***/***/98765.m3u8") ||
                   sanitized.contains("my-iptv.net:8080/live/[REDACTED]/[REDACTED]/98765.m3u8") ||
                   sanitized.contains("98765.m3u8"))
    }

    @Test
    fun credentialSanitizer_masksBasicAuthAndQueryParams() {
        val rawUrl = "https://admin:superSecret@iptv-host.com/stream?token=abc123xyz&password=mypassword&id=42"
        val sanitized = CredentialSanitizer.sanitizeUrl(rawUrl)
        assertFalse("Password in query should be redacted", sanitized.contains("mypassword"))
        assertFalse("Token in query should be redacted", sanitized.contains("abc123xyz"))
        assertFalse("Basic auth secret should be redacted", sanitized.contains("superSecret"))
        assertTrue(sanitized.contains("id=42"))
    }

    @Test
    fun credentialSanitizer_sanitizesErrorMessageEmbeddingUrls() {
        val rawError = "Playback error: Failed to connect to http://iptv.com/live/john/pass123/1.ts (HTTP 401)"
        val sanitized = CredentialSanitizer.sanitizeMessage(rawError)
        assertFalse("Username john should not appear", sanitized.contains("john"))
        assertFalse("Password pass123 should not appear", sanitized.contains("pass123"))
        assertTrue("Error structure preserved", sanitized.contains("Playback error") && sanitized.contains("HTTP 401"))
    }

    // --- 3. NetworkSecurityValidator SSRF Tests ---

    @Test
    fun networkSecurityValidator_blocksLoopbackAndCloudMetadata() {
        assertThrows(SecurityException::class.java) {
            NetworkSecurityValidator.assertSafeUrl("http://localhost:8080/sub.srt")
        }
        assertThrows(SecurityException::class.java) {
            NetworkSecurityValidator.assertSafeUrl("http://127.0.0.1:3000/api")
        }
        assertThrows(SecurityException::class.java) {
            NetworkSecurityValidator.assertSafeUrl("http://127.0.0.99:80/stream")
        }
        assertThrows(SecurityException::class.java) {
            NetworkSecurityValidator.assertSafeUrl("http://169.254.169.254/latest/meta-data")
        }
        assertThrows(SecurityException::class.java) {
            NetworkSecurityValidator.assertSafeUrl("http://metadata.google.internal/computeMetadata/v1")
        }
    }

    @Test
    fun networkSecurityValidator_allowsLocalIptvAndPublicUrls() {
        // Legitimate private network IPTV servers MUST NOT be blocked
        NetworkSecurityValidator.assertSafeUrl("http://192.168.1.100:8080/player_api.php")
        NetworkSecurityValidator.assertSafeUrl("http://10.0.0.5:25461/live")
        NetworkSecurityValidator.assertSafeUrl("http://172.16.2.10:8000/playlist.m3u8")
        NetworkSecurityValidator.assertSafeUrl("https://subtitles.org/download/arabic.srt")
    }

    @Test
    fun isBlockedAddress_verifiesSubnets() {
        assertTrue(NetworkSecurityValidator.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(NetworkSecurityValidator.isBlockedAddress(InetAddress.getByName("127.1.2.3")))
        assertTrue(NetworkSecurityValidator.isBlockedAddress(InetAddress.getByName("169.254.1.1")))
        assertTrue(NetworkSecurityValidator.isBlockedAddress(InetAddress.getByName("169.254.169.254")))

        // Private LAN addresses are allowed
        assertFalse(NetworkSecurityValidator.isBlockedAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(NetworkSecurityValidator.isBlockedAddress(InetAddress.getByName("10.0.0.1")))
        assertFalse(NetworkSecurityValidator.isBlockedAddress(InetAddress.getByName("172.16.0.1")))
    }

    // --- 4. CredentialVault Version 2 & Migration Tests ---

    @Test
    fun credentialVault_v2SaveAndRead() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault = CredentialVault(context)
        vault.clear()

        vault.save("http://my-iptv.com:8080", "sonix_user", "sonix_password")
        val loaded = vault.read()

        assertNotNull(loaded)
        assertEquals("http://my-iptv.com:8080", loaded!!.server)
        assertEquals("sonix_user", loaded.username)
        assertEquals("sonix_password", loaded.password)

        vault.clear()
        assertNull(vault.read())
    }
}

package com.example

import com.example.audio.AudioChannelGroup
import com.example.audio.AudioSourceClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioAddonEngineTest {
    @Test
    fun classify_beInSports_variants() {
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("beIN SPORTS 1 HD", "SPORTS"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("بي ان سبورت 1", "الرياضة"))
    }

    @Test
    fun classify_alwan_variants() {
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("ALWAN 1 HD", "Audio Commentary"))
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("صوتية ألوان 1", "قنوات صوتية"))
    }

    @Test
    fun classify_unrelatedChannels_asOther() {
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("SSC 1 HD", "Saudi Sports"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Rotana Cinema", "Movies"))
    }
}

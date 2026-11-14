package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourceClassifierTest {
    @Test
    fun acceptsOnlyExplicitBeinBrandTokens() {
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("beIN SPORTS 1"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("Sports 2", "beIN SPORTS"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("بي ان سبورت 1"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Sports 1", "Sports"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Cinema Beinspired"))
    }

    @Test
    fun acceptsOnlyExplicitAlwanBrandTokens() {
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("ALWAN HD"))
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("قناة 1", "قنوات الوان"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Alwando"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Movies", "Drama", "alwan"))
    }

    @Test
    fun ignoresOpaqueProviderCategoryIds() {
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Sports 1", "General", "bein"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Cinema", "Movies", "alwan"))
    }
}

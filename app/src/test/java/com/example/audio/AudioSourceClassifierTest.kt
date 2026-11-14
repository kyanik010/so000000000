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
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("beIN SPORTS Documentary", "Movies"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("ALWAN Series", "مسلسلات"))
    }

    @Test
    fun acceptsOnlyExplicitAlwanBrandTokens() {
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("ALWAN HD"))
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("قناة 1", "قنوات الوان"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Alwando"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Movies", "Drama", "alwan"))
    }

    @Test
    fun rejectsMoviesAndEpisodesEvenWhenTheirTitleContainsSupportedBrand() {
        assertEquals(
            AudioChannelGroup.OTHER,
            AudioSourceClassifier.classify("beIN SPORTS S01E08 مترجم - عربي - انحليزي - Pretty Little Liars Original Sin .322")
        )
        assertEquals(
            AudioChannelGroup.OTHER,
            AudioSourceClassifier.classify("beIN SPORTS S01E09 مترجم - عربي - انحليزي - Pretty Little Liars Original Sin .323")
        )
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("ALWAN The Movie", "General"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("beIN SPORTS 1", "TV Series"))
        assertEquals(AudioChannelGroup.BEIN_SPORTS, AudioSourceClassifier.classify("beIN SPORTS 1 HD"))
        assertEquals(AudioChannelGroup.ALWAN, AudioSourceClassifier.classify("ALWAN 2 SD"))
    }

    @Test
    fun sortsQualityFromLowestToHighestAndLeavesUnknownLast() {
        assertEquals(0, AudioSourceClassifier.qualityRank("beIN SPORTS 1 SD"))
        assertEquals(1, AudioSourceClassifier.qualityRank("beIN SPORTS 1 360p"))
        assertEquals(2, AudioSourceClassifier.qualityRank("beIN SPORTS 1 480p"))
        assertEquals(3, AudioSourceClassifier.qualityRank("beIN SPORTS 1 576p"))
        assertEquals(4, AudioSourceClassifier.qualityRank("beIN SPORTS 1 HD"))
        assertEquals(5, AudioSourceClassifier.qualityRank("beIN SPORTS 1 1080p"))
        assertEquals(6, AudioSourceClassifier.qualityRank("beIN SPORTS 1 QHD"))
        assertEquals(7, AudioSourceClassifier.qualityRank("beIN SPORTS 1 4K"))
        assertEquals(8, AudioSourceClassifier.qualityRank("beIN SPORTS 1 8K"))
        assertEquals(99, AudioSourceClassifier.qualityRank("beIN SPORTS 1"))
    }

    @Test
    fun ignoresOpaqueProviderCategoryIds() {
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Sports 1", "General", "bein"))
        assertEquals(AudioChannelGroup.OTHER, AudioSourceClassifier.classify("Cinema", "Movies", "alwan"))
    }
}

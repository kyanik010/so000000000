package com.example.audio

/**
 * Strict allow-list classifier for the two supported external audio brands.
 * Only explicit brand tokens in the channel name or category are accepted.
 * Provider category IDs are opaque identifiers and are never classification input.
 */
object AudioSourceClassifier {
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670]")
    private val separators = Regex("[^a-z0-9\\u0600-\\u06FF]+")

    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim()
            .replace(diacritics, "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .lowercase()
            .replace(separators, " ")
            .trim()
    }

    private fun hasPhrase(text: String, phrase: String): Boolean {
        val normalizedPhrase = normalize(phrase)
        return text.split(' ').windowed(
            size = normalizedPhrase.split(' ').size,
            partialWindows = false
        ).any { it.joinToString(" ") == normalizedPhrase }
    }

    private val nonLiveTerms = listOf(
        "movie", "movies", "film", "films", "cinema", "series", "serial", "episode", "episodes",
        "season", "vod", "مشاهدة", "فيلم", "افلام", "سينما", "مسلسل", "مسلسلات",
        "حلقة", "حلقات", "موسم", "الموسم"
    )
    private val episodePattern = Regex(
        """(^| )(s[0-9]{1,2}e[0-9]{1,3}|season [0-9]+ episode [0-9]+|episode [0-9]+)( |$)"""
    )

    fun classify(name: String?, categoryName: String? = null, categoryId: String? = null): AudioChannelGroup {
        val normalizedName = normalize(name)
        val normalizedCategory = normalize(categoryName)
        val text = listOf(normalizedName, normalizedCategory)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (text.isBlank()) return AudioChannelGroup.OTHER

        // Reject VOD/series evidence in BOTH the title and the provider category.
        // This prevents a movie/episode whose title happens to contain "beIN SPORTS"
        // from being mistaken for a live TV channel.
        if (nonLiveTerms.any { hasPhrase(text, it) } || episodePattern.containsMatchIn(text)) {
            return AudioChannelGroup.OTHER
        }

        val beinPhrases = listOf(
            "bein sports", "bein sport", "beinsports", "beinsport",
            "be in sports", "be in sport",
            "بي ان سبورت", "بين سبورت", "بين سبورتس", "بيان سبورت"
        )
        if (beinPhrases.any { hasPhrase(text, it) }) return AudioChannelGroup.BEIN_SPORTS

        val alwanPhrases = listOf("alwan", "al wan", "elwan", "الوان")
        if (alwanPhrases.any { hasPhrase(text, it) }) return AudioChannelGroup.ALWAN

        return AudioChannelGroup.OTHER
    }

    /**
     * Ascending quality rank: SD/low resolutions first, then HD/FHD/UHD/4K/8K.
     * Unknown quality is placed last rather than guessed to be SD.
     */
    fun qualityRank(name: String?, categoryName: String? = null): Int {
        val text = normalize(listOf(name, categoryName).filterNotNull().joinToString(" "))
        val qualityLevels = listOf(
            0 to Regex("""(^| )sd( |$)"""),
            1 to Regex("""(^| )360p?( |$)"""),
            2 to Regex("""(^| )480p?( |$)"""),
            3 to Regex("""(^| )576p?( |$)"""),
            4 to Regex("""(^| )(720p?|hd)( |$)"""),
            5 to Regex("""(^| )(1080p?|fhd|full hd)( |$)"""),
            6 to Regex("""(^| )(1440p?|qhd)( |$)"""),
            7 to Regex("""(^| )(2160p?|uhd|4k)( |$)"""),
            8 to Regex("""(^| )(4320p?|8k)( |$)""")
        )
        return qualityLevels.filter { (_, pattern) -> pattern.containsMatchIn(text) }
            .maxOfOrNull { it.first } ?: 99
    }

    fun isAudioCandidate(name: String?, categoryName: String? = null, categoryId: String? = null): Boolean =
        classify(name, categoryName, categoryId) != AudioChannelGroup.OTHER
}

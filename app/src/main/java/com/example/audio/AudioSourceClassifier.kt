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

    fun classify(name: String?, categoryName: String? = null, categoryId: String? = null): AudioChannelGroup {
        val normalizedCategory = normalize(categoryName)
        val nonLiveCategoryTerms = listOf(
            "movie", "movies", "film", "films", "series", "episode", "episodes", "vod",
            "فيلم", "افلام", "مسلسل", "مسلسلات", "حلقة", "حلقات"
        )
        if (nonLiveCategoryTerms.any { hasPhrase(normalizedCategory, it) }) {
            return AudioChannelGroup.OTHER
        }

        val text = listOf(normalize(name), normalizedCategory)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (text.isBlank()) return AudioChannelGroup.OTHER

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

    fun isAudioCandidate(name: String?, categoryName: String? = null, categoryId: String? = null): Boolean =
        classify(name, categoryName, categoryId) != AudioChannelGroup.OTHER
}

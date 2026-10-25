package com.mangareader.pipeline

import com.mangareader.database.TokenEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingCorrectionsTest {
    private fun token(page: String, region: Long, position: Int, surface: String) = TokenEntity(
        pageId = page,
        regionId = region,
        position = position,
        surface = surface,
        reading = "ふるい",
        baseForm = surface,
        baseReading = "ふるい",
        partOfSpeech = "名詞",
        posLabel = "noun",
        furigana = "",
        romaji = "furui",
        jmdictEntryId = null,
        glosses = "",
        isContentWord = true,
    )

    @Test fun correctedReadingSurvivesMatchingRetokenizationOnlyInItsBubble() {
        val saved = ReadingCorrections.corrected(token("page", 1, 0, "今日"), "きょう")
        assertEquals("きょう", saved.reading)
        assertEquals("kyou", saved.romaji)
        assertEquals("きょう", saved.toToken().furigana.single().ruby)

        val sameBubble = ReadingCorrections.reapply(
            listOf(saved), listOf(token("page", 1, 0, "今日")),
        ).single()
        assertEquals("きょう", sameBubble.userReading)

        val otherBubble = ReadingCorrections.reapply(
            emptyList(), listOf(token("page", 2, 0, "今日")),
        ).single()
        assertNull(otherBubble.userReading)
        assertEquals("ふるい", otherBubble.reading)

        val changedWord = ReadingCorrections.reapply(
            listOf(saved), listOf(token("page", 1, 0, "昨日")),
        ).single()
        assertNull(changedWord.userReading)
    }
}

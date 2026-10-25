package com.mangareader.pipeline

import com.mangareader.analysis.Furigana
import com.mangareader.analysis.Romaji
import com.mangareader.database.TokenEntity

/** A reading belongs to one stored token, not to every occurrence of the same word. */
internal object ReadingCorrections {
    fun corrected(token: TokenEntity, reading: String): TokenEntity =
        TokenEntity.of(
            token.pageId,
            token.regionId,
            token.toToken().copy(
                reading = reading,
                furigana = Furigana.align(token.surface, reading),
                romaji = Romaji.of(reading),
            ),
        ).copy(userReading = reading)

    fun reapply(previous: List<TokenEntity>, generated: List<TokenEntity>, text: String? = null): List<TokenEntity> {
        var offset = 0
        val overrides = previous.map { row ->
            val start = row.start.takeIf { it >= 0 } ?: text?.indexOf(row.surface, offset)
                ?.takeIf { it >= 0 } ?: offset
            offset = start + row.surface.length
            (start to row.surface) to row.userReading
        }.filter { it.second != null }.toMap()
        var generatedOffset = 0
        return generated.map { token ->
            val start = token.start.takeIf { it >= 0 } ?: generatedOffset
            generatedOffset = start + token.surface.length
            overrides[start to token.surface]?.let {
                corrected(token, it)
            } ?: token
        }
    }
}

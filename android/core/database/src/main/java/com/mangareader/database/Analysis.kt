package com.mangareader.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mangareader.model.Gloss
import com.mangareader.model.Ruby
import com.mangareader.model.SfxMatch
import com.mangareader.model.Token

/**
 * Stage 6's persisted output -- section 6's `Token` and `SavedWord` entities.
 *
 * Section 6 lists eight columns on `Token`; this carries five more (`posLabel`,
 * `baseReading`, `furigana`, `romaji`, `glosses`) because of section 3.5: "each page
 * stores the corrected image plus all pipeline output so reopening is instant". The
 * alternative -- storing the tokenizer's output and re-deriving the display layer on
 * every sheet open -- means a JMdict query per token on the UI thread's timeline, and
 * the sheet is the screen the whole app exists to open.
 *
 * That also means the bubble sheet renders on a device where the dictionary asset is
 * still unpacking. The glosses will be empty until the page is re-analysed, which the
 * reader offers, but furigana and the breakdown are already there.
 */
@Entity(
    tableName = "tokens",
    primaryKeys = ["pageId", "regionId", "position"],
    indices = [Index("pageId"), Index("pageId", "regionId")],
    foreignKeys = [
        ForeignKey(
            entity = RegionEntity::class,
            parentColumns = ["pageId", "regionId"],
            childColumns = ["pageId", "regionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TokenEntity(
    val pageId: String,
    val regionId: Long,
    val position: Int,
    val surface: String,
    /** Hiragana, of the surface form as it appears on the page. */
    val reading: String,
    val baseForm: String,
    /** Hiragana of the dictionary form -- what a saved word needs. */
    val baseReading: String,
    /** The tokenizer's full IPADIC tag string, all four levels. */
    val partOfSpeech: String,
    val posLabel: String,
    /** (text, ruby) segments, [Serial]-encoded. */
    val furigana: String,
    val romaji: String,
    val jmdictEntryId: Long?,
    /** [Gloss] rows, [Serial]-encoded. */
    val glosses: String,
    val isContentWord: Boolean,
    /**
     * IPADIC's 活用形 and 活用型, both defaulted so the v7 rows migrate clean.
     *
     * These are here because tier 4 reads them and the sheet opens over stored tokens, not
     * over a live tokenizer run. Before v8 `conjugationForm` was derived at stage 6 and
     * then dropped on the way to the table, so an imperative was invisible to every rule
     * keyed on it the moment the page was reloaded -- the rules fired during the analysis
     * that captured the page and never again. Nothing surfaced that, because the register
     * verdict it changed was one word in a header.
     */
    val conjugationForm: String = "",
    val conjugationType: String = "",
    /** A correction made in this bubble; null means use the analyser's reading. */
    val userReading: String? = null,
    @ColumnInfo(defaultValue = "-1") val start: Int = -1,
    @ColumnInfo(defaultValue = "-1") val end: Int = -1,
) {
    fun toToken() = Token(
        position = position,
        surface = surface,
        reading = reading,
        baseForm = baseForm,
        baseReading = baseReading,
        partOfSpeech = partOfSpeech,
        posLabel = posLabel,
        furigana = Serial.decodeFurigana(furigana),
        romaji = romaji,
        jmdictEntryId = jmdictEntryId,
        glosses = Serial.decodeGlosses(glosses),
        isContentWord = isContentWord,
        conjugationForm = conjugationForm,
        conjugationType = conjugationType,
        start = start,
        end = end,
        readingIsUser = userReading != null,
    )

    companion object {
        fun of(pageId: String, regionId: Long, token: Token) = TokenEntity(
            pageId = pageId,
            regionId = regionId,
            position = token.position,
            surface = token.surface,
            reading = token.reading,
            baseForm = token.baseForm,
            baseReading = token.baseReading,
            partOfSpeech = token.partOfSpeech,
            posLabel = token.posLabel,
            furigana = Serial.encodeFurigana(token.furigana),
            romaji = token.romaji,
            jmdictEntryId = token.jmdictEntryId,
            glosses = Serial.encodeGlosses(token.glosses),
            isContentWord = token.isContentWord,
            conjugationForm = token.conjugationForm,
            conjugationType = token.conjugationType,
            start = token.start,
            end = token.end,
        )
    }
}

/**
 * Section 6's `SavedWord`, behind section 3.3's "tapping a token pins it".
 *
 * Keyed on the dictionary form and its reading rather than on the JMdict entry id: a
 * token the dictionary could not place still deserves to be pinnable, and 見る saved
 * from two different pages is one word, not two.
 */
@Entity(
    tableName = "saved_words",
    indices = [Index(value = ["baseForm", "reading"], unique = true), Index("savedAt")],
)
data class SavedWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseForm: String,
    val reading: String,
    val gloss: String,
    val posLabel: String,
    val jmdictEntryId: Long?,
    val sourcePageId: String?,
    val sourceRegionId: Long?,
    /** The sentence it was pinned from, for review context. */
    val context: String,
    val savedAt: Long,
)

/**
 * The delimited encoding the non-primitive analysis columns use.
 *
 * Same reasoning as [Converters]: these are never queried, only ever read back whole,
 * and JSON would put a serialization dependency into a module that has none. Unit
 * separator between fields, record separator between rows -- both are control
 * characters that cannot occur in JMdict glosses or in recognized Japanese.
 */
internal object Serial {
    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'

    fun encodeFurigana(segments: List<Ruby>): String =
        segments.joinToString(RECORD.toString()) { it.text + FIELD + it.ruby }

    fun decodeFurigana(value: String): List<Ruby> = split(value).map { row ->
        val parts = row.split(FIELD)
        Ruby(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
    }

    fun encodeGlosses(glosses: List<Gloss>): String = glosses.joinToString(RECORD.toString()) {
        listOf(it.entryId.toString(), it.pos, it.misc, it.text, if (it.common) "1" else "0")
            .joinToString(FIELD.toString())
    }

    fun decodeGlosses(value: String): List<Gloss> = split(value).mapNotNull { row ->
        val parts = row.split(FIELD)
        if (parts.size < 5) return@mapNotNull null
        Gloss(
            entryId = parts[0].toLongOrNull() ?: 0L,
            pos = parts[1],
            misc = parts[2],
            text = parts[3],
            common = parts[4] == "1",
        )
    }

    fun encodeSfx(sfx: SfxMatch?): String? = sfx?.let {
        listOf(it.text, it.reading, it.kind, it.gloss, it.match).joinToString(FIELD.toString())
    }

    fun decodeSfx(value: String?): SfxMatch? {
        val parts = value?.split(FIELD) ?: return null
        if (parts.size < 5) return null
        return SfxMatch(parts[0], parts[1], parts[2], parts[3], parts[4])
    }

    private fun split(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(RECORD)
}

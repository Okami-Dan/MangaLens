package com.mangareader.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stage 7's cache -- section 7's "cache by hash of the ordered bubble-text list + model
 * identifier. Re-opening a page must never re-bill."
 *
 * The translations themselves live on [RegionEntity], which is what the sheet reads.
 * This table exists for the case that column cannot answer: the user switches translator,
 * translates again, then switches back. Without it that second switch is a second call to
 * a paid endpoint for a page whose answer is already known, or a second minute of on-device
 * inference. Keyed by `Translate.cacheKey`, so it is per (page content, cover flag, model)
 * and an OCR correction invalidates only the page it touched.
 *
 * This is the port of `TranslationCache` in `harness/pipeline/translate.py`, which does
 * the same thing in a JSON file next to the harness database.
 */
@Entity(
    tableName = "translations",
    indices = [Index("pageId")],
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranslationCacheEntity(
    /** `Translate.cacheKey(regions, translatorId, cover)`. */
    @PrimaryKey val key: String,
    val pageId: String,
    val translatorId: String,
    /** Region id -> English, encoded by [TranslationSerial]. */
    val results: String,
    val at: Long,
) {
    /** Region id -> English, or null where the model explicitly declined the bubble. */
    fun results(): Map<Long, String?> = TranslationSerial.decode(results)

    companion object {
        fun of(
            key: String,
            pageId: String,
            translatorId: String,
            results: Map<Long, String?>,
        ) = TranslationCacheEntity(
            key = key,
            pageId = pageId,
            translatorId = translatorId,
            results = TranslationSerial.encode(results),
            at = System.currentTimeMillis(),
        )
    }
}

/**
 * The results map, encoded the way the rest of this module encodes small collections.
 *
 * Not JSON, for the reason the note on [Converters] gives: `:core:database` needs no JSON
 * dependency and this does not justify one. The three-field record is what buys the
 * distinction the sheet depends on -- a region the model *declined* to translate is
 * stored, with a false flag, and is a different thing from a region that is simply absent
 * because the model never mentioned it.
 */
internal object TranslationSerial {
    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'

    fun encode(results: Map<Long, String?>): String =
        results.entries.joinToString(RECORD.toString()) { (id, english) ->
            listOf(
                id.toString(),
                if (english == null) "0" else "1",
                english.orEmpty(),
            ).joinToString(FIELD.toString())
        }

    fun decode(value: String): Map<Long, String?> {
        if (value.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, String?>()
        value.split(RECORD).forEach { row ->
            val parts = row.split(FIELD)
            if (parts.size < 3) return@forEach
            val id = parts[0].toLongOrNull() ?: return@forEach
            out[id] = if (parts[1] == "1") parts.drop(2).joinToString(FIELD.toString()) else null
        }
        return out
    }
}

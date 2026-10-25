package com.mangareader.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mangareader.model.GrammarAnswer

/**
 * Section 3.3 tier 4's cache, for the half of the breakdown that costs something.
 *
 * **Only the model's answer is stored.** The offline half -- the phrase cards, the
 * per-token relations, the dependency edges, the register verdict and the rule-table
 * patterns -- is a pure function of [TokenEntity] rows this database already holds, and it
 * runs in microseconds. Storing it would buy a second migration, a re-analysis pass over
 * the whole library every time a rule is added, and rows that quietly disagree with the
 * shipped table. Recomputing when the sheet opens costs nothing and is always current.
 *
 * What is worth storing is the other half, for the same reason [TranslationCacheEntity]
 * exists: an answer that cost money on tier 2, or a minute of on-device inference on tier
 * 1, must not be paid for twice because the user closed a bubble and opened it again.
 *
 * The table and its columns are unchanged from v7 -- what a row *contains* changed shape,
 * and that is handled where it belongs, by the schema version folded into
 * `grammarCacheKey`. An old row is now unreachable rather than misread.
 */
@Entity(
    tableName = "grammar_notes",
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
data class GrammarNoteEntity(
    /**
     * SHA-256 over the region's text, the explainer id and the stored shape's version.
     *
     * Keyed on the **text**, not the region id, which is the rule `Translate.cacheKey`
     * already follows: correcting the OCR for one bubble should invalidate that bubble's
     * notes and nothing else. Keying on the id would serve notes about the old reading
     * after the user fixed it, which is worse than having none.
     */
    @PrimaryKey val key: String,
    val pageId: String,
    val regionId: Long,
    val explainerId: String,
    /** The answer, encoded by [GrammarSerial]. */
    val notes: String,
    val at: Long,
) {
    fun answer(): GrammarAnswer = GrammarSerial.decode(notes)

    companion object {
        fun of(
            key: String,
            pageId: String,
            regionId: Long,
            explainerId: String,
            answer: GrammarAnswer,
        ) = GrammarNoteEntity(
            key = key,
            pageId = pageId,
            regionId = regionId,
            explainerId = explainerId,
            notes = GrammarSerial.encode(answer),
            at = System.currentTimeMillis(),
        )
    }
}

/**
 * A [GrammarAnswer], in the delimited encoding the rest of this module uses.
 *
 * Not JSON, for the reason [Serial] gives: these are never queried, only read back whole,
 * and `:core:database` has no serialization dependency and does not need one for two maps
 * of primitives.
 *
 * An explanation is model-written prose and could contain anything a model emits -- but
 * not U+001D, U+001E or U+001F, which are control characters no generator produces and
 * which the same argument already covers for JMdict glosses. The third separator is new
 * here: the answer holds two independent maps, and a group boundary is cheaper than
 * tagging every record with which map it belongs to.
 */
internal object GrammarSerial {
    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'
    private const val GROUP = '\u001d'

    fun encode(answer: GrammarAnswer): String {
        val explanations = answer.explanations.entries
            .sortedBy { it.key }
            .joinToString(RECORD.toString()) { (token, prose) ->
                token.toString() + FIELD + prose
            }
        val heads = answer.heads.entries
            .sortedBy { it.key }
            .joinToString(RECORD.toString()) { (phrase, head) ->
                phrase.toString() + FIELD + head.toString()
            }
        val meanings = answer.meanings.entries.sortedBy { it.key }
            .joinToString(RECORD.toString()) { (word, prose) -> word.toString() + FIELD + prose }
        return explanations + GROUP + heads + GROUP + meanings
    }

    fun decode(value: String): GrammarAnswer {
        if (value.isEmpty()) return GrammarAnswer.NONE
        val groups = value.split(GROUP)

        val explanations = rows(groups.getOrNull(0)).mapNotNull { parts ->
            val token = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val prose = parts.getOrNull(1).orEmpty()
            if (prose.isEmpty()) null else token to prose
        }.toMap()

        val heads = rows(groups.getOrNull(1)).mapNotNull { parts ->
            val phrase = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val head = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            phrase to head
        }.toMap()

        val meanings = rows(groups.getOrNull(2)).mapNotNull { parts ->
            val word = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val prose = parts.getOrNull(1).orEmpty()
            if (prose.isBlank()) null else word to prose
        }.toMap()
        return GrammarAnswer(explanations, heads, meanings)
    }

    private fun rows(group: String?): List<List<String>> {
        if (group.isNullOrEmpty()) return emptyList()
        return group.split(RECORD).map { it.split(FIELD) }
    }
}

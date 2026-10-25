package com.mangareader.analysis

import com.mangareader.model.Token
import org.json.JSONArray
import org.json.JSONObject

/** Immutable, exact-spelling grammatical senses; never a vocabulary/reading lookup. */
class GrammarLexicon private constructor(
    private val forms: Map<String, List<Form>>,
    private val senses: Map<Int, List<Sense>>,
    private val readings: Map<Int, List<Form>>,
    private val spellings: Map<Int, Set<String>>,
) {
    private data class Form(val id: Int, val text: String, val kind: String, val appliesTo: Set<String>)
    private data class Sense(val id: Int, val position: Int, val pos: Set<String>, val meaning: String,
        val kanji: Set<String>, val reading: Set<String>)
    data class Meaning(val entryId: Int, val sensePosition: Int, val text: String)

    fun meanings(token: Token): List<Meaning> {
        val allowed = compatibleTags(token.partOfSpeech)
        if (allowed.isEmpty()) return emptyList()
        fun lookup(written: String): List<Meaning> = forms[written].orEmpty().flatMap { form ->
            // Reading validates restrictions on an exact spelling. It never finds an entry.
            val reading = Kana.toHiragana(if (written == token.surface) token.reading
                else token.baseReading.ifEmpty { token.reading })
            val candidates = readings[form.id].orEmpty().filter { candidate ->
                (candidate.appliesTo.isEmpty() || written in candidate.appliesTo || form.kind == "kana") &&
                    (reading.isEmpty() || Kana.toHiragana(candidate.text) == reading)
            }
            senses[form.id].orEmpty().filter { sense ->
                sense.pos.any { it in allowed } && sense.meaning.isNotBlank() &&
                    (sense.kanji.isEmpty() || (form.kind == "kanji" && written in sense.kanji) ||
                        (form.kind == "kana" && (if (form.appliesTo.isEmpty()) spellings[form.id].orEmpty()
                            else form.appliesTo).any { it in sense.kanji })) &&
                    (sense.reading.isEmpty() || candidates.any { it.text in sense.reading }) &&
                    (form.appliesTo.isEmpty() || form.kind != "kana" ||
                        sense.kanji.isEmpty() || form.appliesTo.any { it in sense.kanji })
            }.map { Meaning(it.id, it.position, it.meaning) }
        }
        val exact = lookup(token.surface)
        if (exact.isNotEmpty()) return exact.distinctBy { it.text }
        if (token.baseForm == token.surface || token.conjugationForm in setOf("", "*") ||
            token.conjugationType in setOf("", "*")) return emptyList()
        return lookup(token.baseForm).distinctBy { it.text }
    }

    companion object {
        val EMPTY = GrammarLexicon(emptyMap(), emptyMap(), emptyMap(), emptyMap())

        internal fun compatibleTags(pos: String): Set<String> {
            val p = pos.split(",")
            return when {
                p.first() == "助詞" -> if (p.getOrNull(2) == "連語") setOf("prt", "exp") else setOf("prt")
                p.first() == "接頭詞" -> setOf("pref", "n-pref")
                p.first() == "助動詞" -> setOf("aux", "aux-v", "aux-adj", "cop")
                p.getOrNull(1) == "接尾" -> when {
                    p.getOrNull(2) == "助数詞" -> setOf("ctr")
                    p.first() == "動詞" -> setOf("suf", "v-suf", "aux-v", "aux")
                    p.first() == "形容詞" -> setOf("suf", "adj-suf", "aux-adj")
                    else -> setOf("suf", "n-suf")
                }
                p.getOrNull(1) == "非自立" && p.first() == "動詞" -> setOf("aux", "aux-v")
                p.getOrNull(1) == "非自立" && p.first() == "形容詞" -> setOf("aux", "aux-adj")
                else -> emptySet()
            }
        }

        fun parse(json: String): GrammarLexicon {
            val root = JSONObject(json)
            val forms = root.getJSONArray("forms").objects().map { f ->
                Form(f.getInt("entryId"), f.getString("text"), f.getString("kind"), f.getJSONArray("appliesTo").strings())
            }
            val senses = root.getJSONArray("senses").objects().map { s ->
                Sense(s.getInt("entryId"), s.getInt("position"), s.getJSONArray("pos").strings(),
                    s.getString("meaning"), s.getJSONArray("kanjiRestrictions").strings(),
                    s.getJSONArray("readingRestrictions").strings())
            }
            return GrammarLexicon(forms.groupBy { it.text }, senses.groupBy { it.id },
                forms.filter { it.kind == "kana" }.groupBy { it.id },
                forms.filter { it.kind == "kanji" }.groupBy { it.id }.mapValues { (_, f) -> f.map { it.text }.toSet() })
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()
    }
}

package com.mangareader.analysis

import com.mangareader.model.GrammarNote
import com.mangareader.model.NoteSource
import com.mangareader.model.RegisterLevel
import com.mangareader.model.Token
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled grammar pattern table, and the matcher over it.
 *
 * Ported from `tools/check_grammar_rules.py`, which is the reference implementation and
 * the only place these rules are actually tested against a tokenizer. The source is
 * `tools/grammar_rules.json`, copied into the APK's assets by the `:app` build exactly
 * the way `tools/sfx_dict.json` is -- same static factory, same `meta` map, same
 * `org.json` parsing, and no new dependency in a module that has none.
 *
 * **The rules are data, not code, on purpose.** There is no off-the-shelf Japanese
 * grammar-point matcher to depend on -- every project in this space writes its own table
 * over MeCab output -- so this app has to carry one, and a table that needs a rebuild to
 * add 〜ますまい would never grow. Adding a pattern is a JSON edit and a run of
 * `check_grammar_rules.py`.
 *
 * Two things about IPADIC make writing these rules a matter of measurement rather than
 * intuition, and both are why the Python exists:
 *
 *  - **Its segmentation is not the one you would guess.** によって is a single 助詞 token,
 *    not に + よる + て. なきゃ is a single 助動詞 whose base form is ない. たら bases to
 *    た, and なら bases to だ. A rule written from memory usually matches nothing.
 *  - **The conjugation form is a separate field.** 来い and 来る are both 動詞,自立; only
 *    [Token.conjugationForm] tells them apart. Every imperative in a manga page is
 *    invisible to a matcher that reads the tag string alone.
 */
class GrammarRules private constructor(
    private val rules: List<Rule>,
    private val register: List<RegisterRule>,
    val meta: Map<String, String>,
) {

    val size: Int get() = rules.size

    /**
     * Whether there is a register table to consult at all.
     *
     * [Register] needs this to tell its two silences apart. A full table that matches
     * nothing means the line genuinely carries no politeness marking; an empty one means
     * the asset is missing and nothing was asked. Both produce no hits, and only one of
     * them is something to tell the reader.
     */
    val hasRegisterRules: Boolean get() = register.isNotEmpty()

    /**
     * One constraint on one token. An absent field does not constrain.
     *
     * [pos] and [conjugationForm] are **prefix** matches: `動詞,接尾` matches the full
     * `動詞,接尾,*,*`, and `命令` matches 命令ｅ / 命令ｒｏ / 命令ｉ -- whose latin tails are
     * full-width, which is another thing not to try to spell from memory.
     */
    data class Element(
        val surface: String? = null,
        val base: String? = null,
        val reading: String? = null,
        val pos: String? = null,
        val posNot: String? = null,
        val conjugationForm: String? = null,
        val conjugationType: String? = null,
    ) {
        /** How many constraints this carries; [specificity] adds them up. */
        val weight: Int
            get() = listOfNotNull(surface, base, reading, pos, posNot, conjugationForm, conjugationType).size

        fun matches(token: Token?): Boolean {
            if (token == null) return false
            if (surface != null && surface != token.surface) return false
            if (base != null && base != token.baseForm) return false
            if (reading != null && reading != token.reading) return false
            if (pos != null && !token.partOfSpeech.startsWith(pos)) return false
            if (posNot != null && token.partOfSpeech.startsWith(posNot)) return false
            if (conjugationType != null && !token.conjugationType.startsWith(conjugationType)) return false
            if (conjugationForm != null &&
                !token.conjugationForm.startsWith(conjugationForm)
            ) {
                return false
            }
            return true
        }
    }

    data class Rule(
        val id: String,
        val fragment: String,
        val explanation: String,
        val match: List<Element>,
        val gloss: String = "",
        val jlpt: String = "",
        val source: NoteSource = NoteSource.PATTERN,
        /** Constrains the token before the match, without consuming it. */
        val after: Element? = null,
        /** Constrains the token after the match, without consuming it. */
        val before: Element? = null,
        /** The match must end on the region's last token. */
        val atEnd: Boolean = false,
    )

    data class RegisterRule(
        val level: RegisterLevel,
        val note: String,
        val match: List<Element>,
        val after: Element? = null,
        val before: Element? = null,
        val atEnd: Boolean = false,
    )

    // ----------------------------------------------------------------------------------
    // Matching

    /**
     * Where a rule matches starting at [start], or -1.
     *
     * Returns the inclusive end index so the caller can compare match lengths without
     * re-deriving them.
     */
    private fun endOfMatch(
        match: List<Element>,
        after: Element?,
        before: Element?,
        atEnd: Boolean,
        tokens: List<Token>,
        start: Int,
    ): Int {
        val end = start + match.size - 1
        if (end >= tokens.size) return -1
        match.forEachIndexed { offset, element ->
            if (!element.matches(tokens[start + offset])) return -1
        }
        if (after != null && (start == 0 || !after.matches(tokens[start - 1]))) return -1
        if (before != null && !before.matches(tokens.getOrNull(end + 1))) return -1
        if (atEnd && end != tokens.lastIndex) return -1
        return end
    }

    private fun endOfMatch(rule: Rule, tokens: List<Token>, start: Int): Int =
        endOfMatch(rule.match, rule.after, rule.before, rule.atEnd, tokens, start)

    private fun endOfMatch(rule: RegisterRule, tokens: List<Token>, start: Int): Int =
        endOfMatch(rule.match, rule.after, rule.before, rule.atEnd, tokens, start)

    /**
     * How constrained a rule is, for breaking length ties.
     *
     * なきゃ and ない are both one token and both match 行かなきゃ, so length cannot
     * separate them and whichever sat earlier in the file would win -- which is how the
     * generic negative rule silently swallowed the obligation rule the first time this
     * ran. The rule that asks for more is the rule that meant it.
     */
    private fun specificity(rule: Rule): Int =
        rule.match.sumOf { it.weight } +
            (rule.after?.weight ?: 0) +
            (rule.before?.weight ?: 0) +
            (if (rule.atEnd) 1 else 0)

    /**
     * Longest match wins at each position, then most specific. Matches do not overlap and
     * the scan runs left to right.
     */
    fun notes(tokens: List<Token>): List<GrammarNote> {
        val sentences = mutableListOf<MutableList<Token>>(mutableListOf())
        tokens.forEach { token ->
            if (token.surface in setOf("。", "！", "？", "!", "?")) {
                sentences += mutableListOf<Token>()
            } else sentences.last() += token
        }
        return sentences.filter { it.isNotEmpty() }.flatMap { sentence ->
            sentenceNotes(sentence).map { note -> note.copy(from = sentence[note.from].position,
                to = sentence[note.to].position) }
        }
    }

    private fun sentenceNotes(tokens: List<Token>): List<GrammarNote> {
        val out = mutableListOf<GrammarNote>()
        var position = 0
        while (position < tokens.size) {
            var best: Rule? = null
            var bestEnd = -1
            rules.forEach { rule ->
                val end = endOfMatch(rule, tokens, position)
                if (end < 0) return@forEach
                val better = when {
                    best == null -> true
                    end > bestEnd -> true
                    end == bestEnd -> specificity(rule) > specificity(best!!)
                    else -> false
                }
                if (better) {
                    best = rule
                    bestEnd = end
                }
            }
            val rule = best
            if (rule == null) {
                position += 1
                continue
            }
            out += GrammarNote(
                fragment = interpolate(rule.fragment, tokens, position, bestEnd),
                from = position,
                to = bestEnd,
                gloss = rule.gloss,
                explanation = interpolate(rule.explanation, tokens, position, bestEnd),
                source = rule.source,
                jlpt = rule.jlpt,
            )
            position = bestEnd + 1
        }
        return out
    }

    /**
     * Every register rule that fires, as (level, trigger span). [Register] turns these
     * into a verdict; keeping the spans is what lets it quote the enclosing chunk rather
     * than the bare auxiliary.
     */
    fun registerHits(tokens: List<Token>): List<Hit> {
        val hits = mutableListOf<Hit>()
        register.forEach { rule ->
            for (start in tokens.indices) {
                val end = endOfMatch(rule, tokens, start)
                if (end < 0) continue
                hits += Hit(rule.level, rule.note, start, end)
                break
            }
        }
        return hits
    }

    data class Hit(val level: RegisterLevel, val note: String, val from: Int, val to: Int)

    private fun interpolate(template: String, tokens: List<Token>, start: Int, end: Int): String {
        if (!template.contains('{')) return template
        val previous = tokens.getOrNull(start - 1)
        val text = tokens.subList(start, end + 1).joinToString("") { it.surface }
        return template
            .replace("{prev}", previous?.surface.orEmpty())
            .replace("{prevBase}", previous?.baseForm.orEmpty())
            .replace("{base}", tokens[start].baseForm)
            .replace("{text}", text)
    }

    companion object {

        /** Parses `tools/grammar_rules.json`. Mirrors `SfxDictionary.parse`. */
        fun parse(json: String): GrammarRules {
            val root = JSONObject(json)

            val meta = buildMap {
                root.optJSONObject("meta")?.let { block ->
                    block.keys().forEach { key -> put(key, block.optString(key)) }
                }
            }

            val rules = root.optJSONArray("rules").objects().mapNotNull { item ->
                val match = item.optJSONArray("match").elements()
                if (match.isEmpty()) return@mapNotNull null
                Rule(
                    id = item.optString("id"),
                    fragment = item.optString("fragment"),
                    explanation = item.optString("explanation"),
                    match = match,
                    gloss = item.optString("gloss"),
                    jlpt = item.optString("jlpt"),
                    source = source(item.optString("source")),
                    after = item.optJSONObject("after")?.toElement(),
                    before = item.optJSONObject("before")?.toElement(),
                    atEnd = item.optBoolean("atEnd"),
                )
            }

            val register = root.optJSONArray("register").objects().mapNotNull { item ->
                val match = item.optJSONArray("match").elements()
                if (match.isEmpty()) return@mapNotNull null
                val level = runCatching {
                    RegisterLevel.valueOf(item.optString("level"))
                }.getOrNull() ?: return@mapNotNull null
                RegisterRule(
                    level = level,
                    note = item.optString("note"),
                    match = match,
                    after = item.optJSONObject("after")?.toElement(),
                    before = item.optJSONObject("before")?.toElement(),
                    atEnd = item.optBoolean("atEnd"),
                )
            }

            return GrammarRules(rules, register, meta)
        }

        /** An empty table. A missing asset costs the notes, not the section. */
        val EMPTY = GrammarRules(emptyList(), emptyList(), emptyMap())

        private fun source(value: String): NoteSource =
            runCatching { NoteSource.valueOf(value) }.getOrDefault(NoteSource.PATTERN)

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.elements(): List<Element> = objects().map { it.toElement() }

        private fun JSONObject.toElement() = Element(
            surface = optionalString("surface"),
            base = optionalString("base"),
            reading = optionalString("reading"),
            pos = optionalString("pos"),
            posNot = optionalString("posNot"),
            conjugationForm = optionalString("cform"),
            conjugationType = optionalString("ctype"),
        )

        private fun JSONObject.optionalString(name: String): String? =
            if (has(name)) optString(name).takeIf { it.isNotEmpty() } else null
    }
}

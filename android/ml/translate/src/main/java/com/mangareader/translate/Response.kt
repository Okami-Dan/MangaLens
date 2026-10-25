package com.mangareader.translate

import com.mangareader.model.GrammarAnswer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Section 7's "parse defensively", ported from `harness/pipeline/translate.py`.
 *
 * The shape the prompt asks for is `{"translations":[{"id":1,"en":"..."}]}`. What a
 * 1.5B model actually returns is that, plus fences, plus prose, plus -- observed on the
 * fixture pages -- a stray `[]` between the array and the closing brace, which makes the
 * enclosing object unparseable while the array inside it is perfectly good. So the array
 * is located and parsed on its own *before* anything tries the object as a whole.
 *
 * Nothing here throws. A bubble the model skipped or mangled is simply absent from the
 * result and shows as untranslated, which is section 7's requirement: a bad bubble must
 * not cost the user the other eleven.
 */

/**
 * @param raw whatever the model emitted
 * @param expectedIds the 1..N request indices; anything else the model invents is dropped
 * @return request index -> English, or null where the model explicitly declined. An index
 *   that is *absent* was not translated at all, which is a different thing from one
 *   mapped to null and shows differently in the sheet.
 */
fun parseResponse(raw: String, expectedIds: List<Int>): Map<Int, String?> {
    val text = stripFences(stripReasoning(raw.trim()))
    val allowed = expectedIds.toSet()
    val results = LinkedHashMap<Int, String?>()

    extractItems(text, "translations", LOOSE_TRANSLATION).forEach { item ->
        val key = item.optionalInt("id") ?: return@forEach
        if (key !in allowed) return@forEach
        if (item.isNull("en")) {
            results[key] = null
            return@forEach
        }
        val english = item.opt("en") as? String ?: return@forEach
        if (english.trim().isNotEmpty()) results[key] = english.trim()
    }
    return results
}

/**
 * Drop a reasoning block before anything goes looking for the array.
 *
 * Qwen3's dense checkpoints are hybrid -- the same weights answer with a `<think>` block
 * or without one, decided by the chat template -- and a reasoning block is not merely
 * noise here: the model deliberates *in JSON*, drafting a `{"translations": [...]}` inside
 * the block before committing to a different answer after it. Locating the array without
 * removing the block first would parse the draft and show the user the model's first
 * guess. Tier 1 asks for the direct mode, but that flag lives in a chat template applied
 * by llama.cpp rather than by this module, so this does not depend on it.
 */
private fun stripReasoning(text: String): String = text.replace(REASONING_BLOCK, "").trim()

/**
 * The Python strips the fence markers line-wise with a MULTILINE regex, so a fence
 * anywhere on its own line goes, not only one wrapping the whole string.
 */
private fun stripFences(text: String): String = text
    .replace(OPENING_FENCE, "")
    .replace(CLOSING_FENCE, "")
    .trim()

private val REASONING_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

private val OPENING_FENCE = Regex("^```(?:json)?[ \t]*", RegexOption.MULTILINE)
private val CLOSING_FENCE = Regex("[ \t]*```$", RegexOption.MULTILINE)

/** The last resort: individual `{"id": N, ...}` objects, scraped wherever they sit. */
private val LOOSE_TRANSLATION =
    Regex("""\{[^{}]*?"id"\s*:\s*\d+[^{}]*?\}""", RegexOption.DOT_MATCHES_ALL)

/**
 * The same last resort for a note, which has no `id` to anchor on.
 *
 * A note is `{"jp":"…","note":"…"}`, so the anchor is the `jp` key instead. This is why
 * [extractItems] takes the pattern rather than reaching for a single file-level constant:
 * the two shapes have nothing in common to key on, and scraping notes with the
 * translation pattern would find none.
 */
private val LOOSE_NOTE =
    Regex("""\{[^{}]*?"t"\s*:\s*\d+[^{}]*?\}""", RegexOption.DOT_MATCHES_ALL)

/** The same, for the optional `heads` array: `{"p":1,"head":4}`. */
private val LOOSE_HEAD =
    Regex("""\{[^{}]*?"p"\s*:\s*\d+[^{}]*?\}""", RegexOption.DOT_MATCHES_ALL)

/**
 * Three passes, weakest assumption last.
 *
 *  1. the named array, located by key and read on its own
 *  2. the first balanced `{...}` object, if it happens to parse
 *  3. individual objects scraped out of the text by [loose]
 *
 * Parameterised by key rather than hardcoding `translations`, because the grammar
 * explainer's `{"notes":[…]}` needs exactly the same three passes over exactly the same
 * failure modes -- the model that emits fences, prose and a stray `[]` around one shape
 * emits them around the other.
 */
private fun extractItems(text: String, name: String, loose: Regex): List<JSONObject> {
    val key = text.indexOf('"' + name + '"')
    if (key >= 0) {
        val bracket = text.indexOf('[', key)
        if (bracket >= 0) {
            balancedSpan(text, bracket, '[', ']')?.let { span ->
                runCatching { JSONArray(span) }.getOrNull()?.let { return it.objects() }
            }
        }
    }

    val brace = text.indexOf('{')
    if (brace >= 0) {
        balancedSpan(text, brace, '{', '}')?.let { span ->
            runCatching { JSONObject(span) }.getOrNull()?.let { payload ->
                payload.optJSONArray(name)?.let { return it.objects() }
            }
        }
    }

    return loose.findAll(text)
        .mapNotNull { runCatching { JSONObject(it.value) }.getOrNull() }
        .toList()
}

/**
 * Substring from [start] to the matching close bracket, ignoring brackets that fall
 * inside strings -- an `en` value containing a `}` would otherwise cut the span short.
 */
private fun balancedSpan(text: String, start: Int, opener: Char, closer: Char): String? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val char = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            opener -> depth += 1
            closer -> {
                depth -= 1
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/** `int(item["id"])` with Python's tolerance: a numeric string counts, junk does not. */
private fun JSONObject.optionalInt(name: String): Int? = when (val value = opt(name)) {
    null, JSONObject.NULL -> null
    is Number -> value.toInt()
    is String -> value.trim().toDoubleOrNull()?.toInt()
    else -> null
}

/**
 * The grammar explainer's response.
 *
 * `{"notes":[{"t":3,"note":"…"}],"heads":[{"p":1,"head":4}]}` -- the model is asked about
 * **tokens by index**, and answers with the index it was given.
 *
 * That is the whole reason this shape changed. The previous version asked the model to
 * copy back the fragment each note was about and then recovered a span with
 * `text.indexOf(fragment)`, which could only ever order the notes: character offsets are
 * not token offsets, and a fragment the model paraphrased or mis-copied resolved to
 * nothing. An integer lands on exactly the component it was written for, which is what
 * lets a note *replace* a template explanation rather than pile up beneath it.
 *
 * Both arrays stay **flat**. A nested phrase-and-token tree would defeat the three passes
 * below, and those passes are what keep a 1.7B model's fenced, prose-wrapped, half-closed
 * answer from costing the whole section. Nothing throws; a mangled entry is dropped rather
 * than failing the call, because a bubble that came back with three good notes and one
 * broken one should show three notes.
 */
fun parseGrammarNotes(raw: String): GrammarAnswer {
    val text = stripFences(stripReasoning(raw.trim()))

    val notes = extractItems(text, "notes", LOOSE_NOTE).mapNotNull { item ->
        val token = item.optionalInt("t") ?: return@mapNotNull null
        val note = (item.opt("note") as? String)?.trim().orEmpty()
        if (token < 0 || note.isEmpty()) return@mapNotNull null
        token to note
    }.toMap()

    // `heads` is a correction the model may or may not offer, and a malformed one is not
    // worth failing the notes over -- the offline heads are already there and already
    // rendered. Dropped whole on anything unreadable.
    val heads = extractItems(text, "heads", LOOSE_HEAD).mapNotNull { item ->
        val phrase = item.optionalInt("p") ?: return@mapNotNull null
        val head = item.optionalInt("head") ?: return@mapNotNull null
        if (phrase < 0) return@mapNotNull null
        phrase to head
    }.toMap()

    val meanings = extractItems(text, "meanings", Regex("""\{[^{}]*?"w"\s*:\s*\d+[^{}]*?\}""",
        RegexOption.DOT_MATCHES_ALL)).mapNotNull { item ->
        val word = item.optionalInt("w") ?: return@mapNotNull null
        val meaning = (item.opt("meaning") as? String)?.trim().orEmpty()
        if (word < 0 || meaning.isEmpty()) null else word to meaning
    }.toMap()
    return GrammarAnswer(notes, heads, meanings)
}

/** Accept only IDs from the actual request and safe rightward dependency corrections. */
fun GrammarAnswer.validated(phrases: List<com.mangareader.model.Phrase>,
    words: List<com.mangareader.model.LexicalUnit>): GrammarAnswer {
    val tokens = phrases.flatMap { it.components }.map { it.token.position }.toSet()
    val phraseIds = phrases.map { it.id }.toSet()
    val wordIds = words.map { it.id }.toSet()
    return copy(explanations = explanations.filterKeys { it in tokens },
        heads = heads.filter { (p, h) ->
            val root = phrases.firstOrNull { it.id >= p && it.head == -1 }?.id
            p in phraseIds && ((h in phraseIds && h > p && root != null && h <= root) ||
                h == -1 && p == root)
        },
        meanings = meanings.filterKeys { it in wordIds })
}

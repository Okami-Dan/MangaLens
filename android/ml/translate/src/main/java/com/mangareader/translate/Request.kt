package com.mangareader.translate

import com.mangareader.model.Region
import java.security.MessageDigest

/**
 * Section 7's request shape, and the cache key over it.
 *
 * Everything here is a transcription of `harness/pipeline/translate.py` and has to stay
 * byte-for-byte compatible with it: [cacheKey] hashes a JSON rendering, so a Kotlin
 * writer that merely produces *valid* JSON rather than the *same* JSON would give the
 * phone and the harness different keys for the same page and quietly make the two
 * uncomparable. [canonicalJson] exists for that reason alone.
 */

/**
 * The regions stage 7 is allowed to see: everything that has text.
 *
 * Section 2.6 used to withhold free-floating text from the translator on the grounds
 * that it was all onomatopoeia. It was not -- unboxed monologue went the same way, and
 * the reader lost the scene. Now that `SfxClassifier` decides what is a sound effect
 * from the text rather than from the geometry, withholding anything would only make a
 * classifier miss silently lossy again. Sound effects go into the call too; the system
 * prompt tells the model to render them as sound effects.
 *
 * `cover` is gone from this filter. It still selects the prompt and is still hashed
 * into the cache key -- it just no longer decides who gets translated, because on an
 * interior page and a cover alike the answer is now "everyone".
 */
fun translatable(regions: List<Region>): List<Region> =
    regions.sortedBy { it.readingIndex }.filter { it.text.trim().isNotEmpty() }

/** One entry of section 7's `bubbles` array. */
data class RequestBubble(val id: Int, val text: String)

/** Section 7's request: a page context note and the ordered bubbles. */
data class TranslationRequest(
    val pageContext: String,
    val bubbles: List<RequestBubble>,
) {
    val isEmpty: Boolean get() = bubbles.isEmpty()
}

/**
 * Section 7's request shape.
 *
 * Bubbles are numbered **1..N in reading order rather than by region id**. A small
 * instruct model handed non-contiguous ids (2, 6, 1, 4, ...) tends to ignore them and
 * emit 1..N of its own, which silently attaches every translation to the wrong bubble --
 * an observed failure, not a hypothetical (docs/on-device-llm.md section 5). Sequential
 * ids remove the temptation; [indexMap] maps them back.
 */
fun buildRequest(
    regions: List<Region>,
    pageContext: String = "",
    cover: Boolean = false,
): TranslationRequest = TranslationRequest(
    pageContext = pageContext,
    bubbles = translatable(regions)
        .mapIndexed { index, region -> RequestBubble(index + 1, region.text) },
)

/** 1-based request index -> region id. The other half of the renumbering above. */
fun indexMap(regions: List<Region>, cover: Boolean = false): Map<Int, Long> =
    translatable(regions)
        .mapIndexed { index, region -> (index + 1) to region.id }
        .toMap()

/**
 * Section 7: "cache by hash of the ordered bubble-text list + model identifier", so
 * re-opening a page never re-runs and, on tier 2, never re-bills.
 *
 * The versioned key includes the effective context supplied to this model. Liquid's
 * fixed-direction translation uses empty context. The cover flag is rendered Python-style
 * (`True`/`False`) so the desktop harness and Android produce identical identities.
 */
fun cacheKey(regions: List<Region>, modelId: String, cover: Boolean = false, pageContext: String = ""): String {
    val payload = canonicalJson(buildRequest(regions, pageContext, cover))
    val pythonBool = if (cover) "True" else "False"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("t2\n$modelId\n$pythonBool\n$payload".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * The same idea one bubble at a time, for section 3.3 tier 4's notes.
 *
 * Separate from [cacheKey] rather than a special case of it, because the two cache
 * different things at different grains: a translation is per page and shares the harness's
 * key format under a parity test, while an explanation is per bubble and has no harness
 * counterpart to stay byte-identical with. Folding one into the other would put tier 4 in
 * a position to break `TranslateParityTest` by accident.
 *
 * Keyed on the **text**, so correcting the OCR for one bubble invalidates that bubble's
 * notes and leaves every other bubble's alone.
 *
 * And keyed on [GRAMMAR_SCHEMA], which is what a stored answer is *shaped* like. The cache
 * survives app updates by design, so a row written when a note was a (fragment, prose)
 * pair would otherwise be handed to a reader expecting (token index, prose) and decode
 * into explanations pinned to the wrong words -- a silent wrong answer, which is the one
 * failure mode worse than a miss. Bump this whenever the stored shape changes.
 */
fun grammarCacheKey(text: String, explainerId: String, structure: String = ""): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((GRAMMAR_SCHEMA + "\n" + explainerId + "\n" + text + "\n" + structure).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** The stored grammar answer's shape. v2 moved from copied fragments to token indices. */
private const val GRAMMAR_SCHEMA = "g3"

/**
 * The exact output of `json.dumps(request, ensure_ascii=False, sort_keys=True)`.
 *
 * Python's defaults are not any JSON writer's defaults: a space after every `:` and
 * every `,`, keys sorted (`bubbles` before `page_context`, `id` before `text`), non-ASCII
 * passed through raw, and `/` left unescaped -- which `org.json` in particular does not
 * do. Hand-written because the alternative is depending on a serializer's incidental
 * formatting choices for a value that is hashed.
 */
internal fun canonicalJson(request: TranslationRequest): String {
    val bubbles = request.bubbles.joinToString(", ") { bubble ->
        """{"id": ${bubble.id}, "text": ${quote(bubble.text)}}"""
    }
    return """{"bubbles": [$bubbles], "page_context": ${quote(request.pageContext)}}"""
}

/**
 * The JSON the model is actually handed. **Not** [canonicalJson].
 *
 * The Python builds the request dict as `{"page_context": ..., "bubbles": [...]}` and
 * sends it with `json.dumps(request, ensure_ascii=False)` -- no `sort_keys`, so the
 * prompt carries the keys in insertion order, page context first. Only the *cache key*
 * sorts them. Two renderings of one object, and the difference is load-bearing: the
 * prompt has to match what the model was evaluated against, and the key has to match
 * what the harness hashed.
 */
internal fun requestJson(request: TranslationRequest): String {
    val bubbles = request.bubbles.joinToString(", ") { bubble ->
        """{"id": ${bubble.id}, "text": ${quote(bubble.text)}}"""
    }
    return """{"page_context": ${quote(request.pageContext)}, "bubbles": [$bubbles]}"""
}

/** Python's `json.encoder.ESCAPE_DCT` under `ensure_ascii=False`, and nothing more. */
private fun quote(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    value.forEach { char ->
        when {
            char == '\\' -> out.append("\\\\")
            char == '"' -> out.append("\\\"")
            char == '\b' -> out.append("\\b")
            char == '\u000C' -> out.append("\\f")
            char == '\n' -> out.append("\\n")
            char == '\r' -> out.append("\\r")
            char == '\t' -> out.append("\\t")
            char < '\u0020' -> out.append("\\u%04x".format(char.code))
            else -> out.append(char)
        }
    }
    out.append('"')
    return out.toString()
}

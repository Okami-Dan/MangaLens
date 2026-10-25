package com.mangareader.ocr

/**
 * Output normalization, matching `harness/pipeline/ocr.py::post_process` exactly.
 *
 * This is what makes the app's recognized text look like the reference manga-ocr tool's,
 * and it is a stage the harness applies to *every* manga-ocr result -- so a divergence
 * here shows up as a CER difference against `var/run_final` that has nothing to do with
 * the model. Pure string logic, so it is unit-tested on the JVM against vectors
 * generated from the Python; see `PostProcessParityTest`.
 */
object OcrText {

    private val DOT_RUN = Regex("[・.]{2,}")

    /** ASCII 0x21..0x7E maps onto the full-width forms at 0xFF01..0xFF5E. */
    private const val FULLWIDTH_OFFSET = 0xFEE0

    fun postProcess(text: String): String {
        // Whitespace goes first: the character-level vocab emits it between glyphs and
        // it is never meaningful in a bubble.
        var out = text.filterNot { it.isWhitespace() }
        out = out.replace("…", "...")
        out = DOT_RUN.replace(out) { ".".repeat(it.value.length) }
        return halfToFullWidth(out)
    }

    /**
     * jaconv's `h2z(text, ascii=True, digit=True)`, whose `kana` argument defaults to
     * true.
     *
     * The kana half is currently unreachable: manga-ocr's vocab.txt holds no half-width
     * katakana at all (checked across all 6144 entries), so the model cannot emit any.
     * It is implemented anyway because "the model can't produce this" is the kind of
     * assumption that quietly stops being true when a checkpoint is swapped, and the
     * cost is one table.
     */
    private fun halfToFullWidth(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]

            val base = KANA[char]
            if (base != null) {
                // A following ﾞ or ﾟ merges into the base character when the combination
                // exists. When it does not -- ｱﾞ -- the base converts and the mark is
                // left exactly as it was, half-width. jaconv does the same.
                val next = text.getOrNull(index + 1)
                val merged = when (next) {
                    DAKUTEN -> voiced(base)
                    HANDAKUTEN -> semiVoiced(base)
                    else -> null
                }
                if (merged != null) {
                    out.append(merged)
                    index += 2
                    continue
                }
                out.append(base)
                index++
                continue
            }

            if (char.code in 0x21..0x7E) {
                out.append((char.code + FULLWIDTH_OFFSET).toChar())
            } else {
                out.append(char)
            }
            index++
        }
        return out.toString()
    }

    private const val DAKUTEN = 'ﾞ'
    private const val HANDAKUTEN = 'ﾟ'

    /** ウ is the one voiced form that is not simply the next code point. */
    private fun voiced(base: Char): Char? = when {
        base == 'ウ' -> 'ヴ'
        base in 'カ'..'ト' && VOICEABLE.contains(base) -> base + 1
        base in 'ハ'..'ホ' && HA_ROW.contains(base) -> base + 1
        else -> null
    }

    private fun semiVoiced(base: Char): Char? =
        if (HA_ROW.contains(base)) base + 2 else null

    /** ka, sa and ta rows: the voiced form is the next code point in each case. */
    private val VOICEABLE = setOf(
        'カ', 'キ', 'ク', 'ケ', 'コ',
        'サ', 'シ', 'ス', 'セ', 'ソ',
        'タ', 'チ', 'ツ', 'テ', 'ト',
    )

    private val HA_ROW = setOf('ハ', 'ヒ', 'フ', 'ヘ', 'ホ')

    private val KANA: Map<Char, Char> = buildMap {
        val half = "｡｢｣､･ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        val full = "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン"
        require(half.length == full.length) { "half/full width tables disagree" }
        half.forEachIndexed { index, char -> put(char, full[index]) }
    }
}

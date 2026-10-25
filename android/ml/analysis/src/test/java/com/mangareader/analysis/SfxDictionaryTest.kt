package com.mangareader.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The glossary's two lookup paths, which used to be one.
 *
 * `lookup` widens as far as a prefix match so a stretched or trailed-off rendering
 * still resolves; `lookupConfident` stops before that step. The split exists because
 * the match is no longer only cosmetic -- `SfxClassifier` weighs it -- and a guess must
 * not be allowed to vote on the classification that produced it.
 */
class SfxDictionaryTest {

    private val dictionary: SfxDictionary by lazy {
        val file = File("../../../tools/sfx_dict.json").let {
            if (it.isFile) it else File("../../tools/sfx_dict.json")
        }
        assertTrue("tools/sfx_dict.json not found from " + File(".").absolutePath, file.isFile)
        SfxDictionary.parse(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `the confident steps are exact, normalized and repeat`() {
        assertEquals("exact", dictionary.lookupConfident("ドキドキ")?.match)
        assertEquals("normalized", dictionary.lookupConfident("どきどき")?.match)
        assertEquals("normalized", dictionary.lookupConfident("ドキドキーッ")?.match)
        assertEquals("repeat", dictionary.lookupConfident("ゴゴゴゴゴゴ")?.match)
    }

    /**
     * The second bug this change closes. ざわ is a two-character glossary key, so the
     * prefix step matches any sentence that opens with it -- and used to hand back a
     * confident-looking gloss for one.
     */
    @Test
    fun `a sentence is never a confident match`() {
        assertNull(dictionary.lookupConfident("ざわざわしてきた"))
        assertNull(dictionary.lookupConfident("どきどきするなあ"))
    }

    @Test
    fun `the prefix step still resolves a trailed-off rendering`() {
        val match = dictionary.lookup("ザザザ…")
        assertNotNull("a stretched rendering should still resolve", match)
        assertEquals("prefix", match?.match)
    }

    /**
     * The length guard. A stretched rendering is a few times its root; anything longer
     * is a different string that happens to start the same way.
     */
    @Test
    fun `the prefix step gives up on something far longer than its root`() {
        assertNull(dictionary.lookup("ざざざざざざざざざざざざ"))
    }

    @Test
    fun `no match at all stays no match`() {
        assertNull(dictionary.lookup("＠＠＠"))
        assertNull(dictionary.lookupConfident("＠＠＠"))
    }

    @Test
    fun `normalisation folds the decoration SFX lettering carries`() {
        assertEquals("どきどき", SfxDictionary.normalize("ドキドキーッ"))
    }
}

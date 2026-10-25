package com.mangareader.pipeline

import android.content.Context
import android.util.Log
import com.mangareader.analysis.Analyser
import com.mangareader.analysis.GrammarRules
import com.mangareader.analysis.GrammarLexicon
import com.mangareader.analysis.Jmdict
import com.mangareader.analysis.SfxDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Where stage 6's bundled assets come from.
 *
 * Unlike [ModelStore] this downloads nothing and asks for no cable. Section 6 allows
 * JMdict to be "shipped as an asset (or downloaded on first run alongside the OCR
 * model)" and M3 takes the first option, because section 2.4 calls the dictionary layer
 * the core feature and section 9 calls M3 "the first genuinely useful build and fully
 * offline" -- a dictionary that needs `adb push` is neither.
 *
 * The cost is honest and worth stating: `jmdict-mobile.sqlite` is 53 MB, so the APK
 * goes from 43 MB to about 61 MB, and unpacking leaves a second copy in app-private
 * storage. SQLite cannot open a database out of a compressed APK entry, so the copy is
 * not optional. The 20.8 MB FTS5 gloss index is already gone --
 * `tools/build_jmdict_mobile.py` drops it, since English->Japanese search is M6's
 * saved-words screen and nothing on the M3 path touches it.
 *
 * The SFX glossary is 24 KB and the grammar rule table is 60 KB; both are read straight
 * out of assets, and there is nothing to unpack and nothing to weigh up.
 */
class DictionaryStore(private val context: Context) {

    data class Progress(val bytesDone: Long, val bytesTotal: Long) {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else bytesDone.toFloat() / bytesTotal
    }

    /** App-private, so uninstalling reclaims it. */
    val jmdictFile: File get() = File(File(context.filesDir, "dictionary"), JMDICT_ASSET)

    /**
     * What the packaged asset should weigh, or -1 when the APK was built without it.
     *
     * Read from a sidecar the `:app` build writes rather than measured. `AssetManager`
     * compresses a `.sqlite` entry, so `openFd` refuses it and the only way to ask the
     * stream its length is to read all 53 MB of it -- which this is called to avoid.
     */
    val packagedBytes: Long by lazy {
        runCatching {
            context.assets.open(META_ASSET).use { input ->
                input.readBytes().toString(Charsets.UTF_8).lineSequence()
                    .firstOrNull { it.startsWith("bytes=") }
                    ?.removePrefix("bytes=")?.trim()?.toLong()
                    ?: -1L
            }
        }.getOrDefault(-1L)
    }

    val isPackaged: Boolean get() = packagedBytes > 0

    /** True once the whole asset is on disk. A short file is a half-finished unpack. */
    private val packagedHash: String by lazy {
        context.assets.open(META_ASSET).bufferedReader().use { reader ->
            reader.lineSequence().first { it.startsWith("sha256=") }.substringAfter('=')
        }
    }
    private val installedHash: File get() = File(jmdictFile.parentFile, "installed.sha256")
    val isInstalled: Boolean get() = jmdictFile.length() > 0 && jmdictFile.length() == packagedBytes &&
        installedHash.isFile && installedHash.readText().trim() == packagedHash

    /**
     * Unpack JMdict if it is not already there. Idempotent, and safe to call from two
     * places at once through a shared installation lock;
     * [Analyser] holding an open handle to the old file is why the write goes through a
     * `.part` and a rename rather than truncating in place.
     */
    suspend fun install(onProgress: (Progress) -> Unit = {}): Boolean =
        installationMutex.withLock { withContext(Dispatchers.IO) {
            if (isInstalled) return@withContext true
            val total = packagedBytes
            if (total <= 0) {
                Log.w(TAG, "no packaged $JMDICT_ASSET -- run tools/build_jmdict_mobile.py and rebuild")
                return@withContext false
            }

            val target = jmdictFile
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, "$JMDICT_ASSET.part")
            var copied = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            var reported = 0L
            onProgress(Progress(0, total))
            context.assets.open(JMDICT_ASSET).use { input ->
                part.outputStream().buffered(1 shl 16).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        if (copied - reported >= 2L shl 20) {
                            reported = copied
                            onProgress(Progress(copied, total))
                        }
                    }
                }
            }
            onProgress(Progress(copied, total))

            if (copied != total) {
                part.delete()
                error("dictionary: unpacked $copied of $total bytes")
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != packagedHash) {
                part.delete()
                error("dictionary: SHA-256 mismatch")
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                error("dictionary: could not move into place")
            }
            installedHash.writeText(actualHash)
            Log.i(TAG, "unpacked $JMDICT_ASSET ($total bytes) to ${target.absolutePath}")
            true
        } }

    /** Frees the unpacked copy. The APK asset stays, so this is reversible. */
    fun uninstall(): Long {
        val freed = jmdictFile.length()
        jmdictFile.delete()
        installedHash.delete()
        return freed
    }

    fun sfx(): SfxDictionary = SfxDictionary.parse(
        context.assets.open(SFX_ASSET).use { it.readBytes().toString(Charsets.UTF_8) },
    )

    /**
     * Section 3.3 tier 4's rule table.
     *
     * Unlike [sfx], a failure here is swallowed. The SFX glossary decides what an SFX
     * region *is*, so losing it is a broken pipeline; the grammar table only adds a
     * section to the sheet, and a build that shipped without the asset should cost the
     * notes rather than the bubble. [GrammarRules.EMPTY] exists for exactly this, and
     * `Register` reads `hasRegisterRules` to tell "no table loaded" apart from "table
     * loaded, nothing matched" -- so an empty table stays silent instead of claiming the
     * line has no politeness marking.
     */
    val grammarLexicon: GrammarLexicon by lazy {
        runCatching {
            GrammarLexicon.parse(context.assets.open("grammar_lexicon.json").bufferedReader().use { it.readText() })
        }.getOrElse { failure ->
            Log.w(TAG, "Could not read grammar_lexicon.json", failure)
            GrammarLexicon.EMPTY
        }
    }

    fun grammar(): GrammarRules = runCatching {
        GrammarRules.parse(
            context.assets.open(GRAMMAR_ASSET).use { it.readBytes().toString(Charsets.UTF_8) },
        )
    }.getOrElse { failure ->
        Log.w(TAG, "grammar: could not read $GRAMMAR_ASSET", failure)
        GrammarRules.EMPTY
    }

    /**
     * A ready-to-use stage 6. The dictionary half is null when the asset has not been
     * unpacked yet, which costs glosses and nothing else -- see [Analyser].
     *
     * The caller owns the result and must close it; the JMdict handle is a real open
     * SQLite connection.
     */
    fun analyser(): Analyser = Analyser(if (isInstalled) Jmdict.open(jmdictFile) else null, sfx())

    private companion object {
        val installationMutex = Mutex()
        const val TAG = "DictionaryStore"
        const val JMDICT_ASSET = "jmdict.sqlite"
        const val META_ASSET = "jmdict.meta"
        const val SFX_ASSET = "sfx_dict.json"
        const val GRAMMAR_ASSET = "grammar_rules.json"
    }
}

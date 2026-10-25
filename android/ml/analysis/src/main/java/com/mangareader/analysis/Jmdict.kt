package com.mangareader.analysis

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.mangareader.model.Gloss
import java.io.Closeable
import java.io.File

/** Read-only, spelling-constrained lookup. No pronunciation-only fallback. */
class Jmdict(private val database: SQLiteDatabase) : Closeable {
    val meta: Map<String, String> by lazy {
        buildMap {
            database.rawQuery("SELECT key, value FROM meta", null).use { c ->
                while (c.moveToNext()) put(c.getString(0), c.getString(1))
            }
        }
    }
    data class Entry(val id: Long?, val glosses: List<Gloss>, val baseReading: String, val rank: Int = 0) {
        companion object { val NONE = Entry(null, emptyList(), "") }
    }
    private data class Key(val form: String, val reading: String, val pos: String, val limit: Int)
    private val cache = object : LinkedHashMap<Key, List<Entry>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<Entry>>?) = size > 512
    }

    fun lookup(baseForm: String, reading: String = "", pos: String = "", limit: Int = 16): Entry =
        lookupCandidates(baseForm, reading, pos, limit).firstOrNull() ?: Entry.NONE

    @Synchronized
    fun lookupCandidates(form: String, reading: String = "", pos: String = "", limit: Int = 16): List<Entry> {
        if (form.isEmpty() || form == "*") return emptyList()
        val key = Key(form, reading, pos, limit)
        cache[key]?.let { return it }
        val wanted = PartOfSpeech.jmdictTags(pos)
        val restricted = meta["schema_version"] == "2"
        val found = mutableListOf<Pair<Int, Entry>>()
        database.rawQuery(
            "SELECT DISTINCT e.id, e.common, f.common, f.kind FROM forms f " +
                "JOIN entries e ON e.id=f.entry_id WHERE f.text=? ORDER BY e.id",
            arrayOf(form),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val common = c.getInt(1) != 0
                val kind = c.getString(3)
                // Stem readings are not dictionary readings. Constrain spelling first.
                val kanji = if (kind == "kanji") form else ""
                val kanaForms = mutableListOf<String>()
                database.rawQuery("SELECT text" + (if (restricted) ", applies_to" else "") +
                    " FROM forms WHERE entry_id=? AND kind='kana' ORDER BY common DESC, rowid",
                    arrayOf(id.toString())).use { k ->
                    while (k.moveToNext()) {
                        if (!restricted || kanji.isEmpty() || permits(k.getString(1), kanji)) {
                            kanaForms += k.getString(0)
                        }
                    }
                }
                if (kanaForms.isEmpty()) continue
                val baseReading = if (kind == "kana") form else
                    reading.takeIf { it in kanaForms } ?: kanaForms.first()
                val senses = mutableListOf<Gloss>()
                database.rawQuery("SELECT pos, misc, gloss" +
                    (if (restricted) ", kanji_restrictions, reading_restrictions" else "") +
                    " FROM senses WHERE entry_id=? ORDER BY position", arrayOf(id.toString())).use { s ->
                    while (s.moveToNext()) {
                        val tags = s.getString(0).split(",")
                        if (wanted.isNotEmpty() && tags.none { it in wanted }) continue
                        if (restricted && ((kanji.isNotEmpty() && !permits(s.getString(3), kanji)) ||
                            !permits(s.getString(4), baseReading))) continue
                        senses += Gloss(id, s.getString(0), s.getString(1), s.getString(2), common)
                    }
                }
                if (senses.isEmpty()) continue
                val rank = (if (reading.isNotEmpty() && reading in kanaForms) 8 else 0) +
                    (if (common) 4 else 0) + (if (c.getInt(2) != 0) 2 else 0) +
                    (if (kind == "kanji") 1 else 0)
                found += rank to Entry(id, senses.take(limit.coerceIn(1, 16)), baseReading, rank)
            }
        }
        return found.sortedByDescending { it.first }.take(4).map { it.second }.also { cache[key] = it }
    }
    private fun permits(restrictions: String, value: String): Boolean =
        restrictions.isEmpty() || value in restrictions.split('\u001f')

    override fun close() = database.close()
    companion object {
        fun open(file: File): Jmdict? = try {
            if (!file.isFile) null else Jmdict(SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ))
        } catch (_: SQLiteException) { null }
    }
}

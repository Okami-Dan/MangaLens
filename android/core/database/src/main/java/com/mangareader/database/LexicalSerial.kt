package com.mangareader.database

import com.mangareader.model.Gloss
import com.mangareader.model.LexicalUnit
import org.json.JSONArray
import org.json.JSONObject

/** Versioned by RegionEntity.analysisVersion, stored once alongside the token rows. */
object LexicalSerial {
    fun encode(words: List<LexicalUnit>): String = JSONArray().apply {
        words.forEach { w -> put(JSONObject().apply {
            put("id", w.id); put("start", w.start); put("end", w.end)
            put("from", w.from); put("to", w.to); put("surface", w.surface)
            put("lemma", w.lemma); put("reading", w.reading); put("lemmaReading", w.lemmaReading)
            put("pos", w.partOfSpeech); put("label", w.posLabel)
            put("entry", w.entryId ?: JSONObject.NULL)
            put("inflections", JSONArray(w.inflections))
            put("senses", JSONArray().apply { w.senses.forEach { g ->
                put(JSONObject().apply {
                    put("id", g.entryId); put("pos", g.pos); put("misc", g.misc)
                    put("text", g.text); put("common", g.common)
                })
            } })
        }) }
    }.toString()

    fun decode(value: String): List<LexicalUnit> = runCatching {
        val rows = JSONArray(value)
        (0 until rows.length()).map { i ->
            val w = rows.getJSONObject(i)
            val senses = w.getJSONArray("senses")
            val inflections = w.getJSONArray("inflections")
            LexicalUnit(w.getInt("id"), w.getInt("start"), w.getInt("end"),
                w.getInt("from"), w.getInt("to"), w.getString("surface"),
                w.getString("lemma"), w.getString("reading"), w.getString("lemmaReading"),
                w.getString("pos"), w.getString("label"),
                if (w.isNull("entry")) null else w.getLong("entry"),
                (0 until senses.length()).map { j -> senses.getJSONObject(j).let { g ->
                    Gloss(g.getLong("id"), g.getString("pos"), g.getString("misc"),
                        g.getString("text"), g.getBoolean("common"))
                } }, (0 until inflections.length()).map { inflections.getString(it) })
        }
    }.getOrDefault(emptyList())
}

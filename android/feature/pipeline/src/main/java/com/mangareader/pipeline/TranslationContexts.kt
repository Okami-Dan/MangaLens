package com.mangareader.pipeline

import com.mangareader.database.MangaDatabase
import com.mangareader.model.TranslationContext

suspend fun MangaDatabase.translationContext(pageId: String, note: String = ""): TranslationContext {
    val page = pages().byId(pageId) ?: return TranslationContext(note = note)
    val volume = volumes().byId(page.volumeId) ?: return TranslationContext(note = note)
    if (volume.seriesId == MangaDatabase.UNSORTED_ID) return TranslationContext(note = note)
    val series = series().byId(volume.seriesId) ?: return TranslationContext(note = note)
    return TranslationContext(series.title, series.japaneseTitle, note)
}

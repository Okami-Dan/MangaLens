package com.mangareader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mangareader.database.MangaDatabase
import com.mangareader.database.SeriesEntity
import com.mangareader.database.VolumeEntity
import com.mangareader.pipeline.CoverStore
import com.mangareader.pipeline.PageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The library's folders: creating, editing and deleting a series or a volume.
 *
 * Owned at the app level like [AnalysisController] and [TranslationController], and for
 * one of the same reasons -- copying a picked cover is disk work that must not be
 * cancelled by the dialog that started it closing on top of it.
 *
 * Everything here is a folder operation. No pipeline stage reads a series or a volume;
 * they exist so a shelf of tankoubon is browsable as a shelf instead of as one flat roll
 * of every page ever photographed.
 */
class LibraryController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val database = MangaDatabase.get(appContext)
    private val covers = CoverStore(appContext)
    private val pages = PageStore(appContext)

    /** Last failure, until something else is attempted. Shown as a snackbar. */
    var error by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        error = null
    }

    /**
     * A new series, plus the volume 1 it is useless without. Auto-creating that volume is
     * what keeps capture one tap from here: a series with no volumes has nowhere to put a
     * page, and making the user dismiss a second dialog to say "1" serves nobody.
     */
    fun createSeries(
        title: String,
        japaneseTitle: String,
        wikiUrl: String,
        cover: Uri?,
    ) = scope.launch {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        runCatching {
            database.series().insert(
                SeriesEntity(
                    id = id,
                    title = title.trim(),
                    japaneseTitle = japaneseTitle.trim(),
                    wikiUrl = wikiUrl.trim(),
                    coverPath = null,
                    createdAt = now,
                ),
            )
            database.volumes().insert(
                VolumeEntity(
                    id = UUID.randomUUID().toString(),
                    seriesId = id,
                    number = 1,
                    coverPath = null,
                    createdAt = now,
                ),
            )
            // After the rows, not before: a cover that fails to copy should leave a
            // usable series behind rather than nothing at all.
            cover?.let {
                database.series().setCover(id, covers.store(it, id, CoverStore.Owner.SERIES))
                covers.prune(id, CoverStore.Owner.SERIES)
            }
        }.onFailure { error = it.message ?: it.toString() }
    }

    fun editSeries(
        id: String,
        title: String,
        japaneseTitle: String,
        wikiUrl: String,
        cover: Uri?,
    ) = scope.launch {
        runCatching {
            database.series().edit(id, title.trim(), japaneseTitle.trim(), wikiUrl.trim())
            // Null means "the dialog was closed without picking", not "clear the cover".
            cover?.let {
                database.series().setCover(id, covers.store(it, id, CoverStore.Owner.SERIES))
                covers.prune(id, CoverStore.Owner.SERIES)
            }
        }.onFailure { error = it.message ?: it.toString() }
    }

    /**
     * Drops a series and everything under it. The rows cascade -- volumes, pages, and in
     * turn regions, tokens and cached translations -- but the images do not, so every
     * volume's pages are unlinked first while their paths are still readable.
     */
    fun deleteSeries(id: String) = scope.launch {
        runCatching {
            val volumes = database.volumes().bySeries(id)
            volumes.forEach { volume ->
                pages.deletePages(volume.id)
            }
            database.series().delete(id)
            volumes.forEach { covers.prune(it.id, CoverStore.Owner.VOLUME) }
            covers.prune(id, CoverStore.Owner.SERIES)
        }.onFailure { error = it.message ?: it.toString() }
    }

    fun createVolume(seriesId: String, number: Int, cover: Uri?) = scope.launch {
        val id = UUID.randomUUID().toString()
        runCatching {
            database.volumes().insert(
                VolumeEntity(
                    id = id,
                    seriesId = seriesId,
                    number = number,
                    coverPath = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            cover?.let {
                database.volumes().setCover(id, covers.store(it, id, CoverStore.Owner.VOLUME))
                covers.prune(id, CoverStore.Owner.VOLUME)
            }
        }.onFailure { error = it.message ?: it.toString() }
    }

    fun editVolume(id: String, number: Int, cover: Uri?) = scope.launch {
        runCatching {
            database.volumes().setNumber(id, number)
            cover?.let {
                database.volumes().setCover(id, covers.store(it, id, CoverStore.Owner.VOLUME))
                covers.prune(id, CoverStore.Owner.VOLUME)
            }
        }.onFailure { error = it.message ?: it.toString() }
    }

    fun deleteVolume(id: String) = scope.launch {
        runCatching {
            pages.deletePages(id)
            database.volumes().delete(id)
            covers.prune(id, CoverStore.Owner.VOLUME)
        }.onFailure { error = it.message ?: it.toString() }
    }

    fun archiveVolume(id: String) = scope.launch {
        runCatching { pages.archiveVolume(id) }
            .onFailure { error = it.message ?: it.toString() }
    }

    /**
     * Moves a volume to another series. Its pages come with it -- they hang off the
     * volume, not off the series -- and so does its cover, so this is the one row.
     */
    fun moveVolume(id: String, seriesId: String) = scope.launch {
        runCatching { database.volumes().setSeries(id, seriesId) }
            .onFailure { error = it.message ?: it.toString() }
    }

    /**
     * Drops one page: its four JPEGs, its row, and -- by cascade -- its regions, tokens,
     * cached translation and grammar notes. The pages after it in the volume close up
     * behind it; see `PageStore.deletePage`.
     */
    fun deletePage(id: String) = scope.launch {
        runCatching { pages.deletePage(id) }
            .onFailure { error = it.message ?: it.toString() }
    }

    /** Files a page under another volume, at the end of it. */
    fun movePage(id: String, volumeId: String) = scope.launch {
        runCatching { pages.movePage(id, volumeId) }
            .onFailure { error = it.message ?: it.toString() }
    }

    /** Seeds the new-volume dialog with the next number on the shelf. */
    suspend fun nextNumber(seriesId: String): Int = database.volumes().nextNumber(seriesId)
}

package com.mangareader.ui

import com.mangareader.database.PageEntity
import com.mangareader.database.SavedWordEntity
import com.mangareader.database.SeriesEntity
import com.mangareader.database.VolumeEntity

/**
 * How a grid is ordered, and where each screen's choice is kept.
 *
 * Sorting is a *display* decision and is done here, in memory, rather than by swapping the
 * DAO's `ORDER BY`. Two reasons. The lists are already collected whole in `MangaReaderApp`
 * -- a series holds tens of volumes and a volume hundreds of pages, not thousands -- so a
 * second query would buy nothing. And reading order must not move: the reader walks pages
 * by `readingIndex` and bubbles by their own index, and a sort that reached the database
 * would be one refactor away from re-ordering the story.
 *
 * There is deliberately no "date modified". Nothing in the schema records when a row was
 * last touched -- `createdAt` and `capturedAt` are the only timestamps -- and an option
 * that silently answered with the creation date would be a lie in the dropdown.
 */
enum class SortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("A\u2013Z"),
    NAME_DESCENDING("Z\u2013A"),
    NUMBER("Volume number"),
    NUMBER_DESCENDING("Volume number descending"),
    PAGE_ORDER("Page order"),
}

/**
 * One sortable screen: its DataStore key, what it does when the user has said nothing,
 * and what the dropdown offers.
 *
 * **Every default reproduces the order the screen already had**, so an app whose user
 * never opens the menu looks exactly as it did before the menu existed.
 */
enum class SortScope(
    val key: String,
    val default: SortOrder,
    val options: List<SortOrder>,
) {
    SERIES(
        "seriesSort",
        SortOrder.NEWEST,
        listOf(SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.NAME, SortOrder.NAME_DESCENDING),
    ),
    VOLUMES(
        "volumeSort",
        SortOrder.NUMBER,
        listOf(SortOrder.NUMBER, SortOrder.NUMBER_DESCENDING, SortOrder.NEWEST, SortOrder.OLDEST),
    ),
    PAGES(
        "pageSort",
        SortOrder.PAGE_ORDER,
        listOf(SortOrder.PAGE_ORDER, SortOrder.NEWEST, SortOrder.OLDEST),
    ),
    WORDS(
        "wordSort",
        SortOrder.NEWEST,
        listOf(SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.NAME),
    ),
}

/** Case-insensitive, so a series called "berserk" files with the Bs. */
private val byTitle = compareBy(String.CASE_INSENSITIVE_ORDER, SeriesEntity::title)

fun sortSeries(series: List<SeriesEntity>, order: SortOrder): List<SeriesEntity> = when (order) {
    SortOrder.OLDEST -> series.sortedBy { it.createdAt }
    SortOrder.NAME -> series.sortedWith(byTitle)
    SortOrder.NAME_DESCENDING -> series.sortedWith(byTitle.reversed())
    else -> series.sortedByDescending { it.createdAt }
}

fun sortVolumes(volumes: List<VolumeEntity>, order: SortOrder): List<VolumeEntity> = when (order) {
    SortOrder.NEWEST -> volumes.sortedByDescending { it.createdAt }
    SortOrder.OLDEST -> volumes.sortedBy { it.createdAt }
    SortOrder.NUMBER_DESCENDING -> volumes.sortedByDescending { it.number }
    else -> volumes.sortedBy { it.number }
}

fun sortPages(pages: List<PageEntity>, order: SortOrder): List<PageEntity> = when (order) {
    SortOrder.NEWEST -> pages.sortedByDescending { it.capturedAt }
    SortOrder.OLDEST -> pages.sortedBy { it.capturedAt }
    // Not the same thing as capture time: a page moved in from another volume was
    // photographed before pages it now sits after.
    else -> pages.sortedBy { it.readingIndex }
}

fun sortWords(words: List<SavedWordEntity>, order: SortOrder): List<SavedWordEntity> =
    when (order) {
        SortOrder.OLDEST -> words.sortedBy { it.savedAt }
        // Code-point order on the base form. For kana that is dictionary order; for a
        // word written in kanji it is not, but it is stable and it groups a word with
        // itself, which is what scanning a saved list is for.
        SortOrder.NAME -> words.sortedBy { it.baseForm }
        else -> words.sortedByDescending { it.savedAt }
    }

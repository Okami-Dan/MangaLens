package com.mangareader.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mangareader.database.SeriesEntity
import com.mangareader.database.VolumeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseUiTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun seriesSortUsesCreationTimeAndCaseInsensitiveTitles() {
        val entries = listOf(series("berserk", 10), series("Akira", 30), series("zombie", 20))
        assertEquals(listOf("Akira", "zombie", "berserk"), sortSeries(entries, SortOrder.NEWEST).map { it.title })
        assertEquals(listOf("Akira", "berserk", "zombie"), sortSeries(entries, SortOrder.NAME).map { it.title })
        assertEquals(listOf("zombie", "berserk", "Akira"), sortSeries(entries, SortOrder.NAME_DESCENDING).map { it.title })
        assertEquals(SortOrder.NEWEST, SortScope.SERIES.default)
    }

    @Test fun volumeOrderIsNumericAndNewestUsesCreationTime() {
        val entries = listOf(volume(2, 30), volume(10, 10), volume(1, 20))
        assertEquals(listOf(1, 2, 10), sortVolumes(entries, SortOrder.NUMBER).map { it.number })
        assertEquals(listOf(10, 2, 1), sortVolumes(entries, SortOrder.NUMBER_DESCENDING).map { it.number })
        assertEquals(listOf(2, 1, 10), sortVolumes(entries, SortOrder.NEWEST).map { it.number })
        assertEquals(SortOrder.NUMBER, SortScope.VOLUMES.default)
        assertFalse(SortScope.PAGES.options.contains(SortOrder.NAME_DESCENDING))
        assertFalse(SortScope.WORDS.options.contains(SortOrder.NAME_DESCENDING))
    }

    @Test fun deadlineSurvivesRepeatedStartAndExpiresAtTwoPointFiveSeconds() {
        val timer = StartupArtworkTimer()
        timer.start(100)
        assertEquals(2500L, timer.remaining(100))
        timer.start(1000) // Rotation must not grant another 2.5 seconds.
        assertEquals(1600L, timer.remaining(1000))
        assertEquals(1L, timer.remaining(2599))
        assertEquals(0L, timer.remaining(2600))
        timer.start(5000) // A warm launch in the same process must stay finished.
        assertEquals(0L, timer.remaining(5000))
    }

    @Test fun tutorialAndArtworkCyclePersistAcrossStoreRecreation(): Unit = runBlocking {
        val file = temporary.newFolder().resolve("release.preferences_pb")
        repeat(5) { launch ->
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                val release = ReleasePreferences(store)
                assertEquals(launch > 0, release.tutorialWasShown())
                assertEquals(launch % 4, release.takeStartupArtwork())
                release.markTutorialShown()
                assertTrue(release.tutorialWasShown())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun reverseSortChoicesPersistWithoutChangingOtherDefaults(): Unit = runBlocking {
        val file = temporary.newFolder().resolve("sort.preferences_pb")
        repeat(2) { launch ->
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                val settings = ReaderSettings(store, scope)
                if (launch == 0) {
                    settings.setSort(SortScope.SERIES, SortOrder.NAME_DESCENDING)
                    settings.setSort(SortScope.VOLUMES, SortOrder.NUMBER_DESCENDING)
                }
                withTimeout(5000) {
                    settings.sort(SortScope.SERIES).first { it == SortOrder.NAME_DESCENDING }
                    settings.sort(SortScope.VOLUMES).first { it == SortOrder.NUMBER_DESCENDING }
                }
                assertEquals(SortOrder.PAGE_ORDER, settings.sort(SortScope.PAGES).value)
                assertEquals(SortOrder.NEWEST, settings.sort(SortScope.WORDS).value)
            } finally { job.cancelAndJoin() }
        }
    }

    private fun series(title: String, createdAt: Long) = SeriesEntity(title, title, "", "", null, createdAt)
    private fun volume(number: Int, createdAt: Long) = VolumeEntity("$number", "series", number, null, createdAt)
}

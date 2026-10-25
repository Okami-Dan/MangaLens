package com.mangareader.pipeline

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverFilesTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun replacingSeriesCoverKeepsVolumeWithTheSameMigratedId() {
        val oldSeries = directory.newFile("default-123.jpg")
        val volume = directory.newFile("default-456.jpg")
        val newSeries = CoverFiles.newFile(directory.root, CoverStore.Owner.SERIES, "default")
            .apply { writeText("series") }
        CoverFiles.obsolete(directory.root, CoverStore.Owner.SERIES, "default",
            setOf(volume.canonicalFile, newSeries.canonicalFile)).forEach { it.delete() }
        assertFalse(oldSeries.exists())
        assertTrue(volume.exists())
        assertTrue(newSeries.exists())
    }

    @Test fun deletingVolumeKeepsSharedLegacyCoverAndSeriesNamespace() {
        val shared = directory.newFile("default.jpg")
        val series = directory.newFile("series-default-old.jpg")
        val volume = directory.newFile("volume-default-old.jpg")
        CoverFiles.obsolete(directory.root, CoverStore.Owner.VOLUME, "default",
            setOf(shared.canonicalFile, series.canonicalFile)).forEach { it.delete() }
        assertFalse(volume.exists())
        assertTrue(shared.exists())
        assertTrue(series.exists())
        assertEquals(listOf(shared), CoverFiles.obsolete(directory.root, CoverStore.Owner.VOLUME,
            "default", setOf(series.canonicalFile)))
    }

    @Test fun rapidReplacementsHaveDistinctNamesAndCannotTouchAnotherOwner() {
        val first = CoverFiles.newFile(directory.root, CoverStore.Owner.SERIES, "default")
        val second = CoverFiles.newFile(directory.root, CoverStore.Owner.SERIES, "default")
        val volume = CoverFiles.newFile(directory.root, CoverStore.Owner.VOLUME, "default")
        listOf(first, second, volume).forEach { it.writeText("cover") }
        assertNotEquals(first, second)
        assertEquals(listOf(first), CoverFiles.obsolete(directory.root, CoverStore.Owner.SERIES,
            "default", setOf(second.canonicalFile)))
    }
}

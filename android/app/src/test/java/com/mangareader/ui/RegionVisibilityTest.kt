package com.mangareader.ui

import com.mangareader.database.RegionEntity
import com.mangareader.model.Box
import com.mangareader.model.RegionKind
import org.junit.Assert.*
import org.junit.Test

class RegionVisibilityTest {
    private fun region(confidence: Float?) = RegionEntity(
        pageId = "cached-page", regionId = 1L, kind = RegionKind.TEXT_BUBBLE,
        box = Box(0.2f, 0.2f, 0.4f, 0.4f), score = 0.99f,
        readingIndex = 0, panelIndex = 0, bubbleBox = Box(0.1f, 0.1f, 0.5f, 0.5f),
        ocrText = "text", ocrTextEdited = null, ocrConfidence = confidence,
    )

    @Test fun cutoffIsInclusiveAndAppliesToCachedAndEditedRegions() {
        for (confidence in listOf(0f, 0.49f, 0.5f)) {
            val hidden = region(confidence)
            assertFalse(isVisibleRegion(hidden))
            assertFalse(isVisibleRegion(hidden.copy(ocrTextEdited = "corrected")))
            assertNull(hit(listOf(hidden), 0.3f, 0.3f))
            assertNull(hit(listOf(hidden), 0.15f, 0.15f))
        }
    }

    @Test fun higherConfidenceAndUnknownConfidenceRemainReachable() {
        for (confidence in listOf(0.5001f, 0.69f, 1f, null)) {
            val visible = region(confidence)
            assertTrue(isVisibleRegion(visible))
            assertEquals(1L, hit(listOf(visible), 0.3f, 0.3f))
            assertEquals(1L, hit(listOf(visible), 0.15f, 0.15f))
        }
    }
}

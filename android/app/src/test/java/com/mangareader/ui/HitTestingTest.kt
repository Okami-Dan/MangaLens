package com.mangareader.ui

import com.mangareader.model.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class HitTestingTest {
    private val sharedBalloon = Box(0.1f, 0.1f, 0.6f, 0.55f)
    private val targets = listOf(
        HitTarget(1, Box(0.12f, 0.13f, 0.28f, 0.5f), sharedBalloon, 0),
        HitTarget(2, Box(0.32f, 0.13f, 0.52f, 0.43f), sharedBalloon, 1),
    )

    @Test fun sharedBalloonKeepsBothTextRegionsReachable() {
        assertEquals(1L, selectTarget(targets, 0.2f, 0.3f))
        assertEquals(2L, selectTarget(targets, 0.4f, 0.3f))
        assertEquals(1L, selectTarget(targets, 0.2f, 0.52f))
        assertEquals(2L, selectTarget(targets, 0.49f, 0.52f))
    }

    @Test fun emptyPageSpaceHasNoTarget() {
        assertEquals(null, selectTarget(targets, 0.85f, 0.8f))
    }
}

package com.mangareader.ui

import com.mangareader.database.RegionEntity
import com.mangareader.model.Box

/** Pick the text actually touched before considering the larger balloon around it. */
internal data class HitTarget(
    val id: Long,
    val box: Box,
    val bubbleBox: Box?,
    val readingIndex: Int,
)

internal fun hit(regions: List<RegionEntity>, x: Float, y: Float): Long? =
    selectTarget(regions.filter(::isVisibleRegion).map { HitTarget(it.regionId, it.box, it.bubbleBox, it.readingIndex) }, x, y)

/** Apply to cached results too, without deleting the user's OCR or corrections. */
internal fun isVisibleRegion(region: RegionEntity): Boolean =
    region.ocrConfidence?.let { it > 0.5f } ?: true

internal fun selectTarget(targets: List<HitTarget>, x: Float, y: Float): Long? {
    val direct = targets.filter { it.box.contains(x, y) }
    if (direct.isNotEmpty()) {
        return direct.minWithOrNull(
            compareBy<HitTarget>({ it.box.distanceSquared(x, y) }, { it.box.area }, { it.readingIndex }),
        )?.id
    }
    return targets
        .filter { (it.bubbleBox ?: it.box.padded(0.02f)).contains(x, y) }
        .minWithOrNull(
            compareBy<HitTarget>({ it.box.distanceSquared(x, y) }, { it.readingIndex }),
        )?.id
}

private fun Box.contains(x: Float, y: Float): Boolean =
    x >= left && x <= right && y >= top && y <= bottom

private fun Box.distanceSquared(x: Float, y: Float): Float {
    val dx = centerX - x
    val dy = centerY - y
    return dx * dx + dy * dy
}

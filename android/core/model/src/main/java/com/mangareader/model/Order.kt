package com.mangareader.model

/**
 * Stage 4 -- reading order. A port of `harness/pipeline/order.py`.
 *
 * Manga reads right-to-left, top-to-bottom, and naive sorting fails across panels.
 * Section 4 prescribes a recursive X-Y cut and explicitly says not to over-engineer it.
 *
 * The cut runs over the detected region boxes rather than over a binarized page. On a
 * photograph of a printed page, whitespace gutters are not reliably white -- shadow,
 * paper texture and the facing page all bleed in -- whereas the gaps *between regions*
 * are exactly the structure the ordering needs, and they cost no extra image analysis.
 *
 * Section 10 is honest that this will sometimes be wrong, so [reorder] exists for the
 * debug panel's manual override rather than pretending the problem is solved.
 *
 * This lives in `:core:model` rather than a `:ml:` module because it is pure geometry
 * over plain data -- which means it can be tested against the Python on the JVM, with
 * no device and no emulator. See `OrderParityTest`.
 */
object Order {

    /** A gap must be at least this fraction of the page before it counts as a gutter. */
    const val MIN_GAP = 0.02f

    /** Cut positions where a run of intervals has a clear gap between them. */
    private fun gaps(intervals: List<Pair<Float, Float>>): List<Float> {
        // Python sorts tuples lexicographically: by start, then end.
        val spans = intervals.sortedWith(compareBy({ it.first }, { it.second }))
        val cuts = mutableListOf<Float>()
        var reach = spans[0].second
        for ((start, end) in spans.drop(1)) {
            if (start - reach > MIN_GAP) cuts.add((reach + start) / 2)
            reach = maxOf(reach, end)
        }
        return cuts
    }

    private fun split(
        regions: List<Region>,
        cuts: List<Float>,
        key: (Region) -> Float,
    ): List<List<Region>> {
        val groups = List(cuts.size + 1) { mutableListOf<Region>() }
        for (region in regions) {
            val value = key(region)
            groups[cuts.count { value > it }].add(region)
        }
        return groups.filter { it.isNotEmpty() }
    }

    /** Recursively split into bands, emitting regions in manga reading order. */
    private fun xyCut(regions: List<Region>, panels: MutableList<List<Region>>): List<Region> {
        if (regions.size <= 1) {
            if (regions.isNotEmpty()) panels.add(regions)
            return regions.toList()
        }

        // Horizontal cuts first: manga pages are stacks of panel rows, read top to bottom.
        val rowCuts = gaps(regions.map { it.box.top to it.box.bottom })
        if (rowCuts.isNotEmpty()) {
            return split(regions, rowCuts) { it.box.centerY }
                .flatMap { band -> xyCut(band, panels) }
        }

        // Then vertical cuts, right to left.
        val columnCuts = gaps(regions.map { it.box.left to it.box.right })
        if (columnCuts.isNotEmpty()) {
            return split(regions, columnCuts) { it.box.centerX }
                .reversed()
                .flatMap { column -> xyCut(column, panels) }
        }

        // No clean gutter: regions overlap in both axes, so they are one panel's worth
        // of bubbles. Right to left, and top to bottom among bubbles at the same height.
        panels.add(regions)
        return regions.sortedWith(
            compareByDescending<Region> { it.box.right }.thenBy { it.box.top },
        )
    }

    /**
     * Returns the regions in reading order, each carrying its `readingIndex` and
     * `panelIndex`.
     *
     * The Python mutates the regions in place; [Region] is an immutable data class, so
     * this returns copies. Panels are tracked by `Region.id`, which stage 3 assigns
     * uniquely per page, rather than by Python's `id()` identity.
     */
    fun assignReadingOrder(regions: List<Region>): List<Region> {
        if (regions.isEmpty()) return emptyList()
        val panels = mutableListOf<List<Region>>()
        val ordered = xyCut(regions, panels)

        val panelOf = HashMap<Long, Int>()
        panels.forEachIndexed { index, panel -> panel.forEach { panelOf[it.id] = index } }

        return ordered.mapIndexed { index, region ->
            region.copy(readingIndex = index, panelIndex = panelOf[region.id] ?: 0)
        }
    }

    /**
     * Ordering for a cover, which is not a manga page.
     *
     * The right-to-left panel logic in [assignReadingOrder] is about panel flow, and a
     * cover has no panels -- it has a title block, an author line and an obi band
     * stacked down the jacket. Top-to-bottom, then left-to-right, matches how the thing
     * is read.
     */
    fun assignCoverOrder(regions: List<Region>): List<Region> =
        regions
            // Python rounds top to 2dp before sorting, so lines that are level within a
            // hundredth of the page height sort by their left edge instead.
            .sortedWith(
                compareBy(
                    { Math.round(it.box.top * 100f) / 100f },
                    { it.box.left },
                ),
            )
            .mapIndexed { index, region ->
                region.copy(readingIndex = index, panelIndex = 0)
            }

    /** Apply a manual order from the debug panel (section 10's escape hatch). */
    fun reorder(regions: List<Region>, regionIds: List<Long>): List<Region> {
        val byId = regions.associateBy { it.id }
        val chosen = regionIds.mapNotNull { byId[it] }
        val remaining = regions.filter { it.id !in regionIds.toSet() }
        return (chosen + remaining).mapIndexed { index, region ->
            region.copy(readingIndex = index)
        }
    }

    fun boundingBox(regions: List<Region>): Box = Box(
        left = regions.minOf { it.box.left },
        top = regions.minOf { it.box.top },
        right = regions.maxOf { it.box.right },
        bottom = regions.maxOf { it.box.bottom },
    )
}

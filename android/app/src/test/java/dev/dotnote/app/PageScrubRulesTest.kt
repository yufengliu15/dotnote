package dev.dotnote.app

import org.junit.Assert.*
import org.junit.Test

class PageScrubRulesTest {
    @Test
    fun slowDragStepsOnePagePerStep() {
        val g = PageScrub.gain(100f, 40, 800f)
        assertEquals(1f, g * PageScrub.SLOW_STEP_DP, 1e-4f)
    }

    @Test
    fun fastFullHeightDragSpansWholeDocument() {
        val g = PageScrub.gain(3000f, 40, 800f)
        assertEquals(39f, g * 800f, 1e-3f)
        assertEquals(g, PageScrub.gain(-3000f, 40, 800f), 0f)
    }

    @Test
    fun fasterDragsSkipMorePages() {
        val speeds = listOf(0f, 300f, 600f, 900f, 1200f, 1500f)
        val gains = speeds.map { PageScrub.gain(it, 100, 800f) }
        gains.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        assertTrue(gains.last() > gains.first() * 4)
    }

    @Test
    fun shortDocumentsNeverMoveSlowerThanOnePagePerStep() {
        val g = PageScrub.gain(3000f, 3, 800f)
        assertEquals(1f / PageScrub.SLOW_STEP_DP, g, 1e-6f)
    }

    @Test
    fun pagesAreOrderedAndNearestFollowsViewportCenter() {
        val pages =
            (0 until 4).map { i ->
                Item(
                    kind = "PDF",
                    asset = "a.pdf",
                    page = i,
                    points = listOf(Pt(0f, i * 1000f), Pt(800f, i * 1000f + 968f)),
                )
            }
        val ordered = pdfPages(pages.reversed() + Item(kind = "PEN", points = listOf(Pt(0f, 0f))))
        assertEquals(listOf(0, 1, 2, 3), ordered.map { it.page })
        val bounds = ordered.map { it.bounds }
        assertEquals(0, PageScrub.nearest(bounds, -500f))
        assertEquals(2, PageScrub.nearest(bounds, 2400f))
        assertEquals(3, PageScrub.nearest(bounds, 99999f))
    }
}

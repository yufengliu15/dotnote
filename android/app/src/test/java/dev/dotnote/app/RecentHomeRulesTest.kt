package dev.dotnote.app

import org.junit.Assert.*
import org.junit.Test

class RecentHomeRulesTest {
    @Test
    fun rowFitsAsManyFixedWidthCardsAsTheScreenAllows() {
        assertEquals(1, recentCardsPerRow(100f))
        assertEquals(1, recentCardsPerRow(347f))
        assertEquals(2, recentCardsPerRow(348f))
        assertEquals(4, recentCardsPerRow(752f))
        assertEquals(6, recentCardsPerRow(1232f))
        assertEquals(7, recentCardsPerRow(1300f))
    }

    @Test
    fun homeListsExistingNotesOfActiveVaultInOpeningOrder() {
        val notes = (1..8).map { NoteSummary("n$it", null, "Note $it", 0L) }
        val recents =
            listOf(
                RecentNote("v1", "n3", "", ""),
                RecentNote("v2", "n1", "", ""),
                RecentNote("v1", "gone", "", ""),
                RecentNote("v1", "n1", "", ""),
                RecentNote("v1", "n3", "", ""),
                RecentNote("v1", "n7", "", ""),
                RecentNote("v1", "n2", "", ""),
                RecentNote("v1", "n5", "", ""),
                RecentNote("v1", "n8", "", ""),
            )
        val shown = recentNotesFor("v1", recents, notes, limit = 5)
        assertEquals(listOf("n3", "n1", "n7", "n2", "n5"), shown.map { it.id })
        assertTrue(recentNotesFor("v3", recents, notes).isEmpty())
    }
}

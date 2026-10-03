package dev.dotnote.app

import org.junit.Assert.*
import org.junit.Test

class WidgetRulesTest {
    @Test
    fun widgetCapacityUsesAvailableRowsAndColumns() {
        assertEquals(0, widgetSpace(180f, 80f).capacity)
        assertEquals(1, widgetSpace(250f, 160f).capacity)
        assertEquals(3, widgetSpace(250f, 272f).capacity)
        assertEquals(6, widgetSpace(500f, 272f).capacity)
        assertEquals(5, widgetSpace(300f, 384f).capacity)
        assertTrue(widgetSpace(500f, 500f).capacity > widgetSpace(250f, 200f).capacity)
    }

    @Test
    fun folderDestinationShowsFullPathWithDuplicateNames() {
        val top = Folder(id = "top", name = "University")
        val one = Folder(id = "one", parentId = top.id, name = "Notes")
        val two = Folder(id = "two", name = "Notes")
        assertEquals("University / Notes", folderPath(one.id, listOf(top, one, two)))
        assertEquals("Notes", folderPath(two.id, listOf(top, one, two)))
        assertEquals("Vault root", folderPath(null, emptyList()))
    }
}

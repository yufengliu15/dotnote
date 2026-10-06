package dev.dotnote.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorPickRulesTest {
    @Test
    fun onlyEraserReturnsToPenWhenAColourIsPicked() {
        Tool.entries.forEach { tool ->
            val expected = if (tool == Tool.ERASER) Tool.PEN else tool
            assertEquals(tool.name, expected, toolAfterColorPick(tool))
        }
        assertEquals(Tool.HIGHLIGHTER, toolAfterColorPick(Tool.HIGHLIGHTER))
    }
}

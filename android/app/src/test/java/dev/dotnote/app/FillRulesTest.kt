package dev.dotnote.app

import org.junit.Assert.*
import org.junit.Test

class FillRulesTest {
    @Test
    fun closedRegionKeepsHolesAndRejectsOpenBoundary() {
        val w = 12
        val pixels = IntArray(w * w) { -1 }
        for (i in 2..9) {
            pixels[2 * w + i] = 0xff000000.toInt()
            pixels[9 * w + i] = 0xff000000.toInt()
            pixels[i * w + 2] = 0xff000000.toInt()
            pixels[i * w + 9] = 0xff000000.toInt()
        }
        pixels[5 * w + 5] = 0xff000000.toInt()
        val spans = enclosedFillSpans(pixels, w, w, 3, 3)!!
        val covered = spans.flatMap { (a, b) -> (a..b).toList() }.toSet()
        assertEquals(35, covered.size)
        assertFalse(5 * w + 5 in covered)
        assertNull(enclosedFillSpans(pixels, w, w, 0, 0))
        pixels[2 * w + 3] = -1
        assertNull(enclosedFillSpans(pixels, w, w, 3, 3))
    }

    @Test
    fun fillRoundtripTransformsSelectionAndLegacyDefaults() {
        val fill =
            Item(
                kind = "PEN",
                fill = true,
                points = listOf(Pt(10f, 10f), Pt(20f, 20f), Pt(30f, 10f), Pt(40f, 20f)),
                transform = Transform(2f, 2f, 10f, 10f),
            )
        val doc = Document(listOf(fill))
        assertEquals(doc, DocumentCodec.decode(DocumentCodec.encode(doc)))
        assertTrue(hitItem(fill, Pt(40f, 40f), 0f))
        assertFalse(hitItem(fill, Pt(60f, 40f), 0f))
        val old = DocumentCodec.encode(Document(listOf(fill.copy(fill = false))))
        assertFalse(DocumentCodec.decode(old).items.single().fill)
        val invalid = org.json.JSONObject(DocumentCodec.encode(doc))
        invalid.getJSONArray("items").getJSONObject(0).put("kind", "PDF")
        assertThrows(IllegalArgumentException::class.java) {
            DocumentCodec.decode(invalid.toString())
        }
    }
}

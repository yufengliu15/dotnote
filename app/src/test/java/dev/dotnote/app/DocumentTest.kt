package dev.dotnote.app

import org.junit.Assert.*
import org.junit.Test

class DocumentTest {
    @Test
    fun zoomPreservesWorldPointUnderFinger() {
        val camera = Camera(-153f, 84f, .65f)
        val focus = Pt(640f, 300f)
        val before = camera.world(focus)
        val after = camera.zoomAt(focus, 2.5f).world(focus)
        assertEquals(before.x, after.x, .001f)
        assertEquals(before.y, after.y, .001f)
    }

    @Test
    fun folderMovesRejectCyclesAtAnyDepth() {
        val parents = (0..1000).associate { "f$it" to if (it == 0) null else "f${it-1}" }
        assertFalse(canMoveFolder("f0", "f1000", parents))
        assertFalse(canMoveFolder("f5", "f5", parents))
        assertTrue(canMoveFolder("f1000", "f2", parents))
        assertTrue(canMoveFolder("f0", null, parents))
        assertFalse(canMoveFolder("f0", "missing", parents))
    }

    @Test
    fun rejectsAlreadyCorruptFolderCycles() {
        assertFalse(canMoveFolder("other", "a", mapOf("a" to "b", "b" to "a")))
    }

    @Test
    fun documentRoundTripPreservesAttachmentsAndTransforms() {
        val items =
            listOf(
                Item(
                    kind = "GRID",
                    points = listOf(Pt(-30f, 12f), Pt(800f, 500f)),
                    rows = 5,
                    cols = 7,
                    transform = Transform(2f, 3f, 20f, -50f),
                ),
                Item(kind = "PEN", points = listOf(Pt(1f, 2f), Pt(3f, 4f)), ink = "encoded-ink"),
                Item(
                    kind = "PDF",
                    points = listOf(Pt(0f, 0f), Pt(800f, 1000f)),
                    asset = "123abc-ab.pdf",
                    page = 4,
                ),
            )
        val document = Document(items, Camera(-180f, 330f, .125f), false)
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)))
    }

    @Test
    fun oneGestureIsOneUndoAndNewEditsDiscardRedo() {
        val a = listOf(Item(kind = "LINE"))
        val b = a + Item(kind = "CIRCLE")
        val c = a + Item(kind = "GRID")
        val history = History()
        history.push(a)
        assertEquals(a, history.undo(b))
        assertEquals(b, history.redo(a))
        history.undo(b)
        history.push(a)
        assertNull(history.redo(c))
        assertEquals(a, history.undo(c))
    }

    @Test
    fun eraserHitsLinesButNotEmptyInteriorOrLockedPdf() {
        val square = Item(kind = "SQUARE", width = 2f, points = listOf(Pt(0f, 0f), Pt(100f, 100f)))
        assertTrue(hitItem(square, Pt(2f, 50f), 4f))
        assertFalse(hitItem(square, Pt(50f, 50f), 4f))
        assertFalse(hitItem(square.copy(kind = "PDF"), Pt(0f, 0f), 100f))
        val ink =
            square.copy(
                kind = "PEN",
                ink = "raw",
                points = listOf(Pt(0f, 0f), Pt(100f, 100f)),
                transform = Transform(tx = 200f),
            )
        assertTrue(hitItem(ink, Pt(250f, 50f), 2f))
        assertFalse(hitItem(ink, Pt(50f, 50f), 2f))
    }

    @Test
    fun lassoHandlesConcaveRegions() {
        val polygon =
            listOf(
                Pt(0f, 0f),
                Pt(100f, 0f),
                Pt(100f, 40f),
                Pt(40f, 40f),
                Pt(40f, 100f),
                Pt(0f, 100f),
            )
        assertTrue(insidePolygon(Pt(20f, 80f), polygon))
        assertFalse(insidePolygon(Pt(80f, 80f), polygon))
    }

    @Test
    fun resizeKeepsAnchorAndComposesWithPriorMoves() {
        val transformed = Transform(tx = 30f, ty = 20f).resize(Pt(10f, 10f), 2f, 3f)
        assertEquals(Pt(50f, 40f), transformed.map(Pt(0f, 0f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAttachmentTraversal() {
        val document = Document(listOf(Item(kind = "PDF", asset = "../secret.pdf")))
        DocumentCodec.decode(DocumentCodec.encode(document))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFutureSaveVersions() {
        DocumentCodec.decode(
            DocumentCodec.encode(Document()).replace("\"version\":1", "\"version\":2")
        )
    }
}

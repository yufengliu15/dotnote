package dev.dotnote.app

import kotlin.test.*

class PortTest {
    @Test fun portablePressureStrokeRoundTripAndHit() {
        val editor = EditorSession()
        editor.stroke(listOf(Pt(0f, 0f), Pt(20f, 40f), Pt(40f, 0f)), listOf(.2f, 1f, .4f), -1, 3f, false)
        val item = DocumentCodec.decode(editor.encode()).items.single()
        assertEquals(listOf(.2f, 1f, .4f), item.pressures)
        assertTrue(hitItem(item, Pt(20f, 40f), 2f))
        assertFalse(hitItem(item, Pt(20f, 0f), 2f))
    }
    @Test fun historyIsOneOperationAndCancelDoesNotCommit() {
        val editor = EditorSession()
        editor.shape("RECTANGLE", Pt(0f, 0f), Pt(30f, 30f), -1, 2f)
        val saved = editor.encode()
        editor.undo(); assertTrue(editor.document.items.isEmpty())
        editor.redo(); assertEquals(saved, editor.encode())
        editor.load(saved); assertFalse(editor.canUndo)
    }
    @Test fun oldInkAndPdfProvenanceSurvive() {
        val doc = Document(listOf(Item(kind = "PEN", points = listOf(Pt(1f, 2f)), ink = "opaque-native-payload"),
            Item(kind = "PDF", asset = "abcd.pdf", points = listOf(Pt(0f, 0f), Pt(20f, 30f)))))
        assertEquals(doc, DocumentCodec.decode(DocumentCodec.encode(doc)))
        assertTrue(doc.items.last().locked)
        assertTrue(doc.items.first().pressures.isEmpty())
    }
    @Test fun failedLoadDoesNotDestroyCurrentDocument() {
        val editor = EditorSession()
        editor.shape("LINE", Pt(0f, 0f), Pt(20f, 20f), -1, 3f)
        val saved = editor.encode()
        assertFails { editor.load("{}") }
        assertEquals(saved, editor.encode())
    }
    @Test fun selectionAndPdfLockSurviveEditing() {
        val editor = EditorSession()
        editor.addPages("abc.pdf", listOf(100f), listOf(200f), false)
        editor.stroke(listOf(Pt(20f, 20f), Pt(30f, 30f)), emptyList(), -1, 3f, false)
        editor.select(listOf(Pt(20f, 20f)), 3f)
        editor.moveSelection(100f, 0f)
        assertEquals(100f, editor.document.items.last().transform.tx)
        editor.deleteSelection()
        assertEquals("PDF", editor.document.items.single().kind)
        editor.undo(); assertEquals(2, editor.document.items.size)
    }
    @Test fun cameraZoomPreservesFocus() {
        val before = Camera(40f, 20f, 1f)
        val focus = Pt(120f, 100f)
        assertEquals(before.world(focus), before.zoomAt(focus, 2f).world(focus))
    }
}

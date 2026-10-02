package dev.dotnote.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotePreviewTest {
    @Test
    fun snapshotCentersDistantContentWithoutChangingCamera() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val item =
            Item(
                kind = "RECTANGLE",
                color = Color.RED,
                width = 8f,
                points = listOf(Pt(10000f, -5000f), Pt(10200f, -4900f)),
            )
        val document = Document(listOf(item), camera = Camera(-999f, 888f, 8f))
        val bitmap = NotePreviews.render(document, assets, 448, 224)
        var count = 0
        var left = 448
        var right = 0
        var top = 224
        var bottom = 0
        for (y in 0 until 224) for (x in 0 until 448) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 200 && Color.green(pixel) < 80) {
                count++
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        assertTrue(count > 100)
        assertEquals(224f, (left + right) / 2f, 2f)
        assertEquals(112f, (top + bottom) / 2f, 2f)
        assertEquals(Camera(-999f, 888f, 8f), document.camera)
        bitmap.recycle()
    }

    @Test
    fun cacheReusesSnapshotsAndRefreshesAfterEditsAcrossVaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = listOf("preview-${newId()}.db", "preview-${newId()}.db")
        val stores = names.map { Store(context, it) }
        try {
            stores.forEach { it.ready.await() }
            val note =
                Note(
                    title = "Preview fixture",
                    document =
                        DocumentCodec.encode(
                            Document(
                                listOf(
                                    Item(kind = "LINE", points = listOf(Pt(0f, 0f), Pt(100f, 100f)))
                                )
                            )
                        ),
                )
            stores.forEach { it.dao.put(note) }
            val firstNote = stores[0].dao.note(note.id)!!
            val firstKey =
                PreviewKey(stores[0].root.absolutePath, note.id, firstNote.modified, 448, 224)
            val first = NotePreviews.load(stores[0], firstKey)
            assertNotNull(first)
            assertSame(first, NotePreviews.load(stores[0], firstKey))
            val otherNote = stores[1].dao.note(note.id)!!
            assertNotSame(
                first,
                NotePreviews.load(
                    stores[1],
                    firstKey.copy(
                        vault = stores[1].root.absolutePath,
                        modified = otherNote.modified,
                    ),
                ),
            )
            stores[0].dao.save(note.id, DocumentCodec.encode(Document()), firstNote.modified + 1)
            val changed = stores[0].dao.note(note.id)!!
            val next = NotePreviews.load(stores[0], firstKey.copy(modified = changed.modified))!!
            assertNotSame(first, next)
            assertEquals(next.getPixel(0, 0), next.getPixel(224, 112))
            assertEquals(changed, stores[0].dao.note(note.id))
        } finally {
            stores.forEach {
                it.db.close()
                it.root.deleteRecursively()
            }
            names.forEach { context.deleteDatabase(it) }
        }
    }

    @Test
    fun longPdfDeckUsesOnlyFirstPageAndBlankNotesRender() = runBlocking {
        val root =
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "preview-${newId()}",
                )
                .apply { mkdirs() }
        try {
            PdfDocument().useDocument { pdf ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(100, 100, 1).create())
                page.canvas.drawPaint(Paint().apply { color = Color.BLUE })
                pdf.finishPage(page)
                File(root, "page.pdf").outputStream().use { pdf.writeTo(it) }
            }
            val items =
                (0 until 40).map { index ->
                    val y = index * 1032f
                    Item(
                        kind = "PDF",
                        points = listOf(Pt(0f, y), Pt(800f, y + 1000f)),
                        asset = if (index == 0) "page.pdf" else "not-rendered.pdf",
                    )
                }
            val annotation =
                Item(
                    kind = "LINE",
                    color = Color.RED,
                    width = 20f,
                    points = listOf(Pt(100f, 500f), Pt(700f, 500f)),
                )
            val bitmap = NotePreviews.render(Document(items + annotation), root, 448, 224)
            assertTrue(
                "First page must occupy a readable width; later assets must not be opened",
                Color.blue(bitmap.getPixel(180, 80)) > 200 &&
                    Color.red(bitmap.getPixel(180, 80)) < 80,
            )
            assertTrue(
                "First-page annotations remain visible",
                Color.red(bitmap.getPixel(224, 112)) > 200,
            )
            assertEquals(bitmap.getPixel(0, 0), bitmap.getPixel(100, 80))
            bitmap.recycle()
            val blank = NotePreviews.render(Document(), root, 448, 224)
            assertEquals(blank.getPixel(0, 0), blank.getPixel(224, 112))
            blank.recycle()
        } finally {
            root.deleteRecursively()
        }
    }
}

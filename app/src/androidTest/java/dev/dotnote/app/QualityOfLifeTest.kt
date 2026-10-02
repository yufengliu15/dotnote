package dev.dotnote.app

import android.app.Application
import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityOfLifeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun editsDebounceAndCloseFlushesLatestLargeSnapshot() = runBlocking {
        val catalog = VaultCatalog(context)
        val original = catalog.selected()
        val vault = catalog.create("Save regression")
        val models = ViewModelStore()
        lateinit var state: AppState
        try {
            catalog.select(vault.localId)
            withContext(Dispatchers.Main) {
                state = AppState(context.applicationContext as Application)
                models.put("save", state)
            }
            suspend fun ready() =
                withTimeout(20000) {
                    while (withContext(Dispatchers.Main) { state.busy }) delay(20)
                }
            ready()
            withContext(Dispatchers.Main) { state.createNote("Large debounce") }
            ready()
            val id = state.note!!.id
            val initial = state.store.dao.note(id)!!
            val items =
                List(450) { index ->
                    Item(
                        kind = "TEXT",
                        text = "漢字🙂".repeat(1400),
                        points = listOf(Pt(0f, index * 100f), Pt(200f, index * 100f + 80f)),
                    )
                }
            withContext(Dispatchers.Main) { state.commit(items) }
            delay(900)
            withContext(Dispatchers.Main) { state.dots() }
            delay(2300) // Past the first edit's deadline, before the LAST edit's deadline.
            assertEquals(initial.document, state.store.dao.note(id)!!.document)
            assertFalse(withContext(Dispatchers.Main) { state.saved })
            withTimeout(20000) { while (!withContext(Dispatchers.Main) { state.saved }) delay(25) }
            assertEquals(
                items.size,
                DocumentCodec.decode(state.store.dao.note(id)!!.document).items.size,
            )
            withContext(Dispatchers.Main) {
                state.camera(Camera(80f, 90f, 2f))
                state.undo()
                state.redo()
            }
            val expected = withContext(Dispatchers.Main) { state.document }
            val start = SystemClock.elapsedRealtime()
            assertTrue(withContext(Dispatchers.Main) { state.flush() })
            Log.i(
                "DotnoteSave",
                "large flush ms=${SystemClock.elapsedRealtime()-start}; bytes=${DocumentCodec.encode(expected).toByteArray(Charsets.UTF_8).size}",
            )
            assertEquals(expected, DocumentCodec.decode(state.store.dao.note(id)!!.document))
            val modified = state.store.dao.note(id)!!.modified
            delay(3300)
            assertEquals(modified, state.store.dao.note(id)!!.modified)
            withContext(Dispatchers.Main) { state.closeNote() }
            ready()
            assertNull(state.note)
            val persisted = VaultFiles(state.store.root).read().second.single { it.id == id }
            assertEquals(expected, DocumentCodec.decode(persisted.document))
        } finally {
            withContext(Dispatchers.Main) { models.clear() }
            state.store.db.close()
            catalog.select(original)
            catalog.root(vault.localId).deleteRecursively()
            context.deleteDatabase("vault-${vault.localId}.db")
        }
    }

    @Test
    fun bucketRendersPenAndConstantOpacityMarkerAndExports() = runBlocking {
        val database = "fill-${newId()}.db"
        val store = Store(context, database)
        val pdf = File(context.cacheDir, "fill-${newId()}.pdf")
        try {
            store.ready.await()
            val border =
                Item(
                    kind = "RECTANGLE",
                    color = Color.BLACK,
                    width = 3f,
                    points = listOf(Pt(20f, 20f), Pt(160f, 140f)),
                )
            val doc = Document(listOf(border), Camera(0f, 0f, 1f), false)
            val fill =
                bucketFill(
                    doc,
                    store.assets,
                    Pt(50f, 50f),
                    Bounds(0f, 0f, 200f, 180f),
                    Color.RED,
                    false,
                )!!
            fun render(items: List<Item>): Bitmap =
                Bitmap.createBitmap(200, 180, Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    canvas.drawColor(Color.WHITE)
                    ObjectRenderer(false).drawScene(canvas, items, Matrix())
                }
            val pen = render(listOf(border, fill))
            assertEquals(Color.RED, pen.getPixel(50, 50))
            assertEquals(Color.WHITE, pen.getPixel(10, 10))
            assertEquals(Color.BLACK, pen.getPixel(20, 60))
            pen.recycle()
            val marker = fill.copy(kind = "HIGHLIGHTER")
            val highlights = List(4) { marker.copy(id = newId()) } + border
            val marked = render(highlights)
            assertTrue(Color.green(marked.getPixel(50, 50)) in 168..171)
            assertEquals(Color.BLACK, marked.getPixel(20, 60))
            marked.recycle()
            val saved = doc.copy(items = highlights)
            val note = Note(title = "Filled", document = DocumentCodec.encode(saved))
            store.dao.put(note)
            assertEquals(saved, DocumentCodec.decode(store.dao.note(note.id)!!.document))
            val backup = File(context.cacheDir, "fill-backup-${newId()}.zip")
            try {
                store.backup(Uri.fromFile(backup))
                assertEquals(1, store.restore(Uri.fromFile(backup)))
                assertEquals(
                    2,
                    store.dao.allNotes().count {
                        DocumentCodec.decode(it.document).items.count { item -> item.fill } == 4
                    },
                )
            } finally {
                backup.delete()
            }
            PdfFiles.export(store, saved, Uri.fromFile(pdf), Bounds(0f, 0f, 200f, 180f))
            android.graphics.pdf
                .PdfRenderer(
                    android.os.ParcelFileDescriptor.open(
                        pdf,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                )
                .use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap =
                            Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        assertTrue(bitmap.width > 0)
                        assertTrue(
                            (0 until bitmap.height).any { y ->
                                (0 until bitmap.width).any { x ->
                                    val c = bitmap.getPixel(x, y)
                                    Color.red(c) > 240 && Color.green(c) in 160..180
                                }
                            }
                        )
                        bitmap.recycle()
                    }
                }
            assertNull(
                bucketFill(
                    doc,
                    store.assets,
                    Pt(5f, 5f),
                    Bounds(0f, 0f, 200f, 180f),
                    Color.RED,
                    false,
                )
            )
        } finally {
            store.db.close()
            context.deleteDatabase(database)
            store.root.deleteRecursively()
            pdf.delete()
        }
    }
}

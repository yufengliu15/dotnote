package dev.dotnote.app

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorUpdateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun assertSameCoverage(a: Bitmap, b: Bitmap) {
        var maxDelta = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val first = a.getPixel(x, y)
            val second = b.getPixel(x, y)
            for (shift in listOf(0, 8, 16, 24)) {
                maxDelta =
                    maxOf(
                        maxDelta,
                        kotlin.math.abs(
                            ((first ushr shift) and 255) - ((second ushr shift) and 255)
                        ),
                    )
            }
        }
        // Boolean curve union can round antialiased boundary coverage by 1–2/255.
        // Opacity accumulation would create much larger differences.
        assertTrue("Highlight coverage changed by $maxDelta/255", maxDelta <= 2)
    }

    @Test
    fun repeatedHighlightsKeepOpacityAfterReopen() {
        val marker =
            Item(
                kind = "HIGHLIGHTER",
                color = Color.YELLOW,
                width = 21f,
                points = listOf(Pt(30.3f, 30.4f), Pt(140.2f, 80.6f), Pt(70.8f, 80.6f)),
            )
        fun render(items: List<Item>): Bitmap {
            val bitmap = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            ObjectRenderer().drawScene(canvas, items, Matrix())
            return bitmap
        }
        val once = render(listOf(marker))
        val repeated =
            DocumentCodec.decode(
                DocumentCodec.encode(Document(List(8) { marker.copy(id = newId()) }))
            )
        val many = render(repeated.items)
        assertSameCoverage(once, many)
        assertTrue(
            "Single marker remains one-third opaque",
            Color.blue(once.getPixel(90, 80)) in 168..171,
        )
        assertEquals(once.getPixel(90, 80), many.getPixel(90, 80))
        assertNotEquals(Color.WHITE, once.getPixel(90, 80))
        once.recycle()
        many.recycle()
    }

    @Test
    fun pdfHighlightsStayTranslucentAndDoNotAccumulate() {
        val context = instrumentation.targetContext
        val dbName = "highlight-${newId()}.db"
        val store = Store(context, dbName)
        val root = File(context.cacheDir, "highlight-${newId()}").apply { mkdirs() }
        try {
            val marker =
                Item(
                    kind = "HIGHLIGHTER",
                    color = Color.YELLOW,
                    width = 20f,
                    points = listOf(Pt(20f, 50f), Pt(140f, 50f)),
                )
            fun export(count: Int): Bitmap {
                val file = File(root, "$count.pdf")
                PdfFiles.export(
                    store,
                    Document(List(count) { marker.copy(id = newId()) }),
                    Uri.fromFile(file),
                    Bounds(0f, 0f, 180f, 120f),
                )
                return PdfRenderer(
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    )
                    .use { pdf ->
                        pdf.openPage(0).use { page ->
                            Bitmap.createBitmap(612, 842, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(
                                    it,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                            }
                        }
                    }
            }
            val once = export(1)
            val many = export(8)
            assertTrue(
                "Exported highlights remain translucent",
                Color.blue(once.getPixel(200, 180)) in 150..190,
            )
            assertSameCoverage(once, many)
            assertEquals(once.getPixel(200, 180), many.getPixel(200, 180))
            once.recycle()
            many.recycle()
        } finally {
            store.db.close()
            context.deleteDatabase(dbName)
            store.root.deleteRecursively()
            root.deleteRecursively()
        }
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (check()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for editor update")
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let {
                find(it)?.let { found ->
                    return found
                }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun click(label: String) {
        await { node(label) != null }
        var n = node(label)
        while (n != null && !n.isClickable) n = n.parent
        assertTrue(
            "Cannot click $label",
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true,
        )
    }

    private fun setHex(hex: String) {
        fun editable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let {
                editable(it)?.let { found ->
                    return found
                }
            }
            return null
        }
        await { node("Apply") != null }
        val field = editable(instrumentation.uiAutomation.rootInActiveWindow!!)
        assertNotNull(field)
        assertTrue(
            field!!.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        hex,
                    )
                },
            )
        )
        click("Apply")
    }

    @Test
    fun paletteLongPressPersistsAndSelectionPickerSupportsUndo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            var oldColor = 0
            var oldSlot = 0
            var oldDock = "Top"
            scenario.onActivity {
                state = ViewModelProvider(it)[AppState::class.java]
                oldColor = state.color
                oldSlot = state.palette[0]
                oldDock = state.dock
                state.dock = "Top"
                state.createNote("Update UI test")
            }
            await { state.note != null && !state.busy }
            val id = state.note!!.id
            try {
                await { node("Color slot 1") != null }
                var slot = node("Color slot 1")
                while (slot != null && !slot.isLongClickable) slot = slot.parent
                assertTrue(
                    "Palette slot has a long-press action",
                    slot?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true,
                )
                setHex("EF1278")
                await { state.palette[0] == 0xffef1278.toInt() }
                val shape = Item(kind = "RECTANGLE", points = listOf(Pt(30f, 30f), Pt(140f, 90f)))
                scenario.onActivity {
                    state.commit(listOf(shape))
                    state.selection = setOf(shape.id)
                }
                click("Change selection color")
                setHex("1234AB")
                await { state.document.items.single().color == 0xff1234ab.toInt() }
                scenario.onActivity { state.undo() }
                assertEquals(shape.color, state.document.items.single().color)
                scenario.onActivity { state.redo() }
                assertEquals(0xff1234ab.toInt(), state.document.items.single().color)
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
                scenario.recreate()
                scenario.onActivity {
                    state = ViewModelProvider(it)[AppState::class.java]
                    assertEquals(
                        0xffef1278.toInt(),
                        it.getSharedPreferences("writing", 0).getInt("palette_0", 0),
                    )
                    assertEquals(0xffef1278.toInt(), state.palette[0])
                    val models = ViewModelStore()
                    val fresh =
                        ViewModelProvider(
                            models,
                            ViewModelProvider.AndroidViewModelFactory(it.application),
                        )[AppState::class.java]
                    assertEquals(
                        "Fresh process state loads saved slots",
                        0xffef1278.toInt(),
                        fresh.palette[0],
                    )
                    // Store now rebuilds its file index asynchronously on opening.
                    runBlocking { fresh.store.ready.await() }
                    models.clear()
                    fresh.store.db.close()
                    assertFalse(
                        ViewCompat.getRootWindowInsets(it.window.decorView)!!.isVisible(
                            WindowInsetsCompat.Type.navigationBars()
                        )
                    )
                }
            } finally {
                scenario.onActivity {
                    state.updatePalette(0, oldSlot)
                    state.color = oldColor
                    state.dock = oldDock
                }
                runBlocking { state.store.dao.deleteNote(id) }
            }
        }
    }
}

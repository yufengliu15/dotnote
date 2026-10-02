package dev.dotnote.app

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextTemplateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(30)
        }
        fail("Timed out")
    }

    private fun canvas(view: View): NotebookView? {
        if (view is NotebookView) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) canvas(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun node(
        root: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        root ?: return null
        if (match(root)) return root
        for (i in 0 until root.childCount) node(root.getChild(i), match)?.let {
            return it
        }
        return null
    }

    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var target: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < deadline) {
            target =
                node(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString() == label || it.contentDescription?.toString() == label
                }
            if (target != null) break
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(
            "Cannot click $label",
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true,
        )
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200) // Let the compositor display the latest Compose state.
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun fillText(value: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (
            node(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } == null &&
                SystemClock.uptimeMillis() < deadline
        ) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
        val field =
            node(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable }
                ?: error("No text field")
        assertTrue(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                },
            )
        )
        instrumentation.waitForIdleSync()
    }

    private fun inputText(value: String) {
        fillText(value)
        click("Save text")
    }

    private fun gesture(
        view: NotebookView,
        from: Pt,
        to: Pt = from,
        tool: Int = MotionEvent.TOOL_TYPE_STYLUS,
        cancel: Boolean = false,
    ) {
        val down = SystemClock.uptimeMillis()
        val density = view.resources.displayMetrics.density
        for (step in 0..4) {
            val event =
                MotionEvent.obtain(
                    down,
                    down + step * 16L,
                    if (step == 0) MotionEvent.ACTION_DOWN
                    else if (step == 4)
                        if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_MOVE,
                    1,
                    arrayOf(
                        MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = tool
                        }
                    ),
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = (from.x + (to.x - from.x) * step / 4) * density
                            y = (from.y + (to.y - from.y) * step / 4) * density
                            pressure = .6f
                        }
                    ),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN
                    else InputDevice.SOURCE_STYLUS,
                    0,
                )
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun textTapDialogEditingSelectionHistoryAndReopen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            var view: NotebookView? = null
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            await { !state.busy }
            scenario.onActivity { state.createNote("Text regression") }
            await { state.note != null && !state.busy }
            val id = state.note!!.id
            try {
                await {
                    scenario.onActivity { view = canvas(it.window.decorView) }
                    (view?.width ?: 0) > 0
                }
                scenario.onActivity {
                    state.camera(Camera(20f, 30f, 2f))
                    state.tool = Tool.TEXT
                    gesture(view!!, Pt(200f, 180f), cancel = true)
                    assertNull(state.textEdit)
                    gesture(view!!, Pt(200f, 180f))
                    assertEquals(Pt(90f, 75f), state.textEdit!!.position)
                }
                await { state.textEdit != null }
                scenario.recreate()
                await {
                    scenario.onActivity { view = canvas(it.window.decorView) }
                    (view?.width ?: 0) > 0 && state.textEdit != null
                }
                instrumentation.waitForIdleSync()
                fillText("Hello 你好\nSecond line")
                screenshot("text-dialog.png")
                click("Save text")
                await { state.textEdit == null && state.saved && state.document.items.size == 1 }
                val original = state.document.items.single()
                assertEquals("TEXT", original.kind)
                assertTrue(original.bounds.height > original.fontSize)
                scenario.onActivity {
                    state.fingerDrawing = true
                    gesture(view!!, Pt(210f, 190f), tool = MotionEvent.TOOL_TYPE_FINGER)
                    assertEquals(original.id, state.textEdit!!.item!!.id)
                }
                inputText("Edited text")
                await { state.textEdit == null && state.saved }
                assertEquals(original.id, state.document.items.single().id)
                scenario.onActivity { state.undo() }
                assertEquals(original, state.document.items.single())
                scenario.onActivity {
                    state.redo()
                    state.tool = Tool.LASSO
                    gesture(view!!, Pt(220f, 210f))
                }
                assertEquals(setOf(original.id), state.selection)
                scenario.onActivity {
                    gesture(view!!, Pt(220f, 210f), Pt(300f, 250f))
                    state.recolorSelection(Color.BLUE)
                }
                val moved = state.document.items.single()
                assertEquals(40f, moved.transform.tx, .1f)
                assertEquals(Color.BLUE, moved.color)
                scenario.onActivity {
                    state.selectionSizing = SelectionSizing.SCALE
                    val camera = state.document.camera
                    val handle =
                        Pt(
                            camera.x + moved.bounds.right * camera.zoom,
                            camera.y + moved.bounds.bottom * camera.zoom,
                        )
                    gesture(
                        view!!,
                        handle,
                        Pt(
                            handle.x + 80f,
                            handle.y + 80f * moved.bounds.height / moved.bounds.width,
                        ),
                    )
                }
                assertEquals(
                    moved.bounds.width + 40f,
                    state.document.items.single().bounds.width,
                    .2f,
                )
                assertEquals(
                    moved.bounds.height * ((moved.bounds.width + 40f) / moved.bounds.width),
                    state.document.items.single().bounds.height,
                    .2f,
                )
                scenario.onActivity { state.undo() }
                assertEquals(moved, state.document.items.single())
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
                scenario.onActivity { state.open(id) }
                await { state.note?.id == id && !state.busy }
                assertEquals(moved, state.document.items.single())
                scenario.onActivity {
                    state.selection = setOf(original.id)
                    state.deleteSelection()
                    state.undo()
                }
                assertEquals(moved, state.document.items.single())
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
            } finally {
                runBlocking { state.store.dao.deleteNote(id) }
            }
        }
    }

    @Test
    fun resizeReflowsWhileScalePreservesWrappingAndEditingKeepsBoxWidth() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            var view: NotebookView? = null
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            await { !state.busy }
            scenario.onActivity { state.createNote("Text sizing regression") }
            await { state.note != null && !state.busy }
            val noteId = state.note!!.id
            try {
                await {
                    scenario.onActivity { view = canvas(it.window.decorView) }
                    (view?.width ?: 0) > 0
                }
                val narrow =
                    reflowText(textItem("like this", 24f, Color.BLACK, Pt(100f, 100f)), 55f)
                assertEquals(
                    2,
                    textLayout(narrow.text!!, narrow.fontSize, narrow.color, 55).lineCount,
                )
                scenario.onActivity {
                    state.camera(Camera(0f, 0f, 1f))
                    state.tool = Tool.LASSO
                    state.commit(listOf(narrow))
                    state.selection = setOf(narrow.id)
                }
                click("Resize")
                scenario.onActivity {
                    gesture(
                        view!!,
                        Pt(narrow.bounds.right, narrow.bounds.bottom),
                        Pt(narrow.bounds.left + 180f, narrow.bounds.bottom + 80f),
                    )
                }
                val wide = state.document.items.single()
                assertEquals(24f, wide.fontSize, 0f)
                assertEquals(narrow.transform, wide.transform)
                assertEquals(180f, wide.bounds.width, .1f)
                assertTrue(wide.bounds.height < narrow.bounds.height)
                assertEquals(1, textLayout(wide.text!!, wide.fontSize, wide.color, 180).lineCount)
                screenshot("text-resize.png")
                scenario.onActivity { state.undo() }
                assertEquals(narrow, state.document.items.single())
                scenario.onActivity {
                    state.redo()
                    state.requestText(Pt(120f, 110f))
                    state.applyText("like this", 28f)
                }
                val edited = state.document.items.single()
                assertEquals(180f, edited.bounds.width, .1f)
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
                scenario.onActivity { state.open(noteId) }
                await { state.note?.id == noteId && !state.busy }
                assertEquals(edited, state.document.items.single())
                await {
                    scenario.onActivity { view = canvas(it.window.decorView) }
                    (view?.width ?: 0) > 0
                }
                scenario.onActivity {
                    state.selection = setOf(edited.id)
                    state.tool = Tool.LASSO
                }
                click("Scale")
                scenario.onActivity {
                    gesture(
                        view!!,
                        Pt(edited.bounds.right, edited.bounds.bottom),
                        Pt(edited.bounds.left + edited.bounds.width * 2, edited.bounds.bottom),
                    )
                }
                val scaled = state.document.items.single()
                assertEquals(edited.points, scaled.points)
                assertEquals(edited.text, scaled.text)
                assertEquals(edited.fontSize, scaled.fontSize, 0f)
                assertEquals(edited.transform.sx * 2, scaled.transform.sx, .01f)
                assertEquals(edited.transform.sy * 2, scaled.transform.sy, .01f)
                screenshot("text-scale.png")
                scenario.onActivity {
                    gesture(
                        view!!,
                        Pt(scaled.bounds.right, scaled.bounds.bottom),
                        Pt(scaled.bounds.left + scaled.bounds.width / 2, scaled.bounds.bottom),
                    )
                }
                val shrunk = state.document.items.single()
                assertEquals(edited.transform.sx, shrunk.transform.sx, .01f)
                assertEquals(edited.transform.sy, shrunk.transform.sy, .01f)
                assertEquals(edited.points, shrunk.points)
                scenario.onActivity { state.undo() }
                assertEquals(scaled, state.document.items.single())
                scenario.onActivity { state.undo() }
                assertEquals(edited, state.document.items.single())
                // Explicit paragraph breaks survive reflow; only soft wrapping changes.
                val paragraphs = textItem("like\nthis", 24f, Color.BLACK, Pt(0f, 0f))
                assertEquals(
                    2,
                    textLayout(reflowText(paragraphs, 300f).text!!, 24f, Color.BLACK, 300).lineCount,
                )
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
            } finally {
                runBlocking { state.store.dao.deleteNote(noteId) }
            }
        }
    }

    @Test
    fun textRendersAfterTransformAndInPdfExport() {
        val dbName = "text-render-${newId()}.db"
        val store = Store(context, dbName)
        val file = File(context.cacheDir, "text-${newId()}.pdf")
        val item =
            textItem("Hello\n你好", 28f, Color.BLUE, Pt(15f, 10f))
                .copy(transform = Transform(1.5f, 1.5f, 20f, 10f))
        fun bluePixels(bitmap: Bitmap) =
            (0 until bitmap.height).sumOf { y ->
                (0 until bitmap.width).count { x ->
                    val color = bitmap.getPixel(x, y)
                    Color.blue(color) > Color.red(color) + 50
                }
            }
        try {
            val restored =
                DocumentCodec.decode(DocumentCodec.encode(Document(listOf(item), dots = false)))
            val bitmap = Bitmap.createBitmap(300, 250, Bitmap.Config.ARGB_8888)
            val surface = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            ObjectRenderer().drawScene(surface, restored.items, Matrix())
            assertTrue("Text must render visibly", bluePixels(bitmap) > 200)
            assertEquals(Color.WHITE, bitmap.getPixel(10, 10))
            bitmap.recycle()
            PdfFiles.export(store, restored, Uri.fromFile(file), Bounds(0f, 0f, 300f, 250f))
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                pdf ->
                pdf.openPage(0).use { page ->
                    val output =
                        Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    output.eraseColor(Color.WHITE)
                    page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    assertTrue("PDF must include text", bluePixels(output) > 200)
                    output.recycle()
                }
            }
        } finally {
            store.db.close()
            context.deleteDatabase(dbName)
            store.root.deleteRecursively()
            file.delete()
        }
    }

    private fun inIsolatedVault(block: () -> Unit) {
        val catalog = VaultCatalog(context)
        val previous = catalog.selected()
        val fixture = catalog.create("Isolated template backup test")
        catalog.select(fixture.localId)
        try {
            block()
        } finally {
            catalog.select(previous)
            BackupScheduler.cancel(context, fixture.localId)
            context.deleteDatabase("vault-${fixture.localId}.db")
            catalog.root(fixture.localId).deleteRecursively()
        }
    }

    @Test
    fun templateCopiesStayIndependentAndSurviveFileRebuildAndBackup() = inIsolatedVault {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            await { !state.busy }
            scenario.onActivity { state.createNote("Reusable template", isTemplate = true) }
            await { state.note?.isTemplate == true && !state.busy }
            val sourceId = state.note!!.id
            val createdIds = mutableListOf(sourceId)
            val backup = File(context.cacheDir, "templates-${newId()}.zip")
            val pdfAsset = "${newId()}.pdf"
            val beforeIds = runBlocking { state.store.dao.allNotes().map { it.id }.toSet() }
            try {
                val pdf = android.graphics.pdf.PdfDocument()
                try {
                    val page =
                        pdf.startPage(
                            android.graphics.pdf.PdfDocument.PageInfo.Builder(100, 100, 1).create()
                        )
                    page.canvas.drawColor(Color.RED)
                    pdf.finishPage(page)
                    File(state.store.assets, pdfAsset).outputStream().use(pdf::writeTo)
                } finally {
                    pdf.close()
                }
                scenario.onActivity {
                    state.commit(
                        listOf(
                            textItem("Heading", 30f, Color.BLACK, Pt(0f, 0f)),
                            Item(
                                kind = "PDF",
                                asset = pdfAsset,
                                points = listOf(Pt(0f, 100f), Pt(100f, 200f)),
                            ),
                        )
                    )
                    state.camera(Camera(12f, 25f, .8f))
                    state.dots()
                    state.createNote("Template copy", templateId = sourceId)
                }
                await { state.note?.id != sourceId && !state.busy }
                val copyId = state.note!!.id
                createdIds.add(copyId)
                val source = runBlocking {
                    DocumentCodec.decode(state.store.dao.note(sourceId)!!.document)
                }
                assertFalse(state.note!!.isTemplate)
                assertEquals(source.camera, state.document.camera)
                assertEquals(source.dots, state.document.dots)
                assertEquals(
                    source.items.map { it.copy(id = "same") },
                    state.document.items.map { it.copy(id = "same") },
                )
                assertTrue(
                    source.items
                        .map { it.id }
                        .toSet()
                        .intersect(state.document.items.map { it.id }.toSet())
                        .isEmpty()
                )
                scenario.onActivity {
                    state.commit(emptyList())
                    state.closeNote()
                }
                await { state.note == null && !state.busy }
                runBlocking {
                    state.store.reload()
                    assertTrue(state.store.dao.note(sourceId)!!.isTemplate)
                    assertEquals(
                        source,
                        DocumentCodec.decode(state.store.dao.note(sourceId)!!.document),
                    )
                    state.store.backup(Uri.fromFile(backup))
                    state.store.restore(Uri.fromFile(backup))
                    val restored =
                        state.store.dao.allNotes().filter {
                            it.id !in beforeIds && it.id !in createdIds
                        }
                    createdIds.addAll(restored.map { it.id })
                    val template =
                        restored.single { it.isTemplate && it.title == "Reusable template" }
                    val document = DocumentCodec.decode(template.document)
                    assertEquals("Heading", document.items.first().text)
                    assertTrue(File(state.store.assets, document.items.last().asset!!).isFile)
                    assertTrue(state.vaultTemplates(state.store.vaultId).any { it.id == sourceId })
                }
            } finally {
                runBlocking { createdIds.forEach { state.store.dao.deleteNote(it) } }
                backup.delete()
            }
        }
    }

    @Test
    fun newNoteDialogCreatesTemplatesAndUsesPickerWithoutChangingSource() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            await { !state.busy }
            val ids = mutableListOf<String>()
            try {
                scenario.onActivity { state.newNoteRequested = true }
                fillText("UI template")
                click("Create as template")
                click("Create note")
                await { state.note?.isTemplate == true && !state.busy }
                val source = state.note!!.id
                ids.add(source)
                scenario.onActivity {
                    state.commit(listOf(textItem("Reusable heading", 32f, Color.BLACK, Pt(0f, 0f))))
                    state.closeNote()
                }
                await { state.note == null && !state.busy }
                scenario.onActivity { state.newNoteRequested = true }
                fillText("UI copy")
                click("Choose template")
                click("UI template")
                screenshot("template-dialog.png")
                click("Create note")
                await { state.note != null && state.note?.id != source && !state.busy }
                ids.add(state.note!!.id)
                assertFalse(state.note!!.isTemplate)
                assertEquals("Reusable heading", state.document.items.single().text)
                scenario.onActivity { state.setTemplate(true) }
                await { state.note?.isTemplate == true && !state.busy }
                scenario.onActivity { state.setTemplate(false) }
                await { state.note?.isTemplate == false && !state.busy }
                assertTrue(runBlocking { state.store.dao.note(source)!!.isTemplate })
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
            } finally {
                scenario.onActivity { state.newNoteRequested = false }
                runBlocking { ids.forEach { state.store.dao.deleteNote(it) } }
            }
        }
    }

    @Test
    fun schemaOneMigrationPreservesExistingNotesWithNonTemplateDefault() = runBlocking {
        val name = "template-migration-${newId()}.db"
        context.openOrCreateDatabase(name, 0, null).use { old ->
            old.execSQL(
                "CREATE TABLE folders (id TEXT NOT NULL PRIMARY KEY, parentId TEXT, name TEXT NOT NULL)"
            )
            old.execSQL(
                "CREATE TABLE notes (id TEXT NOT NULL PRIMARY KEY, folderId TEXT, title TEXT NOT NULL, modified INTEGER NOT NULL, document TEXT NOT NULL)"
            )
            old.execSQL("CREATE INDEX index_notes_folderId ON notes(folderId)")
            old.execSQL(
                "INSERT INTO notes VALUES (?, NULL, 'Existing', 123, ?)",
                arrayOf("old-note", DocumentCodec.encode(Document())),
            )
            old.version = 1
        }
        val database =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(LibraryDatabase.MIGRATION_1_2)
                .build()
        try {
            val note = database.dao().note("old-note")!!
            assertFalse(note.isTemplate)
            assertEquals("Existing", note.title)
            assertEquals(123L, note.modified)
            assertEquals(Document(), DocumentCodec.decode(note.document))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}

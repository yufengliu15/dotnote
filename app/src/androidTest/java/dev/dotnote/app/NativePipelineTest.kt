package dev.dotnote.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePipelineTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeInkSurvivesSerializationAndRenders() {
        val inputs =
            MutableStrokeInputBatch().apply {
                add(StrokeInput().apply { update(20f, 20f, 0L) })
                add(StrokeInput().apply { update(100f, 80f, 30L) })
                add(StrokeInput().apply { update(180f, 20f, 60L) })
            }
        val item = strokeItem(Stroke(brush(Color.BLACK, 8f, false), inputs), false)
        val restored =
            DocumentCodec.decode(DocumentCodec.encode(Document(listOf(item)))).items.single()
        val bitmap = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        ObjectRenderer().draw(canvas, restored, Matrix())
        var nonWhite = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (
            bitmap.getPixel(x, y) != Color.WHITE
        )
            nonWhite++
        assertTrue("Native stroke must render after reopening", nonWhite > 100)
        bitmap.recycle()
    }

    @Test
    fun pdfImportExportBackupRestoreAndFailedRestoreAreConsistent() = runBlocking {
        val root = File(context.cacheDir, "pipeline-${newId()}").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            }
        val dbName = "pipeline-${newId()}.db"
        val store = Store(isolated, dbName)
        try {
            val pdfFile = File(root, "input.pdf")
            PdfDocument().useDocument { pdf ->
                repeat(3) { index ->
                    val page =
                        pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                    page.canvas.drawText(
                        "Test page ${index+1}",
                        30f,
                        50f,
                        Paint().apply { textSize = 24f },
                    )
                    pdf.finishPage(page)
                }
                pdfFile.outputStream().use(pdf::writeTo)
            }
            val imported = PdfFiles.import(store, Uri.fromFile(pdfFile), 0f)
            assertEquals(3, imported.size)
            assertTrue(imported.zipWithNext().all { (a, b) -> a.bounds.bottom < b.bounds.top })
            val doc =
                Document(
                    imported +
                        Item(kind = "RECTANGLE", points = listOf(Pt(10f, 10f), Pt(120f, 120f)))
                )
            val folder = Folder(name = "Root")
            val child = Folder(parentId = folder.id, name = "Nested")
            store.dao.put(folder)
            store.dao.put(child)
            store.dao.put(
                Note(
                    title = "Test document",
                    folderId = child.id,
                    document = DocumentCodec.encode(doc),
                )
            )
            val output = File(root, "export.pdf")
            PdfFiles.export(store, doc, Uri.fromFile(output), Bounds(0f, 0f, 800f, 1200f))
            assertTrue(output.length() > 1000)
            val backup = File(root, "backup.zip")
            store.backup(Uri.fromFile(backup))
            assertEquals(1, store.restore(Uri.fromFile(backup)))
            assertEquals(2, store.dao.allNotes().size)
            assertEquals(4, store.dao.allFolders().size)
            val restored = store.dao.allNotes().last()
            val restoredDoc = DocumentCodec.decode(restored.document)
            assertTrue(
                restoredDoc.items
                    .filter { it.asset != null }
                    .all { File(store.assets, it.asset!!).isFile }
            )
            val bad = File(root, "bad.zip")
            ZipOutputStream(bad.outputStream()).use {
                it.putNextEntry(ZipEntry("../escape"))
                it.write("invalid".toByteArray())
                it.closeEntry()
            }
            assertTrue(runCatching { store.restore(Uri.fromFile(bad)) }.isFailure)
            assertEquals(2, store.dao.allNotes().size)
        } finally {
            store.db.close()
            context.deleteDatabase(dbName)
            root.deleteRecursively()
        }
    }
}

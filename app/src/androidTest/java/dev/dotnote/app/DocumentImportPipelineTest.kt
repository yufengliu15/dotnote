package dev.dotnote.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentImportPipelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withStore(block: suspend (Store, File) -> Unit) = runBlocking {
        val root = File(context.cacheDir, "imports-${newId()}").apply { mkdirs() }
        val dbName = "imports-${newId()}.db"
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            }
        val store = Store(isolated, dbName)
        try {
            store.ready.await()
            block(store, root)
        } finally {
            store.db.close()
            context.deleteDatabase(dbName)
            root.deleteRecursively()
        }
    }

    private fun png(width: Int = 160, height: Int = 80): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            Canvas(bitmap)
                .drawRect(
                    width / 2f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    Paint().apply { color = Color.BLUE },
                )
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun render(store: Store, item: Item): Bitmap =
        PdfPageSource(store.assets).use { it.render(item, 800) }

    @Test
    fun imageImportPreservesAspectPixelsAndSurvivesBackup() = withStore { store, root ->
        val image = File(root, "photo.PNG").apply { writeBytes(png()) }
        val imported = DocumentImport.import(store, Uri.fromFile(image), 100f)
        val item = imported.items.single()
        assertTrue(item.image)
        assertFalse(item.locked)
        assertEquals("PDF", item.kind)
        assertEquals(100f, item.bounds.top, .01f)
        assertEquals(400f, item.bounds.height, .01f)
        render(store, item).let { bitmap ->
            assertEquals(Color.RED, bitmap.getPixel(100, 100))
            assertEquals(Color.BLUE, bitmap.getPixel(700, 100))
            bitmap.recycle()
        }
        val moved = item.copy(transform = Transform(.5f, .5f, 100f, 200f))
        val export = File(root, "moved.pdf")
        PdfFiles.export(
            store,
            Document(listOf(moved)),
            Uri.fromFile(export),
            Bounds(0f, 0f, 800f, 800f),
        )
        PdfPageSource(root).use { source ->
            source.render(Item(kind = "PDF", asset = export.name), 612).let { bitmap ->
                assertEquals(Color.WHITE, bitmap.getPixel(40, 40))
                assertEquals(Color.RED, bitmap.getPixel(165, 235))
                assertEquals(Color.BLUE, bitmap.getPixel(306, 235))
                bitmap.recycle()
            }
        }
        val note =
            Note(
                title = "Imported image",
                document = DocumentCodec.encode(Document(imported.items)),
            )
        store.dao.put(note)
        image.delete()
        val zip = File(root, "backup.zip")
        store.backup(Uri.fromFile(zip))
        assertEquals(1, store.restore(Uri.fromFile(zip)))
        val restored =
            DocumentCodec.decode(store.dao.allNotes().first { it.id != note.id }.document)
        assertTrue(File(store.assets, restored.items.single().asset!!).isFile)
        assertTrue(restored.items.single().image)
        assertTrue(
            store.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("import-") }
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun jpegRotationIsAppliedAndImageDecodeIsBounded() = withStore { store, root ->
        val jpeg = File(root, "rotated.jpg")
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        Canvas(bitmap).drawRect(80f, 0f, 160f, 80f, Paint().apply { color = Color.BLUE })
        jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        ExifInterface(jpeg.path).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        val item = DocumentImport.import(store, Uri.fromFile(jpeg), 0f).items.single()
        assertEquals(1600f, item.bounds.height, .1f)
        render(store, item).let { rendered ->
            assertTrue(Color.red(rendered.getPixel(400, 200)) > 240)
            assertTrue(Color.blue(rendered.getPixel(400, 1400)) > 240)
            rendered.recycle()
        }
        val large = File(root, "wide.png")
        Bitmap.createBitmap(5000, 20, Bitmap.Config.ARGB_8888).let { wide ->
            large.outputStream().use { wide.compress(Bitmap.CompressFormat.PNG, 100, it) }
            wide.recycle()
        }
        DocumentImport.decodeImage(android.graphics.ImageDecoder.createSource(large)).let { decoded
            ->
            assertEquals(4096, decoded.width)
            decoded.recycle()
        }
    }

    private val ns =
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private fun relationships(vararg rels: Triple<String, String, String>) =
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            rels.joinToString("") { (id, type, target) ->
                "<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" Target=\"$target\"/>"
            } +
            "</Relationships>"

    private fun slide(contents: String, background: String = "FFFFFF") =
        "<p:sld $ns><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$background\"/></a:solidFill></p:bgPr></p:bg><p:spTree>$contents</p:spTree></p:cSld></p:sld>"

    private fun deck(root: File, extra: Map<String, ByteArray> = emptyMap()): File {
        val parts =
            mapOf(
                    "_rels/.rels" to
                        relationships(Triple("office", "officeDocument", "ppt/presentation.xml")),
                    // Reversed relationship/numeric attribute order and reversed file numbering are
                    // intentional.
                    "ppt/presentation.xml" to
                        "<p:presentation $ns><p:sldIdLst><p:sldId r:id=\"second\" id=\"256\"/><p:sldId id=\"257\" r:id=\"first\"/></p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"5143500\"/></p:presentation>",
                    "ppt/_rels/presentation.xml.rels" to
                        relationships(
                            Triple("first", "slide", "slides/slide1.xml"),
                            Triple("second", "slide", "slides/slide2.xml"),
                        ),
                    "ppt/slides/slide1.xml" to slide("", "00FF00"),
                    "ppt/slides/slide2.xml" to
                        slide(
                            """
                <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:rPr sz="2400"/><a:t>Offline slides</a:t></a:r></a:p></p:txBody></p:sp>
                <p:pic><p:spPr><a:xfrm><a:off x="5080000" y="1270000"/><a:ext cx="2540000" cy="1270000"/></a:xfrm></p:spPr><p:blipFill><a:blip r:embed="picture"/></p:blipFill></p:pic>
                <p:sp><p:spPr><a:xfrm><a:off x="1270000" y="2540000"/><a:ext cx="1270000" cy="1270000"/></a:xfrm><a:prstGeom prst="ellipse"/><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:spPr></p:sp>
            """
                                .trimIndent()
                        ),
                    "ppt/slides/_rels/slide2.xml.rels" to
                        relationships(
                            Triple("picture", "image", "../media/picture.png"),
                            Triple("layout", "slideLayout", "../slideLayouts/layout.xml"),
                        ),
                    "ppt/slideLayouts/layout.xml" to
                        "<p:sldLayout $ns><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"508000\" y=\"508000\"/><a:ext cx=\"5080000\" cy=\"1016000\"/></a:xfrm></p:spPr></p:sp></p:spTree></p:cSld></p:sldLayout>",
                )
                .mapValues { it.value.toByteArray() } +
                mapOf("ppt/media/picture.png" to png()) +
                extra
        return File(root, "lecture.pptx").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                parts.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    @Test
    fun powerpointConvertsInPresentationOrderWithImagesShapesAndInheritedText() =
        withStore { store, root ->
            val input = deck(root)
            val result = DocumentImport.import(store, Uri.fromFile(input), 0f)
            assertEquals(2, result.items.size)
            assertTrue(result.message.contains("Converted 2 PowerPoint slides to PDF"))
            assertEquals(450f, result.items.first().bounds.height, .1f)
            assertTrue(result.items[1].bounds.top > result.items[0].bounds.bottom)
            render(store, result.items[0]).let { bitmap ->
                assertEquals(Color.RED, bitmap.getPixel(170, 280)) // ellipse
                assertEquals(Color.RED, bitmap.getPixel(470, 160)) // image left
                assertEquals(Color.BLUE, bitmap.getPixel(630, 160)) // image right
                var dark = 0
                for (y in 40..110) for (x in 40..400) if (Color.red(bitmap.getPixel(x, y)) < 100)
                    dark++
                assertTrue("Placeholder inherits its layout position and draws text", dark > 50)
                bitmap.recycle()
            }
            input.delete()
            render(store, result.items[1]).let { bitmap ->
                assertEquals(Color.GREEN, bitmap.getPixel(20, 20))
                bitmap.recycle()
            }
        }

    @Test
    fun imageHeavyDeckExceedingTheOldPixelBudgetImportsEverySlide() = withStore { store, root ->
        val count = 24
        val parts = mutableMapOf<String, ByteArray>()
        val ids = (1..count).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"r$it\"/>" }
        parts["ppt/presentation.xml"] =
            ("<p:presentation $ns><p:sldIdLst>$ids</p:sldIdLst>" +
                    "<p:sldSz cx=\"9144000\" cy=\"5143500\"/></p:presentation>")
                .toByteArray()
        parts["ppt/_rels/presentation.xml.rels"] =
            relationships(
                    *(1..count)
                        .map { Triple("r$it", "slide", "slides/slide$it.xml") }
                        .toTypedArray()
                )
                .toByteArray()
        parts["ppt/media/picture.png"] = png(2048, 1152)
        for (i in 1..count) {
            parts["ppt/slides/slide$i.xml"] =
                slide(
                        """
                <p:pic><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="9144000" cy="5143500"/></a:xfrm></p:spPr><p:blipFill><a:blip r:embed="picture"/></p:blipFill></p:pic>
            """
                            .trimIndent()
                    )
                    .toByteArray()
            parts["ppt/slides/_rels/slide$i.xml.rels"] =
                relationships(Triple("picture", "image", "../media/picture.png")).toByteArray()
        }
        // 56.6 million decoded image pixels used to fail the 32 Mi-pixel deck-wide limit.
        val result = DocumentImport.import(store, Uri.fromFile(deck(root, parts)), 0f)
        assertEquals(count, result.items.size)
        assertTrue(result.items.all { it.locked && !it.image })
        assertEquals((0 until count).toList(), result.items.map { it.page })
        for (item in listOf(result.items.first(), result.items.last())) {
            render(store, item).let { bitmap ->
                assertEquals(Color.RED, bitmap.getPixel(100, 100))
                assertEquals(Color.BLUE, bitmap.getPixel(700, 100))
                bitmap.recycle()
            }
        }
    }

    @Test
    fun unsupportedContentIsReportedAndFailedConversionsLeaveNoAttachment() =
        withStore { store, root ->
            val unsupported =
                deck(
                    root,
                    mapOf("ppt/slides/slide1.xml" to slide("<p:graphicFrame/>").toByteArray()),
                )
            val result = DocumentImport.import(store, Uri.fromFile(unsupported), 0f)
            assertTrue(result.conversionReport!!.contains("unsupported objects are omitted"))
            val count = store.assets.listFiles().orEmpty().size
            val malicious =
                deck(
                    root,
                    mapOf(
                        "ppt/slides/slide1.xml" to
                            ("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" +
                                    slide("<p:sp>&secret;</p:sp>"))
                                .toByteArray()
                    ),
                )
            assertTrue(
                runCatching { DocumentImport.import(store, Uri.fromFile(malicious), 0f) }.isFailure
            )
            val broken = File(root, "broken.png").apply { writeText("not an image") }
            assertTrue(
                runCatching { DocumentImport.import(store, Uri.fromFile(broken), 0f) }.isFailure
            )
            assertEquals(count, store.assets.listFiles().orEmpty().size)
            assertTrue(
                store.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("import-") }
            )
        }
}

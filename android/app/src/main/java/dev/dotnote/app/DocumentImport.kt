package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Imports are normalized to PDF attachments, so rendering, undo and backups share one path. */
object DocumentImport {
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    val mimeTypes = arrayOf("application/pdf", "image/*", PPTX)

    enum class Kind {
        PDF,
        IMAGE,
        POWERPOINT,
    }

    data class Result(
        val items: List<Item>,
        val message: String,
        val conversionReport: String? = null,
    )

    fun kind(name: String, mime: String?): Kind {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Some document providers report ZIP or octet-stream for Office documents.
        return when {
            extension == "ppt" ->
                error(
                    "Older .ppt files are not supported. Save as .pptx or PDF in PowerPoint first."
                )
            extension == "pptx" || mime == PPTX -> Kind.POWERPOINT
            extension == "pdf" || mime == "application/pdf" -> Kind.PDF
            extension in
                setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif") ||
                mime?.startsWith("image/") == true -> Kind.IMAGE
            else -> error("Choose a PDF, image, or PowerPoint .pptx file.")
        }
    }

    fun import(store: Store, uri: Uri, top: Float): Result {
        val resolver = store.context.contentResolver
        val name =
            if (uri.scheme == "file") uri.lastPathSegment.orEmpty()
            else {
                resolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    .orEmpty()
            }
        val kind = kind(name, resolver.getType(uri))
        if (kind == Kind.PDF) {
            val items = PdfFiles.import(store, uri, top)
            return Result(items, "Imported ${items.size} PDF pages")
        }
        val source = File.createTempFile("import-", ".source", store.context.cacheDir)
        val converted = File.createTempFile("import-", ".pdf", store.context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { copyLimited(input, it, 128L * 1024 * 1024) }
            } ?: error("Cannot open the selected file")
            val warnings =
                if (kind == Kind.IMAGE) {
                    imageToPdf(source, converted)
                    emptySet()
                } else PptxPdf.convert(source, converted)
            val items =
                PdfFiles.import(store, Uri.fromFile(converted), top).map {
                    if (kind == Kind.IMAGE) it.copy(image = true) else it
                }
            val description =
                if (kind == Kind.IMAGE) "Imported image"
                else "Converted ${items.size} PowerPoint slides to PDF"
            val detail =
                if (kind == Kind.POWERPOINT) {
                    "Offline conversion uses device fonts; complex formatting may differ." +
                        if (warnings.isEmpty()) ""
                        else
                            " ${warnings.joinToString("; ")}. For an exact copy, export PDF from PowerPoint."
                } else null
            return Result(items, description, detail)
        } finally {
            source.delete()
            converted.delete()
        }
    }

    internal fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Import exceeds the ${limit / (1024 * 1024)} MB size limit" }
            output.write(buffer, 0, count)
        }
    }

    internal fun decodeImage(source: ImageDecoder.Source, maxDimension: Int = 4096): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Bounds decoding and EXIF orientation are handled before allocating pixel storage.
            val scale = minOf(1.0, maxDimension.toDouble() / max(info.size.width, info.size.height))
            decoder.setTargetSize(
                max(1, (info.size.width * scale).roundToInt()),
                max(1, (info.size.height * scale).roundToInt()),
            )
        }

    private fun imageToPdf(source: File, output: File) {
        val bitmap = decodeImage(ImageDecoder.createSource(source))
        try {
            PdfDocument().useDocument { pdf ->
                val width = 800
                val height = max(1, (width * bitmap.height.toFloat() / bitmap.width).roundToInt())
                require(height <= 14400) { "Image is too tall. Crop it before importing." }
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                pdf.finishPage(page)
                output.outputStream().use {
                    pdf.writeTo(it)
                    it.fd.sync()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}

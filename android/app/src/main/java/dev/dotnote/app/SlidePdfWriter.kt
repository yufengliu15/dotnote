package dev.dotnote.app

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Streams lossless RGB pages to PDF. Only the current slide bitmap is retained in memory. PDF 1.4
 * image XObjects, indirect stream lengths and classic xref offsets follow ISO 32000-1. This
 * deliberately narrow writer accepts generated pixels, never arbitrary PDF input.
 */
internal class SlidePdfWriter(file: File, private val pageCount: Int) : Closeable {
    private val fileStream = file.outputStream()
    private val output = CountingOutput(BufferedOutputStream(fileStream))
    private val offsets = LongArray(3 + 4 * pageCount)
    private var pagesWritten = 0
    private var finished = false

    init {
        require(pageCount in 1..500)
        write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val kids = (0 until pageCount).joinToString(" ") { "${3 + 4 * it} 0 R" }
        obj(2, "<< /Type /Pages /Count $pageCount /Kids [$kids] >>")
    }

    fun page(bitmap: Bitmap, width: Int, height: Int) {
        check(!finished && pagesWritten < pageCount)
        require(width in 1..14400 && height in 1..14400)
        val id = 3 + pagesWritten * 4
        obj(
            id,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                "/Resources << /XObject << /Im0 ${id + 1} 0 R >> >> /Contents ${id + 3} 0 R >>",
        )
        startObject(id + 1)
        write(
            "<< /Type /XObject /Subtype /Image /Width ${bitmap.width} /Height ${bitmap.height} " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${id + 2} 0 R >>\nstream\n"
        )
        val start = output.count
        val deflater = Deflater()
        try {
            val compressed = DeflaterOutputStream(output, deflater, 65536)
            val pixels = IntArray(bitmap.width)
            val rgb = ByteArray(bitmap.width * 3)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                pixels.forEachIndexed { x, color ->
                    rgb[x * 3] = (color shr 16).toByte()
                    rgb[x * 3 + 1] = (color shr 8).toByte()
                    rgb[x * 3 + 2] = color.toByte()
                }
                compressed.write(rgb)
            }
            compressed.finish()
        } finally {
            deflater.end()
        }
        val length = output.count - start
        write("\nendstream\nendobj\n")
        obj(id + 2, length.toString())
        val content = "q\n$width 0 0 $height 0 0 cm\n/Im0 Do\nQ\n"
        obj(id + 3, "<< /Length ${content.length} >>\nstream\n${content}endstream")
        pagesWritten++
    }

    fun finish() {
        check(!finished && pagesWritten == pageCount)
        val xref = output.count
        write("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { write("${it.toString().padStart(10, '0')} 00000 n \n") }
        write("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        output.flush()
        fileStream.fd.sync()
        finished = true
    }

    private fun startObject(id: Int) {
        offsets[id] = output.count
        write("$id 0 obj\n")
    }

    private fun obj(id: Int, body: String) {
        startObject(id)
        write("$body\nendobj\n")
    }

    private fun write(value: String) = output.write(value.toByteArray(Charsets.ISO_8859_1))

    override fun close() = output.close()

    private class CountingOutput(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }
}

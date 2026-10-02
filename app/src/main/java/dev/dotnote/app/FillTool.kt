package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import kotlin.math.*

/** Bounded bucket rasterization; saved coverage is portable vector rectangles, not a bitmap. */
internal fun bucketFill(
    document: Document,
    assets: File,
    point: Pt,
    viewport: Bounds,
    color: Int,
    highlighter: Boolean,
): Item? {
    val scale = min(2f, 1024f / max(viewport.width, viewport.height))
    val width = ceil(viewport.width * scale).toInt().coerceIn(1, 1024)
    val height = ceil(viewport.height * scale).toInt().coerceIn(1, 1024)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val matrix =
            Matrix().apply {
                setValues(
                    floatArrayOf(
                        scale,
                        0f,
                        -viewport.left * scale,
                        0f,
                        scale,
                        -viewport.top * scale,
                        0f,
                        0f,
                        1f,
                    )
                )
            }
        canvas.concat(matrix)
        val visible = document.items.filter { it.bounds.intersects(viewport) }
        PdfPageSource(assets).use { source ->
            visible
                .filter { it.kind == "PDF" }
                .forEach { item ->
                    val page = source.render(item, (item.bounds.width * scale).toInt())
                    try {
                        canvas.drawBitmap(
                            page,
                            null,
                            item.bounds.rect(),
                            Paint(Paint.FILTER_BITMAP_FLAG),
                        )
                    } finally {
                        page.recycle()
                    }
                }
        }
        ObjectRenderer(vectorHighlights = false).drawScene(canvas, visible, matrix)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val x = ((point.x - viewport.left) * scale).toInt()
        val y = ((point.y - viewport.top) * scale).toInt()
        val spans = enclosedFillSpans(pixels, width, height, x, y) ?: return null
        val points =
            spans.flatMap { (start, end) ->
                listOf(
                    Pt(
                        viewport.left + (start % width) / scale,
                        viewport.top + (start / width) / scale,
                    ),
                    Pt(
                        viewport.left + (end % width + 1) / scale,
                        viewport.top + (end / width + 1) / scale,
                    ),
                )
            }
        return Item(
            kind = if (highlighter) "HIGHLIGHTER" else "PEN",
            color = color,
            points = points,
            fill = true,
        )
    } finally {
        bitmap.recycle()
    }
}

/** Four-connected flood fill rejects regions reaching the viewport edge (unbounded/open). */
internal fun enclosedFillSpans(
    pixels: IntArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): List<Pair<Int, Int>>? {
    if (x !in 0 until width || y !in 0 until height) return null
    val target = pixels[y * width + x]
    fun matches(value: Int): Boolean =
        abs((value shr 16 and 255) - (target shr 16 and 255)) <= 24 &&
            abs((value shr 8 and 255) - (target shr 8 and 255)) <= 24 &&
            abs((value and 255) - (target and 255)) <= 24
    val seen = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var head = 0
    var tail = 1
    queue[0] = y * width + x
    seen[queue[0]] = true
    while (head < tail) {
        val p = queue[head++]
        val px = p % width
        val py = p / width
        if (px == 0 || px == width - 1 || py == 0 || py == height - 1) return null
        fun visit(n: Int) {
            if (!seen[n] && matches(pixels[n])) {
                seen[n] = true
                queue[tail++] = n
            }
        }
        visit(p - 1)
        visit(p + 1)
        visit(p - width)
        visit(p + width)
    }
    return buildList {
        for (row in 0 until height) {
            var column = 0
            while (column < width) {
                val start = row * width + column
                if (!seen[start]) {
                    column++
                    continue
                }
                while (column + 1 < width && seen[row * width + column + 1]) column++
                add(start to row * width + column)
                column++
            }
        }
    }
}

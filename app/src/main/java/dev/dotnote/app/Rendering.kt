package dev.dotnote.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.*
import java.util.Base64
import kotlin.math.*

fun Transform.matrix() =
    Matrix().apply { setValues(floatArrayOf(sx, 0f, tx, 0f, sy, ty, 0f, 0f, 1f)) }

fun Camera.matrix() =
    Matrix().apply { setValues(floatArrayOf(zoom, 0f, x, 0f, zoom, y, 0f, 0f, 1f)) }

fun Bounds.rect() = RectF(left, top, right, bottom)

fun brush(color: Int, width: Float, highlight: Boolean): Brush =
    Brush.createWithColorIntArgb(
        if (highlight) StockBrushes.highlighter() else StockBrushes.pressurePen(),
        if (highlight) (color and 0x00ffffff) or 0x55000000 else color,
        width,
        .1f,
    )

fun strokeItem(stroke: Stroke, highlight: Boolean): Item {
    val bytes = ByteArrayOutputStream().also { stroke.inputs.encode(it) }.toByteArray()
    return Item(
        kind = if (highlight) "HIGHLIGHTER" else "PEN",
        color = stroke.brush.colorIntArgb or 0xff000000.toInt(),
        width = stroke.brush.size,
        points =
            (0 until stroke.inputs.size).map {
                val p = stroke.inputs[it]
                Pt(p.x, p.y)
            },
        ink = Base64.getEncoder().encodeToString(bytes),
    )
}

class ObjectRenderer {
    private var cachedHighlights: List<Item> = emptyList()
    private val cachedHighlightGroups = linkedMapOf<Int, Path>()
    private val inkRenderer = CanvasStrokeRenderer.create()
    private val highlightPaths = object : LruCache<String, Pair<Item, Path>>(400) {}
    private val strokes = object : LruCache<String, Stroke>(400) {}
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    // Composite all marker strokes once, including the live stroke, to cap opacity.
    // Pen and shape ink stays above the marker layer.
    fun drawScene(
        canvas: Canvas,
        items: List<Item>,
        worldToScreen: Matrix,
        liveHighlight: Item? = null,
    ) {
        val highlights = items.filter { it.kind == "HIGHLIGHTER" }
        if (cachedHighlights != highlights) {
            cachedHighlightGroups.clear()
            highlights.forEach { item ->
                val path = cachedHighlightGroups.remove(item.color) ?: Path()
                mergeOutline(path, highlightOutline(item))
                cachedHighlightGroups[item.color] = path
            }
            cachedHighlights = highlights
        }
        if (highlights.isNotEmpty() || liveHighlight != null) {
            val paths = LinkedHashMap(cachedHighlightGroups)
            liveHighlight?.let { item ->
                val path = paths.remove(item.color)?.let(::Path) ?: Path()
                mergeOutline(path, highlightOutline(item))
                paths[item.color] = path
            }
            val layer = canvas.saveLayerAlpha(null, 85)
            // Union removes internal edges even in PDF renderers. Cached finished
            // outlines are reused while only the live outline changes on each frame.
            paint.style = Paint.Style.FILL
            paths.forEach { (color, path) ->
                paint.color = color or 0xff000000.toInt()
                canvas.drawPath(path, paint)
            }
            canvas.restoreToCount(layer)
        }
        items
            .filter { it.kind != "HIGHLIGHTER" && it.kind != "PDF" }
            .forEach { draw(canvas, it, worldToScreen) }
    }

    private fun mergeOutline(path: Path, outline: Path) {
        // Normalize even the first outline; PDF renderers can otherwise draw
        // a self-overlapping contour's antialiased edges more than once.
        if (!path.op(outline, Path.Op.UNION)) path.addPath(outline)
    }

    private fun highlightOutline(item: Item): Path {
        highlightPaths.get(item.id)?.let { (cached, path) -> if (cached === item) return path }
        if (item.points.isEmpty()) return Path()
        paint.strokeWidth = item.width
        paint.style = Paint.Style.STROKE
        val centerline =
            Path().apply {
                moveTo(item.points.first().x, item.points.first().y)
                item.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
        return Path()
            .apply {
                if (item.points.size == 1)
                    addCircle(item.points[0].x, item.points[0].y, item.width / 2, Path.Direction.CW)
                else paint.getFillPath(centerline, this)
                transform(item.transform.matrix())
            }
            .also { highlightPaths.put(item.id, item to it) }
    }

    fun draw(canvas: Canvas, item: Item, worldToScreen: Matrix) {
        if (item.kind == "HIGHLIGHTER") {
            drawScene(canvas, listOf(item), worldToScreen)
            return
        }
        canvas.save()
        if (item.ink != null) {
            val key = "${item.id}:${item.color}:${item.width}"
            val stroke =
                strokes.get(key)
                    ?: Stroke(
                            brush(item.color, item.width, item.kind == "HIGHLIGHTER"),
                            StrokeInputBatch.decode(
                                ByteArrayInputStream(Base64.getDecoder().decode(item.ink))
                            ),
                        )
                        .also { strokes.put(key, it) }
            val local = item.transform.matrix()
            canvas.concat(local)
            val screen = Matrix().apply { setConcat(worldToScreen, local) }
            inkRenderer.draw(canvas, stroke, screen)
        } else if (item.kind != "PDF") {
            paint.color = item.color
            paint.strokeWidth = item.width * max(item.transform.sx, item.transform.sy)
            paint.style = Paint.Style.STROKE
            if (item.kind == "CIRCLE" || item.kind == "ELLIPSE")
                canvas.drawOval(item.transform.map(Bounds.of(item.points)).rect(), paint)
            else
                shapeSegments(item).forEach { (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, paint) }
        }
        canvas.restore()
    }
}

/** All access must be serialized by its caller. Never render every page up front. */
class PdfPageSource(private val assets: File) : Closeable {
    private val open = object : LinkedHashMap<String, PdfRenderer>(4, .75f, true) {}

    fun renderer(asset: String): PdfRenderer =
        open[asset]
            ?: PdfRenderer(
                    ParcelFileDescriptor.open(
                        File(assets, asset),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                )
                .also {
                    if (open.size >= 3) {
                        val first = open.entries.first()
                        first.value.close()
                        open.remove(first.key)
                    }
                    open[asset] = it
                }

    fun render(item: Item, targetWidth: Int): Bitmap {
        val pdf = renderer(requireNotNull(item.asset))
        return pdf.openPage(item.page).use { page ->
            val width = targetWidth.coerceIn(64, 2048)
            val height =
                max(1, (width * page.height.toFloat() / page.width).roundToInt()).coerceAtMost(4096)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() {
        open.values.forEach { it.close() }
        open.clear()
    }
}

object PdfFiles {
    fun import(store: Store, uri: Uri, top: Float): List<Item> {
        val name = "${newId()}.pdf"
        val file = File(store.assets, name)
        try {
            val input =
                store.context.contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
            input.use { source ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total < 512L * 1024 * 1024) { "PDF must be smaller than 512 MB" }
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                }
            }
            return PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                .use { pdf ->
                    var y = top
                    (0 until pdf.pageCount).map { index ->
                        pdf.openPage(index).use { page ->
                            val width = 800f
                            val height = width * page.height / page.width
                            Item(
                                    kind = "PDF",
                                    asset = name,
                                    page = index,
                                    points = listOf(Pt(0f, y), Pt(width, y + height)),
                                )
                                .also { y += height + 32f }
                        }
                    }
                }
                .let { items ->
                    val hashed = digest(file) + ".pdf"
                    val destination = File(store.assets, hashed)
                    if (destination.exists()) {
                        require(digest(destination) == hashed.removeSuffix(".pdf")) {
                            "Existing PDF failed its checksum"
                        }
                        file.delete()
                    } else require(file.renameTo(destination)) { "Could not finish saving PDF" }
                    items.map { it.copy(asset = hashed) }
                }
        } catch (e: Exception) {
            file.delete()
            throw IllegalArgumentException(
                "Could not import PDF. It may be encrypted or damaged. ${e.message}",
                e,
            )
        }
    }

    fun export(store: Store, doc: Document, uri: Uri, region: Bounds?) {
        val area = region ?: doc.bounds?.outset(24f) ?: Bounds(0f, 0f, 800f, 1100f)
        val pageW = 800f
        val pageH = 1100f
        val cols = if (region != null) 1 else max(1, ceil(area.width / pageW).toInt())
        val rows = if (region != null) 1 else max(1, ceil(area.height / pageH).toInt())
        require(cols.toLong() * rows <= 500) {
            "This spans over 500 pages. Export the visible area instead."
        }
        val objects = ObjectRenderer()
        PdfPageSource(store.assets).use { source ->
            PdfDocument().useDocument { pdf ->
                var number = 1
                for (row in 0 until rows) for (col in 0 until cols) {
                    val tile =
                        if (region != null) area
                        else
                            Bounds(
                                area.left + col * pageW,
                                area.top + row * pageH,
                                area.left + (col + 1) * pageW,
                                area.top + (row + 1) * pageH,
                            )
                    val page =
                        pdf.startPage(PdfDocument.PageInfo.Builder(612, 842, number++).create())
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val scale = min(564f / max(tile.width, 1f), 794f / max(tile.height, 1f))
                    val matrix =
                        Matrix().apply {
                            setValues(
                                floatArrayOf(
                                    scale,
                                    0f,
                                    24f - tile.left * scale,
                                    0f,
                                    scale,
                                    24f - tile.top * scale,
                                    0f,
                                    0f,
                                    1f,
                                )
                            )
                        }
                    canvas.save()
                    canvas.concat(matrix)
                    canvas.clipRect(tile.rect())
                    doc.items
                        .filter { it.kind == "PDF" && it.bounds.intersects(tile) }
                        .forEach { item ->
                            val bitmap = source.render(item, 1600)
                            canvas.drawBitmap(
                                bitmap,
                                null,
                                item.bounds.rect(),
                                Paint(Paint.FILTER_BITMAP_FLAG),
                            )
                            bitmap.recycle()
                        }
                    objects.drawScene(
                        canvas,
                        doc.items.filter { it.kind != "PDF" && it.bounds.intersects(tile) },
                        matrix,
                    )
                    canvas.restore()
                    pdf.finishPage(page)
                }
                val out =
                    store.context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Cannot write PDF")
                out.use { pdf.writeTo(it) }
            }
        }
    }
}

inline fun <T> PdfDocument.useDocument(block: (PdfDocument) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

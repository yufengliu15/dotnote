package dev.dotnote.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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

private val penFamily by lazy { StockBrushes.pressurePen() }
private val markerFamily by lazy { StockBrushes.highlighter() }

fun brush(color: Int, width: Float, highlight: Boolean): Brush =
    Brush.createWithColorIntArgb(
        if (highlight) markerFamily else penFamily,
        if (highlight) (color and 0x00ffffff) or 0x55000000 else color,
        width,
        .1f,
    )

/** Load native Ink and build a real pressure stroke before the editor receives input. */
object InkWarmup {
    private val started = java.util.concurrent.atomic.AtomicBoolean()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread(
                {
                    runCatching {
                        val inputs =
                            androidx.ink.strokes.MutableStrokeInputBatch().apply {
                                repeat(3) { step ->
                                    add(
                                        androidx.ink.strokes.StrokeInput().apply {
                                            update(
                                                4f + step * 4f,
                                                8f,
                                                step * 8L,
                                                androidx.ink.brush.InputToolType.STYLUS,
                                                pressure = .8f,
                                            )
                                        }
                                    )
                                }
                            }
                        val stroke =
                            androidx.ink.strokes
                                .InProgressStroke()
                                .apply {
                                    start(brush(Color.BLACK, 3f, false))
                                    enqueueInputs(
                                        inputs,
                                        androidx.ink.strokes.MutableStrokeInputBatch(),
                                    )
                                    finishInput()
                                    updateShape()
                                }
                                .toImmutable()
                        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                        try {
                            CanvasStrokeRenderer.create().draw(Canvas(bitmap), stroke, Matrix())
                            brush(Color.YELLOW, 15f, true)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                },
                "Dotnote-InkWarmup",
            )
            .start()
    }
}

fun strokeItem(stroke: Stroke, highlight: Boolean): Item =
    strokeItem(stroke.inputs, stroke.brush, highlight)

// Saving input data does not require constructing/tessellating a second native stroke.
fun strokeItem(inputs: StrokeInputBatch, brush: Brush, highlight: Boolean): Item {
    val bytes = ByteArrayOutputStream().also { inputs.encode(it) }.toByteArray()
    return Item(
        kind = if (highlight) "HIGHLIGHTER" else "PEN",
        color = brush.colorIntArgb or 0xff000000.toInt(),
        width = brush.size,
        points =
            (0 until inputs.size).map {
                val p = inputs[it]
                Pt(p.x, p.y)
            },
        ink = Base64.getEncoder().encodeToString(bytes),
    )
}

/** Mutable live geometry: append inputs without copying/rebounding the whole stroke. */
class LiveHighlight(val color: Int, val width: Float) {
    private val paths = mutableListOf<Path>()
    private var current = Path()
    private var count = 0
    private var first: Pt? = null

    fun append(point: Pt) {
        if (first == null) {
            first = point
            current.moveTo(point.x, point.y)
            paths.add(current)
        } else {
            current.lineTo(point.x, point.y)
            if (++count == 128) {
                // Finished chunks stay unchanged, avoiding ever-growing path tessellation.
                current = Path().apply { moveTo(point.x, point.y) }
                paths.add(current)
                count = 0
            }
        }
    }

    fun draw(canvas: Canvas, paint: Paint) {
        paint.color = color or 0xff000000.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paths.forEach { canvas.drawPath(it, paint) }
        // A down event alone must produce visible ink too.
        first?.let {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(it.x, it.y, width / 2, paint)
        }
    }
}

/**
 * Thread-safe LRU of native Ink strokes shared by the UI and tile threads. Ink strokes are
 * immutable, so one tessellation serves every renderer.
 */
class StrokeCache(private val limit: Int = 3000) {
    private val map = LinkedHashMap<String, Pair<Item, Stroke>>(256, .75f, true)

    @Synchronized
    fun get(item: Item): Stroke? =
        map[item.id]
            ?.takeIf { (previous, _) ->
                previous === item ||
                    (previous.color == item.color &&
                        previous.width == item.width &&
                        previous.ink == item.ink)
            }
            ?.second

    @Synchronized
    fun put(item: Item, stroke: Stroke) {
        map[item.id] = item to stroke
        if (map.size > limit) {
            val iterator = map.entries.iterator()
            while (map.size > limit && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }
}

class ObjectRenderer(
    private val vectorHighlights: Boolean = true,
    private val sharedStrokes: StrokeCache? = null,
) {
    private var cachedScene: List<Item>? = null
    private var foreground: List<Item> = emptyList()
    private var cachedHighlights: List<Item> = emptyList()
    private val cachedHighlightGroups = linkedMapOf<Int, Path>()
    private val interactiveGroups = linkedMapOf<Int, MutableList<Path>>()
    private val inkRenderer by lazy { CanvasStrokeRenderer.create() }
    private val highlightPaths = VisibleResourceCache<Pair<Item, Path>>()
    private val strokes = VisibleResourceCache<Pair<Item, Stroke>>()
    private val shapeLines = VisibleResourceCache<Pair<Item, FloatArray>>()
    private val textLayouts = VisibleResourceCache<Pair<Item, android.text.StaticLayout>>()
    internal var strokeBuildCount = 0
        private set

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
        liveHighlight: LiveHighlight? = null,
        hiddenIds: Collection<String> = emptySet(),
    ) {
        if (cachedScene !== items) {
            val ids = items.mapTo(HashSet()) { it.id }
            strokes.retainVisible(ids)
            highlightPaths.retainVisible(ids)
            shapeLines.retainVisible(ids)
            textLayouts.retainVisible(ids)
            foreground = items.filter { it.kind != "HIGHLIGHTER" && it.kind != "PDF" }
        }
        val highlights =
            if (cachedScene === items) cachedHighlights
            else items.filter { it.kind == "HIGHLIGHTER" }
        if (cachedHighlights != highlights) {
            cachedHighlightGroups.clear()
            interactiveGroups.clear()
            highlights.forEach { item ->
                if (vectorHighlights) {
                    val path = cachedHighlightGroups.remove(item.color) ?: Path()
                    mergeOutline(path, highlightOutline(item))
                    cachedHighlightGroups[item.color] = path
                } else {
                    val paths = interactiveGroups.remove(item.color) ?: mutableListOf()
                    paths.add(highlightOutline(item))
                    interactiveGroups[item.color] = paths
                }
            }
            cachedHighlights = highlights
        }
        cachedScene = items
        if (highlights.isNotEmpty() || liveHighlight != null) {
            val layer = canvas.saveLayerAlpha(null, 85)
            paint.style = Paint.Style.FILL
            if (vectorHighlights) {
                // PDF output needs normalized outlines to avoid antialiasing seams.
                cachedHighlightGroups.forEach { (color, path) ->
                    paint.color = color or 0xff000000.toInt()
                    canvas.drawPath(path, paint)
                }
            } else {
                // Opaque strokes share ONE translucent layer. No boolean path operations
                // are needed in the interactive renderer, including during pen-up.
                fun drawGroup(color: Int, paths: List<Path>) {
                    paint.color = color or 0xff000000.toInt()
                    paths.forEach { canvas.drawPath(it, paint) }
                }
                interactiveGroups.forEach { (color, paths) ->
                    if (color != liveHighlight?.color) drawGroup(color, paths)
                }
                // Keep the existing color-group ordering in sync with vector exports.
                liveHighlight?.let { live ->
                    interactiveGroups[live.color]?.let { drawGroup(live.color, it) }
                }
            }
            liveHighlight?.draw(canvas, paint)
            canvas.restoreToCount(layer)
        }
        foreground.forEach { if (it.id !in hiddenIds) draw(canvas, it, worldToScreen) }
    }

    private fun mergeOutline(path: Path, outline: Path) {
        // Normalize even the first outline; PDF renderers can otherwise draw
        // a self-overlapping contour's antialiased edges more than once.
        if (!path.op(outline, Path.Op.UNION)) path.addPath(outline)
    }

    private fun highlightOutline(item: Item): Path {
        highlightPaths[item.id]?.let { (cached, path) -> if (cached === item) return path }
        if (item.points.isEmpty()) return Path()
        paint.strokeWidth = item.width
        paint.style = Paint.Style.STROKE
        val centerline =
            Path().apply {
                moveTo(item.points.first().x, item.points.first().y)
                for (index in 1 until item.points.size) {
                    val point = item.points[index]
                    lineTo(point.x, point.y)
                }
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

    /**
     * Opaque marker fills for one tile or region, grouped by color in [order] (the scene-wide
     * last-use order), exactly as the interactive layer groups them. The caller composites the
     * result once at marker opacity.
     */
    fun drawMarkerFills(canvas: Canvas, items: List<Item>, order: IntArray) {
        for (color in order) {
            for (item in items) if (item.color == color && item.kind == "HIGHLIGHTER") {
                val outline = highlightOutline(item)
                paint.style = Paint.Style.FILL
                paint.color = color or 0xff000000.toInt()
                canvas.drawPath(outline, paint)
            }
        }
    }

    fun draw(canvas: Canvas, item: Item, worldToScreen: Matrix) {
        if (item.kind == "HIGHLIGHTER") {
            drawScene(canvas, listOf(item), worldToScreen)
            return
        }
        canvas.save()
        if (item.ink != null) {
            val shared = sharedStrokes
            val cached = if (shared != null) shared.get(item) else
                strokes[item.id]
                    ?.takeIf { (previous, _) ->
                        previous.color == item.color &&
                            previous.width == item.width &&
                            previous.ink == item.ink
                    }
                    ?.second
            val stroke =
                cached
                    ?: Stroke(
                            brush(item.color, item.width, item.kind == "HIGHLIGHTER"),
                            StrokeInputBatch.decode(
                                ByteArrayInputStream(Base64.getDecoder().decode(item.ink))
                            ),
                        )
                        .also {
                            strokeBuildCount++
                            if (shared != null) shared.put(item, it)
                            else strokes.put(item.id, item to it)
                        }
            val local = item.transform.matrix()
            canvas.concat(local)
            val screen = Matrix().apply { setConcat(worldToScreen, local) }
            inkRenderer.draw(canvas, stroke, screen)
        } else if (item.kind == "TEXT") {
            val layout =
                textLayouts[item.id]?.takeIf { it.first === item }?.second
                    ?: textLayout(
                            requireNotNull(item.text),
                            item.fontSize,
                            item.color,
                            Bounds.of(item.points).width.roundToInt().coerceIn(1, 4096),
                        )
                        .also { textLayouts.put(item.id, item to it) }
            canvas.concat(item.transform.matrix())
            val anchor = item.points.first()
            canvas.translate(anchor.x, anchor.y)
            layout.draw(canvas)
        } else if (item.kind != "PDF") {
            paint.color = item.color
            paint.strokeWidth = item.width * max(item.transform.sx, item.transform.sy)
            paint.style = Paint.Style.STROKE
            if (item.kind == "CIRCLE" || item.kind == "ELLIPSE")
                canvas.drawOval(item.transform.map(Bounds.of(item.points)).rect(), paint)
            else {
                val cached = shapeLines[item.id]
                val lines =
                    cached?.takeIf { it.first === item }?.second
                        ?: shapeSegments(item).let { segments ->
                            FloatArray(segments.size * 4)
                                .apply {
                                    segments.forEachIndexed { index, (a, b) ->
                                        this[index * 4] = a.x
                                        this[index * 4 + 1] = a.y
                                        this[index * 4 + 2] = b.x
                                        this[index * 4 + 3] = b.y
                                    }
                                }
                                .also { shapeLines.put(item.id, item to it) }
                        }
                canvas.drawLines(lines, paint)
            }
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
        val index = SceneIndex()
        val hits = IntList()
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
                    // Only items on this page tile are visited.
                    index.query(doc.items, tile, hits)
                    val onTile = List(hits.size) { doc.items[hits[it]] }
                    onTile
                        .filter { it.kind == "PDF" }
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
                        onTile.filter { it.kind != "PDF" },
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

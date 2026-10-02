package dev.dotnote.app

import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.*
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import java.util.concurrent.Executors
import kotlin.math.*

private const val PAPER = 0xfffafaf6.toInt()
// Objects a not-yet-rendered tile area may draw directly while its tile is being rendered.
private const val FALLBACK_LIMIT = 300

// Constructed exclusively by Compose AndroidView with its editor state.
@android.annotation.SuppressLint("ViewConstructor")
class NotebookView(context: Context, val state: AppState) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val ink = InProgressStrokesView(context)
    private var preparedPen = brush(state.color, state.strokeWidth, false)
    private var preparedMarker = brush(state.color, state.strokeWidth * 5, true)
    private var warmedSurface = false
    private val strokeCache = StrokeCache.shared
    // Draws only what is not in tiles: dragged selections, gaps and live previews.
    private val renderer = ObjectRenderer(vectorHighlights = false, sharedStrokes = strokeCache)
    private val sceneIndex = SceneIndex()
    private val hits = IntList()
    private val tiles =
        SceneTiles(
            post = { post(it) },
            postDelayed = { r, delay -> postDelayed(r, delay) },
            changed = {
                highlights.invalidate()
                foreground.invalidate()
            },
            strokes = strokeCache,
        )
    private var dotBuffer = FloatArray(0)
    private val livePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private var pagesSource: List<Item>? = null
    private var pageItems: List<Item> = emptyList()
    private var selectionSource: Pair<List<Item>, Set<String>>? = null
    private var selectionBox: Bounds? = null
    private val pageSource = PdfPageSource(state.store.assets)
    private val worker = Executors.newSingleThreadExecutor()
    private val cache =
        object : LruCache<String, Bitmap>(48 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
        }
    private val pending = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private var disposed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // The live renderer may finish on a later frame. Persist actual inputs at pen-up,
    // and hide that durable stroke until the live renderer hands it over.
    private val handoffs = mutableMapOf<InProgressStrokeId, String>()
    private var highlightPreview: LiveHighlight? = null
    private var recordedInputs = MutableStrokeInputBatch()
    private var recordedBrush: Brush? = null
    private var inputStartTime = 0L
    private var lastInputTime = -1L
    private var inputTool = InputToolType.STYLUS
    private var activeStroke: InProgressStrokeId? = null
    private var pointer = -1
    private var activeTool = Tool.PEN
    private var start = Pt(0f, 0f)
    private var last = Pt(0f, 0f)
    private var gestureBefore: List<Item>? = null
    private var shape: Item? = null
    private var lasso = mutableListOf<Pt>()
    private var moving = false
    private var resizing = false
    private var sizingMode = SelectionSizing.RESIZE
    private var sizingTextOnly = false
    private var originalBounds: Bounds? = null
    private var navFocus: Pt? = null
    private var navSpan = 0f
    private var navigation = false
    private val scrollConfig = ViewConfiguration.get(context)
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var flingPointer = -1
    private var panStart = Pt(0f, 0f)
    private var panDragged = false
    private var flingX = 0
    private var flingY = 0
    private var suppressFingers = false
    private var textDragged = false
    private val predictor = MotionEventPredictor.newInstance(this)
    private val content =
        object : View(context) {
            override fun onDraw(canvas: Canvas) {
                drawContent(canvas, background = true)
            }
        }

    private val highlights =
        object : View(context) {
            override fun onDraw(canvas: Canvas) = drawMarkers(canvas)
        }
    private val foreground =
        object : View(context) {
            override fun onDraw(canvas: Canvas) = drawContent(canvas, background = false)
        }

    init {
        setWillNotDraw(false)
        isFocusable = true
        contentDescription =
            "Note canvas. Write with the stylus, drag or flick to pan, and pinch to zoom."
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(highlights, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(foreground, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ink, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ink.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    handOff(strokes)
                }
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Allocate the native renderer and drawing surface before the first pen event.
        ink.eagerInit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || warmedSurface) return
        warmedSurface = true
        // eagerInit creates the surface, but does not exercise brush/mesh rendering.
        // A stroke outside the view warms that path; it never enters the document/history.
        val transform = Matrix().apply { setTranslate(-10000f, -10000f) }
        val input = StrokeInput().apply { update(0f, 0f, 0L, InputToolType.STYLUS, pressure = .8f) }
        val id = ink.startStroke(input, currentPen(), transform)
        ink.finishStroke(
            StrokeInput().apply { update(10f, 0f, 8L, InputToolType.STYLUS, pressure = .8f) },
            id,
        )
    }

    private fun currentPen(): Brush {
        if (preparedPen.colorIntArgb != state.color || preparedPen.size != state.strokeWidth)
            preparedPen = brush(state.color, state.strokeWidth, false)
        return preparedPen
    }

    private fun currentMarker(): Brush {
        if (
            preparedMarker.colorIntArgb != ((state.color and 0x00ffffff) or 0x55000000) ||
                preparedMarker.size != state.strokeWidth * 5
        )
            preparedMarker = brush(state.color, state.strokeWidth * 5, true)
        return preparedMarker
    }

    fun refresh() {
        currentPen()
        currentMarker()
        content.invalidate()
        highlights.invalidate()
        foreground.invalidate()
    }

    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        strokes.keys.forEach { handoffs.remove(it) }
        if (strokes.isEmpty()) return
        foreground.invalidate()
        ink.removeFinishedStrokes(strokes.keys)
    }

    fun settle() {
        stopNavigationMotion()
        handOff(ink.getFinishedStrokes())
    }

    private fun clearVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
        flingPointer = -1
        panDragged = false
    }

    private fun stopNavigationMotion() {
        // forceFinished preserves the displayed position; abortAnimation jumps to the end.
        scroller.forceFinished(true)
        clearVelocity()
    }

    override fun onDetachedFromWindow() {
        stopNavigationMotion()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopNavigationMotion()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (disposed || !scroller.computeScrollOffset()) return
        val c = state.document.camera
        // Scroller coordinates are relative screen pixels, independent of zoom and distance
        // from the canvas origin. The camera stores screen dp, not world coordinates.
        state.camera(
            c.copy(
                x = c.x + (scroller.currX - flingX) / density,
                y = c.y + (scroller.currY - flingY) / density,
            )
        )
        flingX = scroller.currX
        flingY = scroller.currY
        refresh()
        if (!scroller.isFinished) postInvalidateOnAnimation()
    }

    private fun trackPan(e: MotionEvent) {
        // Pinches and pointer handoffs must never turn into a fling when fingers lift.
        if (
            e.pointerCount != 1 ||
                e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER ||
                e.flags and MotionEvent.FLAG_CANCELED != 0
        ) {
            clearVelocity()
            return
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            clearVelocity()
            velocityTracker = VelocityTracker.obtain()
            flingPointer = e.getPointerId(0)
            panStart = Pt(e.x, e.y)
        }
        val tracker = velocityTracker ?: return
        tracker.addMovement(e)
        if (
            e.actionMasked == MotionEvent.ACTION_MOVE &&
                hypot(e.x - panStart.x, e.y - panStart.y) > scrollConfig.scaledTouchSlop
        ) {
            panDragged = true
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (panDragged) {
                tracker.computeCurrentVelocity(
                    1000,
                    scrollConfig.scaledMaximumFlingVelocity.toFloat(),
                )
                val vx = tracker.getXVelocity(flingPointer)
                val vy = tracker.getYVelocity(flingPointer)
                if (max(abs(vx), abs(vy)) >= scrollConfig.scaledMinimumFlingVelocity) {
                    flingX = 0
                    flingY = 0
                    scroller.fling(
                        0,
                        0,
                        vx.toInt(),
                        vy.toInt(),
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                    )
                    postInvalidateOnAnimation()
                }
            }
            val textTap =
                !panDragged &&
                    (state.tool == Tool.TEXT || state.tool == Tool.FILL) &&
                    hypot(e.x - panStart.x, e.y - panStart.y) <= scrollConfig.scaledTouchSlop
            clearVelocity()
            if (textTap) {
                if (state.tool == Tool.FILL) requestFill(world(e, 0))
                else state.requestText(world(e, 0))
            }
        }
    }

    private fun recordInputs(event: MotionEvent, index: Int) {
        fun append(x: Float, y: Float, time: Long, pressure: Float) {
            val elapsed = (time - inputStartTime).coerceAtLeast(0)
            if (elapsed <= lastInputTime) return
            val point = state.document.camera.world(Pt(x / density, y / density))
            highlightPreview?.append(point)
            recordedInputs.add(
                StrokeInput().apply {
                    update(
                        point.x,
                        point.y,
                        elapsed,
                        inputTool,
                        pressure = pressure.coerceIn(0f, 1f),
                    )
                }
            )
            lastInputTime = elapsed
        }
        for (h in 0 until event.historySize) append(
            event.getHistoricalX(index, h),
            event.getHistoricalY(index, h),
            event.getHistoricalEventTime(h),
            event.getHistoricalPressure(index, h),
        )
        append(event.getX(index), event.getY(index), event.eventTime, event.getPressure(index))
    }

    fun release() {
        if (disposed) return
        settle()
        disposed = true
        tiles.release()
        worker.execute { pageSource.close() }
        worker.shutdown()
        cache.evictAll()
    }

    fun viewport(): Bounds {
        val c = state.document.camera
        return Bounds.of(
            listOf(c.world(Pt(0f, 0f)), c.world(Pt(width / density, height / density)))
        )
    }

    fun fit() {
        settle()
        state.camera(fittedCamera(state.document.bounds, width / density, height / density))
        refresh()
    }

    fun page(item: Item) {
        settle()
        val b = item.bounds
        val w = width / density
        val h = height / density
        val z = min((w - 40) / b.width, (h - 40) / b.height).coerceIn(.08f, 4f)
        state.camera(Camera((w - b.width * z) / 2 - b.left * z, 20f - b.top * z, z))
        refresh()
    }

    private fun screenMatrix(): Matrix {
        val c = state.document.camera
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    c.zoom * density,
                    0f,
                    c.x * density,
                    0f,
                    c.zoom * density,
                    c.y * density,
                    0f,
                    0f,
                    1f,
                )
            )
        }
    }

    private fun world(e: MotionEvent, index: Int) =
        state.document.camera.world(Pt(e.getX(index) / density, e.getY(index) / density))

    private fun selectionBounds(): Bounds? {
        val selection = state.selection
        if (selection.isEmpty()) return null
        val items = state.document.items
        selectionSource?.let { (i, s) -> if (i === items && s === selection) return selectionBox }
        var box: Bounds? = null
        for (item in items) if (item.id in selection) box = box?.union(item.bounds) ?: item.bounds
        selectionSource = items to selection
        selectionBox = box
        return box
    }

    /** Items drawn live instead of from tiles while a selection is dragged or resized. */
    private fun excluded(): Set<String> =
        if ((moving || resizing) && pointer >= 0) state.selection else emptySet()

    /** Selection being moved, rasterized once at the start of the drag and then translated. */
    private class Sprite(
        val key: Any,
        val ink: Bitmap?,
        val markers: Bitmap?,
        val left: Float,
        val top: Float,
        val scale: Float,
    )

    private var sprite: Sprite? = null

    private fun dragSprite(): Sprite? {
        if (!moving || pointer < 0 || state.selection.isEmpty()) {
            sprite = null
            return null
        }
        val before = gestureBefore ?: return null
        val scale = state.document.camera.zoom * density
        val key = Triple(before, state.selection, scale)
        sprite?.let { if (it.key == key) return it }
        val selected = before.filter { it.id in state.selection && it.kind != "PDF" }
        if (selected.isEmpty()) return null
        var box = selected[0].bounds
        selected.forEach { box = box.union(it.bounds) }
        box = box.outset(2f / scale)
        val w = kotlin.math.ceil(box.width * scale).toInt()
        val h = kotlin.math.ceil(box.height * scale).toInt()
        // Huge selections are drawn live instead of holding a large bitmap.
        if (w <= 0 || h <= 0 || w.toLong() * h > width.toLong() * height * 2) return null
        fun render(markers: Boolean): Bitmap? {
            val list =
                selected.filter { (it.kind == "HIGHLIGHTER") == markers }.ifEmpty { return null }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            c.scale(scale, scale)
            c.translate(-box.left, -box.top)
            if (markers) {
                renderer.drawMarkerFills(c, list, list.map { it.color }.distinct().toIntArray())
                // Pre-faded, so it can be drawn without the marker layer.
                SceneTiles.fade(c)
            } else {
                val m = Matrix(c.matrix)
                list.forEach { renderer.draw(c, it, m) }
            }
            return bitmap
        }
        return Sprite(key, render(false), render(true), box.left, box.top, scale).also { sprite = it }
    }

    /** Draws the dragged selection's sprite at the current drag offset; false if unavailable. */
    private fun drawSprite(canvas: Canvas, markers: Boolean): Boolean {
        val s = dragSprite() ?: return false
        val bitmap = (if (markers) s.markers else s.ink) ?: return true
        val c = state.document.camera
        val x = (s.left + last.x - start.x) * s.scale + c.x * density
        val y = (s.top + last.y - start.y) * s.scale + c.y * density
        canvas.drawBitmap(bitmap, x, y, spritePaint)
        return true
    }

    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun syncTiles() {
        tiles.update(state.document.items, excluded(), handoffs.values)
    }

    private fun pages(items: List<Item>): List<Item> {
        if (pagesSource !== items) {
            pagesSource = items
            pageItems = items.filter { it.kind == "PDF" }
        }
        return pageItems
    }

    /** Draws scene items intersecting [area] directly; used for tiles that are not ready yet. */
    private fun drawVector(canvas: Canvas, area: Bounds, markers: Boolean, complete: Boolean) {
        val items = state.document.items
        val skip = excluded()
        val hidden = handoffs.values
        sceneIndex.query(items, area, hits)
        // The area is already being rendered into tiles. Drawing hundreds of strokes directly
        // would stall this frame for longer than the tiles take to arrive (for example the first
        // frames of a dense note just opened), so such areas wait for their tiles.
        if (!complete && hits.size > FALLBACK_LIMIT) return
        val matrix = screenMatrix()
        canvas.save()
        canvas.concat(matrix)
        if (markers) {
            val list = ArrayList<Item>()
            for (k in 0 until hits.size) {
                val item = items[hits[k]]
                if (item.kind == "HIGHLIGHTER" && item.id !in skip && item.id !in hidden)
                    list.add(item)
            }
            renderer.drawMarkerFills(canvas, list, tiles.colorOrder)
        } else {
            for (k in 0 until hits.size) {
                val item = items[hits[k]]
                if (
                    item.kind != "HIGHLIGHTER" &&
                        item.kind != "PDF" &&
                        item.id !in skip &&
                        item.id !in hidden &&
                        (complete || item.ink == null || strokeCache.get(item) != null)
                )
                    renderer.draw(canvas, item, matrix)
            }
        }
        canvas.restore()
    }

    private fun drawMarkers(canvas: Canvas) {
        syncTiles()
        val live = highlightPreview
        val skip = excluded()
        val dragged =
            if (skip.isEmpty()) emptyList()
            else state.document.items.filter { it.kind == "HIGHLIGHTER" && it.id in skip }
        if (!tiles.hasMarkers && live == null && dragged.isEmpty()) return
        val camera = state.document.camera
        // Finished tiles (and a moved selection's pre-faded sprite) draw without a layer.
        val spriteReady = dragged.isEmpty() || (moving && dragSprite() != null)
        if (live == null && spriteReady && tiles.drawTranslucent(canvas, camera, density, width, height)) {
            if (dragged.isNotEmpty()) drawSprite(canvas, markers = true)
            return
        }
        // Opaque fills share ONE translucent layer, so overlaps never darken.
        val layer = canvas.saveLayerAlpha(null, 85)
        if (tiles.hasMarkers)
            tiles.draw(canvas, state.document.camera, density, width, height, true) {
                c,
                area,
                complete ->
                drawVector(c, area, markers = true, complete = complete)
            }
        if (dragged.isNotEmpty() || live != null) {
            canvas.save()
            canvas.concat(screenMatrix())
            if (dragged.isNotEmpty())
                renderer.drawMarkerFills(
                    canvas,
                    dragged,
                    dragged.map { it.color }.distinct().toIntArray(),
                )
            live?.draw(canvas, livePaint)
            canvas.restore()
        }
        canvas.restoreToCount(layer)
    }

    private fun drawContent(canvas: Canvas, background: Boolean) {
        if (!background) {
            drawInk(canvas)
            return
        }
        val doc = state.document
        val visible = viewport()
        val matrix = screenMatrix()
        val z = doc.camera.zoom
        var spacing = 24f
        while (spacing * z < 12) spacing *= 2
        val period = spacing * z * density
        // GPU canvases repeat a one-period tile, which costs nothing to keep current while
        // zooming. Software canvases (tests, previews) blit one cached full-view image instead,
        // since per-pixel shading is their expensive part.
        val cached =
            doc.dots &&
                (if (canvas.isHardwareAccelerated) drawPaperTile(canvas, period)
                else drawPaper(canvas, period))
        if (!cached) canvas.drawColor(PAPER)
        canvas.save()
        canvas.concat(matrix)
        if (doc.dots && !cached) {
            // One batched draw call instead of one call per dot.
            val x0 = floor(visible.left / spacing) * spacing
            val y0 = floor(visible.top / spacing) * spacing
            val columns = max(0, ceil((visible.right - x0) / spacing).toInt())
            val rows = max(0, ceil((visible.bottom - y0) / spacing).toInt())
            val count = columns.toLong() * rows
            if (count in 1..40_000) {
                val needed = (count * 2).toInt()
                if (dotBuffer.size < needed) dotBuffer = FloatArray(needed)
                var n = 0
                for (c in 0 until columns) {
                    val x = x0 + c * spacing
                    for (r in 0 until rows) {
                        dotBuffer[n++] = x
                        dotBuffer[n++] = y0 + r * spacing
                    }
                }
                paint.color = 0xffcdd3ca.toInt()
                paint.style = Paint.Style.FILL
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 2f / z
                canvas.drawPoints(dotBuffer, 0, n, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
        }
        pages(doc.items).forEach { item ->
            val b = item.bounds
            if (!b.intersects(visible)) return@forEach
            paint.color = Color.WHITE
            canvas.drawRect(b.rect(), paint)
            val desired = (b.width * z * density).toInt().coerceIn(128, 2048)
            val target =
                when {
                    desired <= 512 -> 512
                    desired <= 1024 -> 1024
                    else -> 2048
                }
            val key = "${item.asset}:${item.page}:$target"
            val bitmap = cache.get(key)
            if (bitmap != null) {
                paint.isFilterBitmap = true
                canvas.drawBitmap(bitmap, null, b.rect(), paint)
            } else {
                paint.color = 0xff72796f.toInt()
                paint.textSize = 16f
                canvas.drawText(
                    if (key in failed) "Page unavailable" else "Loading page ${item.page+1}…",
                    b.left + 24,
                    b.top + 36,
                    paint,
                )
                if (!disposed && key !in failed && pending.add(key))
                    worker.execute {
                        val result = runCatching { pageSource.render(item, target) }
                        post {
                            pending.remove(key)
                            if (!disposed) {
                                result.fold(
                                    { cache.put(key, it) },
                                    {
                                        failed.add(key)
                                        state.message =
                                            "A PDF page could not be read: ${it.message}"
                                    },
                                )
                                content.invalidate()
                            }
                        }
                    }
            }
            paint.style = Paint.Style.STROKE
            paint.color = 0xffdadfd4.toInt()
            paint.strokeWidth = 1f / z
            canvas.drawRect(b.rect(), paint)
            paint.style = Paint.Style.FILL
        }
        canvas.restore()
    }

    private var paperBitmap: Bitmap? = null
    private var paperPeriod = 0f
    private var periodSeen = 0f
    private var periodChangedAt = 0L

    /**
     * Paper and dots repeat every [period] pixels, so one cached image slightly larger than the
     * view is blitted at the camera's phase (rounded to whole pixels; every dot shifts together by
     * under half a pixel). It is rebuilt only when the dot period changes and stays unchanged for
     * a moment, so a pinch keeps using batched point drawing.
     */
    private fun drawPaper(canvas: Canvas, period: Float): Boolean {
        if (width <= 0 || height <= 0 || period < 4f) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (period != periodSeen) {
            periodSeen = period
            periodChangedAt = now
        }
        var bitmap = paperBitmap
        if (bitmap == null || paperPeriod != period || bitmap.width < width + period + 2 ||
            bitmap.height < height + period + 2) {
            if (now - periodChangedAt < 150) {
                postInvalidateDelayed(160)
                return drawPaperTile(canvas, period)
            }
            val w = width + ceil(period).toInt() + 2
            val h = height + ceil(period).toInt() + 2
            bitmap =
                bitmap?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { paperBitmap = it }
            bitmap.setHasAlpha(false)
            val c = Canvas(bitmap)
            c.drawColor(PAPER)
            paint.color = 0xffcdd3ca.toInt()
            paint.style = Paint.Style.FILL
            var x = 0f
            while (x < w + period) {
                var y = 0f
                while (y < h + period) {
                    c.drawCircle(x, y, density, paint)
                    y += period
                }
                x += period
            }
            paperPeriod = period
            bitmap.prepareToDraw()
        }
        val c = state.document.camera
        fun phase(offset: Float): Int {
            val m = ((offset % period) + period) % period
            return (m - period).roundToInt()
        }
        canvas.drawBitmap(bitmap, phase(c.x * density).toFloat(), phase(c.y * density).toFloat(), null)
        return true
    }

    private var dotTile: Bitmap? = null
    private var dotTileSize = 0
    private val dotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dotMatrix = Matrix()

    /**
     * While the dot period is changing (a pinch), fills the view with a repeating one-period tile
     * instead of rasterizing every dot of the view each frame.
     */
    private fun drawPaperTile(canvas: Canvas, period: Float): Boolean {
        val size = period.roundToInt()
        if (size < 4 || size > 1024) return false
        var tile = dotTile
        if (tile == null || dotTileSize != size) {
            tile?.recycle()
            tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(tile)
            c.drawColor(PAPER)
            paint.color = 0xffcdd3ca.toInt()
            paint.style = Paint.Style.FILL
            // A dot at the shared corner of four repeated tiles.
            for (x in 0..1) for (y in 0..1) c.drawCircle(x * size.toFloat(), y * size.toFloat(), density, paint)
            dotTile = tile
            dotTileSize = size
            dotPaint.shader =
                android.graphics.BitmapShader(
                    tile,
                    android.graphics.Shader.TileMode.REPEAT,
                    android.graphics.Shader.TileMode.REPEAT,
                )
        }
        val c = state.document.camera
        dotMatrix.setScale(period / size, period / size)
        dotMatrix.postTranslate(c.x * density, c.y * density)
        dotPaint.shader.setLocalMatrix(dotMatrix)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dotPaint)
        return true
    }

    private fun drawInk(canvas: Canvas) {
        val started = System.nanoTime()
        try {
            drawInkLayer(canvas)
        } finally {
            val ms = (System.nanoTime() - started) / 1e6
            if (BuildConfig.DEBUG && ms > 50) android.util.Log.i("DotnoteFrame", "ink layer $ms ms")
        }
    }

    private fun drawInkLayer(canvas: Canvas) {
        syncTiles()
        val doc = state.document
        tiles.draw(canvas, doc.camera, density, width, height, false) { c, area, complete ->
            drawVector(c, area, markers = false, complete = complete)
        }
        val matrix = screenMatrix()
        val z = doc.camera.zoom
        canvas.save()
        canvas.concat(matrix)
        val skip = excluded()
        if (skip.isNotEmpty()) {
            // A moved selection blits its sprite (screen space); resizing draws vectors.
            canvas.restore()
            val sprited = moving && drawSprite(canvas, markers = false)
            canvas.save()
            canvas.concat(matrix)
            if (!sprited)
                for (item in doc.items)
                    if (item.id in skip && item.kind != "HIGHLIGHTER" && item.kind != "PDF")
                        renderer.draw(canvas, item, matrix)
        }
        shape?.let { renderer.draw(canvas, it, matrix) }
        if (lasso.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.color = 0xff255a4e.toInt()
            paint.strokeWidth = 1.5f / z
            paint.pathEffect = DashPathEffect(floatArrayOf(6f / z, 4f / z), 0f)
            val path =
                Path().apply {
                    moveTo(lasso[0].x, lasso[0].y)
                    for (i in 1 until lasso.size) lineTo(lasso[i].x, lasso[i].y)
                    close()
                }
            canvas.drawPath(path, paint)
            paint.pathEffect = null
        }
        selectionBounds()?.let { b ->
            paint.style = Paint.Style.STROKE
            paint.color = 0xff255a4e.toInt()
            paint.strokeWidth = 1.5f / z
            canvas.drawRect(b.outset(6f / z).rect(), paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(b.right, b.bottom, 7f / z, paint)
        }
        canvas.restore()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disposed) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        val action = event.actionMasked
        val index = event.actionIndex
        if (action == MotionEvent.ACTION_CANCEL) {
            stopNavigationMotion()
            cancelGesture(event)
            navigation = false
            return true
        }
        if (action == MotionEvent.ACTION_DOWN) {
            stopNavigationMotion()
            suppressFingers = false
            navigation = false
            navFocus = null
            navSpan = 0f
        }
        val stylus =
            event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        if (
            (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) &&
                stylus &&
                pointer < 0
        ) {
            navigation = false
            suppressFingers = true
            begin(event, index)
            return true
        }
        // Once a stylus owns the gesture, palm pointers neither draw nor move the camera.
        if (pointer >= 0 && suppressFingers) {
            handleOwned(event)
            return true
        }
        if (suppressFingers) return true
        if (event.pointerCount >= 2) {
            if (pointer >= 0) cancelGesture(event)
            navigation = true
            navigate(event)
            return true
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (state.fingerDrawing && state.tool != Tool.TEXT && state.tool != Tool.FILL)
                begin(event, index)
            else {
                navigation = true
                navigate(event)
            }
        } else if (navigation) navigate(event) else handleOwned(event)
        return true
    }

    private fun requestFill(point: Pt) {
        val camera = state.document.camera
        val a = camera.world(Pt(0f, 0f))
        val b = camera.world(Pt(width / density, height / density))
        state.fillAt(point, Bounds(a.x, a.y, b.x, b.y))
    }

    private fun begin(e: MotionEvent, index: Int) {
        settle()
        pointer = e.getPointerId(index)
        start = world(e, index)
        last = start
        textDragged = false
        activeTool =
            if (
                e.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
                    e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
            )
                Tool.ERASER
            else state.tool
        gestureBefore = state.document.items
        when (activeTool) {
            Tool.PEN,
            Tool.HIGHLIGHTER -> {
                requestUnbufferedDispatch(e)
                predictor.record(e)
                val inverse = Matrix()
                screenMatrix().invert(inverse)
                val high = activeTool == Tool.HIGHLIGHTER
                recordedBrush = if (high) currentMarker() else currentPen()
                recordedInputs = MutableStrokeInputBatch()
                inputStartTime = e.eventTime
                lastInputTime = -1
                inputTool =
                    if (e.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER) InputToolType.TOUCH
                    else InputToolType.STYLUS
                if (high) highlightPreview = LiveHighlight(state.color, state.strokeWidth * 5)
                recordInputs(e, index)
                if (!high) activeStroke = ink.startStroke(e, pointer, recordedBrush!!, inverse)
            }
            Tool.TEXT,
            Tool.FILL -> state.selection = emptySet()
            Tool.ERASER -> erase(start, start)
            Tool.LASSO -> {
                originalBounds = selectionBounds()
                sizingMode = state.selectionSizing
                sizingTextOnly =
                    state.document.items
                        .filter { it.id in state.selection }
                        .let { it.isNotEmpty() && it.all { item -> item.kind == "TEXT" } }
                resizing =
                    originalBounds?.let {
                        hypot(start.x - it.right, start.y - it.bottom) <
                            20f / state.document.camera.zoom
                    } ?: false
                moving =
                    !resizing &&
                        originalBounds?.outset(10f / state.document.camera.zoom)?.contains(start) ==
                            true
                if (!moving && !resizing) {
                    state.selection = emptySet()
                    lasso = mutableListOf(start)
                }
            }
            else -> {
                state.selection = emptySet()
                shape =
                    Item(
                        kind = activeTool.name,
                        color = state.color,
                        width = state.strokeWidth,
                        points = listOf(start, start),
                        rows = state.rows,
                        cols = state.cols,
                    )
            }
        }
        refresh()
    }

    private fun handleOwned(e: MotionEvent) {
        if (pointer < 0) return
        val i = e.findPointerIndex(pointer)
        if (i < 0) return
        val released =
            (e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_POINTER_UP) &&
                e.getPointerId(e.actionIndex) == pointer
        if (released && e.flags and MotionEvent.FLAG_CANCELED != 0) {
            cancelGesture(e)
            return
        }
        if (e.actionMasked != MotionEvent.ACTION_MOVE && !released) return
        val p = world(e, i)
        if (activeTool == Tool.TEXT || activeTool == Tool.FILL) {
            if (hypot(p.x - start.x, p.y - start.y) > 8f / state.document.camera.zoom)
                textDragged = true
            if (released) {
                pointer = -1
                gestureBefore = null
                if (!textDragged) {
                    if (activeTool == Tool.FILL) requestFill(start) else state.requestText(start)
                }
                textDragged = false
            }
            return
        }
        if (highlightPreview != null) {
            recordInputs(e, i)
            if (released) {
                state.commit(
                    state.document.items + strokeItem(recordedInputs, recordedBrush!!, true)
                )
                highlightPreview = null
                pointer = -1
                gestureBefore = null
            }
            if (released) refresh() else highlights.postInvalidateOnAnimation()
            return
        }
        activeStroke?.let { id ->
            predictor.record(e)
            recordInputs(e, i)
            if (released) {
                val item =
                    strokeItem(recordedInputs, recordedBrush!!, activeTool == Tool.HIGHLIGHTER)
                handoffs[id] = item.id
                state.commit(state.document.items + item)
                ink.finishStroke(e, pointer, id)
                activeStroke = null
                pointer = -1
                gestureBefore = null
            } else {
                val predicted = predictor.predict()
                try {
                    ink.addToStroke(e, pointer, id, predicted)
                } finally {
                    predicted?.recycle()
                }
            }
            return
        }
        when (activeTool) {
            Tool.ERASER -> erase(last, p)
            Tool.LASSO ->
                if (moving || resizing) {
                    val before = gestureBefore ?: emptyList()
                    val bounds = originalBounds
                    var fx =
                        if (resizing && bounds != null)
                            ((p.x - bounds.left) / max(bounds.width, 1f)).coerceIn(.05f, 20f)
                        else 1f
                    var fy =
                        if (resizing && bounds != null)
                            ((p.y - bounds.top) / max(bounds.height, 1f)).coerceIn(.05f, 20f)
                        else 1f
                    if (resizing && sizingMode == SelectionSizing.SCALE && sizingTextOnly) {
                        val factor = if (abs(fx - 1f) >= abs(fy - 1f)) fx else fy
                        fx = factor
                        fy = factor
                    }
                    state.preview(
                        before.map { item ->
                            if (item.id !in state.selection) item
                            else if (resizing && bounds != null)
                                sizeSelectionItem(item, bounds, fx, fy, sizingMode)
                            else
                                item.copy(
                                    transform = item.transform.move(p.x - start.x, p.y - start.y)
                                )
                        }
                    )
                } else {
                    if (hypot(p.x - last.x, p.y - last.y) > 1f / state.document.camera.zoom)
                        lasso.add(p)
                }
            else -> {
                var end = p
                if (activeTool == Tool.SQUARE || activeTool == Tool.CIRCLE) {
                    val size = max(abs(p.x - start.x), abs(p.y - start.y))
                    end =
                        Pt(
                            start.x + size * if (p.x >= start.x) 1 else -1,
                            start.y + size * if (p.y >= start.y) 1 else -1,
                        )
                }
                shape = shape?.copy(points = listOf(start, end))
            }
        }
        last = p
        if (released) {
            val before = gestureBefore ?: state.document.items
            if (activeTool == Tool.LASSO && !moving && !resizing) {
                val items = state.document.items
                val selected =
                    if (
                        hypot(p.x - start.x, p.y - start.y) < 8f / state.document.camera.zoom &&
                            lasso.size < 5
                    ) {
                        val radius = 12f / state.document.camera.zoom
                        sceneIndex.query(items, Bounds(p.x, p.y, p.x, p.y).outset(radius), hits)
                        var found: Item? = null
                        for (k in hits.size - 1 downTo 0) {
                            val item = items[hits[k]]
                            if (hitItem(item, p, radius)) {
                                found = item
                                break
                            }
                        }
                        found?.let { setOf(it.id) } ?: emptySet()
                    } else if (lasso.size < 3) emptySet()
                    else {
                        // Only items overlapping the loop's bounds can be inside it.
                        sceneIndex.query(items, Bounds.of(lasso), hits)
                        val ids = LinkedHashSet<String>()
                        for (k in 0 until hits.size) {
                            val item = items[hits[k]]
                            if (lassoHits(item, lasso)) ids.add(item.id)
                        }
                        ids
                    }
                state.selection = selected
            } else if (shape != null) {
                if (hypot(p.x - start.x, p.y - start.y) > 2f / state.document.camera.zoom)
                    state.commit(state.document.items + shape!!, before)
            } else state.commit(state.document.items, before)
            shape = null
            lasso.clear()
            moving = false
            resizing = false
            pointer = -1
            gestureBefore = null
            if (hypot(p.x - start.x, p.y - start.y) < 4f) performClick()
        }
        refresh()
    }

    private fun erase(a: Pt, b: Pt) {
        val radius = 14f / state.document.camera.zoom
        val steps =
            max(1, ceil(hypot(b.x - a.x, b.y - a.y) / (radius * .5f)).toInt()).coerceAtMost(1000)
        val items = state.document.items
        // Only items near the eraser path are tested.
        val area = Bounds(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)).outset(radius)
        sceneIndex.query(items, area, hits)
        var erased: java.util.IdentityHashMap<Item, Unit>? = null
        for (k in 0 until hits.size) {
            val item = items[hits[k]]
            if (item.locked || item.image) continue
            if (
                (0..steps).any { s ->
                    hitItem(
                        item,
                        Pt(a.x + (b.x - a.x) * s / steps, a.y + (b.y - a.y) * s / steps),
                        radius,
                    )
                }
            )
                (erased ?: java.util.IdentityHashMap<Item, Unit>().also { erased = it })[item] = Unit
        }
        val removed = erased ?: return
        state.preview(items.filterNot { removed.containsKey(it) })
    }

    private fun cancelGesture(e: MotionEvent) {
        activeStroke?.let {
            ink.cancelStroke(it, e)
            handoffs.remove(it)
        }
        activeStroke = null
        highlightPreview = null
        gestureBefore?.let(state::preview)
        gestureBefore = null
        shape = null
        lasso.clear()
        pointer = -1
        moving = false
        resizing = false
        sprite = null
        refresh()
    }

    private fun navigate(e: MotionEvent) {
        trackPan(e)
        if (
            e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_POINTER_UP
        ) {
            navFocus = null
            navSpan = 0f
            if (e.actionMasked == MotionEvent.ACTION_UP) navigation = false
            return
        }
        val points = (0 until e.pointerCount).map { Pt(e.getX(it) / density, e.getY(it) / density) }
        val focus =
            Pt(
                points.sumOf { it.x.toDouble() }.toFloat() / points.size,
                points.sumOf { it.y.toDouble() }.toFloat() / points.size,
            )
        val span =
            if (points.size >= 2) hypot(points[0].x - points[1].x, points[0].y - points[1].y)
            else 0f
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            navFocus = focus
            navSpan = span
            return
        }
        navFocus?.let { old ->
            var c = state.document.camera
            if (span > 0 && navSpan > 0) c = c.zoomAt(old, span / navSpan)
            c = c.copy(x = c.x + focus.x - old.x, y = c.y + focus.y - old.y)
            state.camera(c)
            refresh()
        }
        navFocus = focus
        navSpan = span
    }
}

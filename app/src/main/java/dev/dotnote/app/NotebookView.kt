package dev.dotnote.app

import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.*
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import java.util.concurrent.Executors
import kotlin.math.*

// Constructed exclusively by Compose AndroidView with its editor state.
@android.annotation.SuppressLint("ViewConstructor")
class NotebookView(context: Context, val state: AppState) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val ink = InProgressStrokesView(context)
    private var preparedPen = brush(state.color, state.strokeWidth, false)
    private val renderer = ObjectRenderer()
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
    private var highlightPreview: Item? = null
    private val recordedPoints = mutableListOf<Pt>()
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
    private var originalBounds: Bounds? = null
    private var navFocus: Pt? = null
    private var navSpan = 0f
    private var navigation = false
    private var suppressFingers = false
    private val predictor = MotionEventPredictor.newInstance(this)
    private val content =
        object : View(context) {
            override fun onDraw(canvas: Canvas) {
                drawContent(canvas)
            }
        }

    init {
        setWillNotDraw(false)
        isFocusable = true
        contentDescription = "Note canvas. Write with the stylus, drag to pan, and pinch to zoom."
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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

    private fun currentPen(): Brush {
        if (preparedPen.colorIntArgb != state.color || preparedPen.size != state.strokeWidth)
            preparedPen = brush(state.color, state.strokeWidth, false)
        return preparedPen
    }

    fun refresh() {
        currentPen()
        content.invalidate()
    }

    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        strokes.keys.forEach { handoffs.remove(it) }
        content.invalidate()
        ink.removeFinishedStrokes(strokes.keys)
    }

    fun settle() {
        handOff(ink.getFinishedStrokes())
    }

    private fun recordInputs(event: MotionEvent, index: Int) {
        fun append(x: Float, y: Float, time: Long, pressure: Float) {
            val elapsed = (time - inputStartTime).coerceAtLeast(0)
            if (elapsed <= lastInputTime) return
            val point = state.document.camera.world(Pt(x / density, y / density))
            recordedPoints.add(point)
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
        val b = state.document.bounds ?: Bounds(0f, 0f, 800f, 700f)
        val w = width / density
        val h = height / density
        val z =
            min((w - 80) / max(100f, b.width), (h - 80) / max(100f, b.height)).coerceIn(.08f, 2f)
        state.camera(
            Camera((w - b.width * z) / 2 - b.left * z, (h - b.height * z) / 2 - b.top * z, z)
        )
        refresh()
    }

    fun page(item: Item) {
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

    private fun selectionBounds() =
        state.document.items
            .filter { it.id in state.selection }
            .map { it.bounds }
            .reduceOrNull { a, b -> a.union(b) }

    private fun drawContent(canvas: Canvas) {
        canvas.drawColor(0xfffafaf6.toInt())
        val doc = state.document
        val visible = viewport()
        val matrix = screenMatrix()
        val z = doc.camera.zoom
        canvas.save()
        canvas.concat(matrix)
        if (doc.dots) {
            var spacing = 24f
            while (spacing * z < 12) spacing *= 2
            paint.color = 0xffcdd3ca.toInt()
            paint.style = Paint.Style.FILL
            var x = floor(visible.left / spacing) * spacing
            while (x < visible.right) {
                var y = floor(visible.top / spacing) * spacing
                while (y < visible.bottom) {
                    canvas.drawCircle(x, y, 1f / z, paint)
                    y += spacing
                }
                x += spacing
            }
        }
        doc.items
            .filter { it.kind == "PDF" && it.bounds.intersects(visible) }
            .forEach { item ->
                val b = item.bounds
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
        renderer.drawScene(
            canvas,
            doc.items.filter {
                it.kind != "PDF" && it.id !in handoffs.values && it.bounds.intersects(visible)
            },
            matrix,
            highlightPreview,
        )
        shape?.let { renderer.draw(canvas, it, matrix) }
        if (lasso.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.color = 0xff255a4e.toInt()
            paint.strokeWidth = 1.5f / z
            paint.pathEffect = DashPathEffect(floatArrayOf(6f / z, 4f / z), 0f)
            val path =
                Path().apply {
                    moveTo(lasso[0].x, lasso[0].y)
                    lasso.drop(1).forEach { lineTo(it.x, it.y) }
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
        parent?.requestDisallowInterceptTouchEvent(true)
        val action = event.actionMasked
        val index = event.actionIndex
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelGesture(event)
            navigation = false
            return true
        }
        if (action == MotionEvent.ACTION_DOWN) {
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
            if (state.fingerDrawing && state.tool != Tool.HAND) begin(event, index)
            else {
                navigation = true
                navigate(event)
            }
        } else if (navigation) navigate(event) else handleOwned(event)
        return true
    }

    private fun begin(e: MotionEvent, index: Int) {
        settle()
        pointer = e.getPointerId(index)
        start = world(e, index)
        last = start
        activeTool =
            if (
                e.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
                    e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
            )
                Tool.ERASER
            else state.tool
        if (activeTool == Tool.HAND) {
            pointer = -1
            suppressFingers = false
            navigation = true
            navigate(e)
            return
        }
        gestureBefore = state.document.items
        when (activeTool) {
            Tool.PEN,
            Tool.HIGHLIGHTER -> {
                requestUnbufferedDispatch(e)
                predictor.record(e)
                val inverse = Matrix()
                screenMatrix().invert(inverse)
                val high = activeTool == Tool.HIGHLIGHTER
                recordedBrush =
                    if (high) brush(state.color, state.strokeWidth * 5, true) else currentPen()
                recordedInputs = MutableStrokeInputBatch()
                recordedPoints.clear()
                inputStartTime = e.eventTime
                lastInputTime = -1
                inputTool =
                    if (e.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER) InputToolType.TOUCH
                    else InputToolType.STYLUS
                recordInputs(e, index)
                if (high)
                    highlightPreview =
                        Item(
                            kind = "HIGHLIGHTER",
                            color = state.color,
                            width = state.strokeWidth * 5,
                            points = recordedPoints.toList(),
                        )
                else activeStroke = ink.startStroke(e, pointer, recordedBrush!!, inverse)
            }
            Tool.ERASER -> erase(start, start)
            Tool.LASSO -> {
                originalBounds = selectionBounds()
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
        if (highlightPreview != null) {
            recordInputs(e, i)
            highlightPreview = highlightPreview!!.copy(points = recordedPoints.toList())
            if (released) {
                state.commit(
                    state.document.items + strokeItem(Stroke(recordedBrush!!, recordedInputs), true)
                )
                highlightPreview = null
                pointer = -1
                gestureBefore = null
            }
            refresh()
            return
        }
        activeStroke?.let { id ->
            predictor.record(e)
            recordInputs(e, i)
            if (released) {
                val item =
                    strokeItem(
                        Stroke(recordedBrush!!, recordedInputs),
                        activeTool == Tool.HIGHLIGHTER,
                    )
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
                    val fx =
                        if (resizing && bounds != null)
                            ((p.x - bounds.left) / max(bounds.width, 1f)).coerceIn(.05f, 20f)
                        else 1f
                    val fy =
                        if (resizing && bounds != null)
                            ((p.y - bounds.top) / max(bounds.height, 1f)).coerceIn(.05f, 20f)
                        else 1f
                    state.preview(
                        before.map { item ->
                            if (item.id !in state.selection) item
                            else
                                item.copy(
                                    transform =
                                        if (resizing && bounds != null)
                                            item.transform.resize(
                                                Pt(bounds.left, bounds.top),
                                                fx,
                                                fy,
                                            )
                                        else item.transform.move(p.x - start.x, p.y - start.y)
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
                val selected =
                    if (
                        hypot(p.x - start.x, p.y - start.y) < 8f / state.document.camera.zoom &&
                            lasso.size < 5
                    ) {
                        state.document.items
                            .lastOrNull { hitItem(it, p, 12f / state.document.camera.zoom) }
                            ?.let { setOf(it.id) } ?: emptySet()
                    } else
                        state.document.items
                            .filter { item ->
                                !item.locked &&
                                    item.points.any { insidePolygon(item.transform.map(it), lasso) }
                            }
                            .map(Item::id)
                            .toSet()
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
        state.preview(
            state.document.items.filterNot { item ->
                !item.locked &&
                    (0..steps).any { s ->
                        hitItem(
                            item,
                            Pt(a.x + (b.x - a.x) * s / steps, a.y + (b.y - a.y) * s / steps),
                            radius,
                        )
                    }
            }
        )
    }

    private fun cancelGesture(e: MotionEvent) {
        activeStroke?.let {
            ink.cancelStroke(it, e)
            handoffs.remove(it)
        }
        activeStroke = null
        highlightPreview = null
        recordedPoints.clear()
        gestureBefore?.let(state::preview)
        gestureBefore = null
        shape = null
        lasso.clear()
        pointer = -1
        moving = false
        resizing = false
        refresh()
    }

    private fun navigate(e: MotionEvent) {
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

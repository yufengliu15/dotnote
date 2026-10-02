package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Process
import android.os.SystemClock
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Raster cache for finished ink and marker strokes.
 *
 * Drawing every visible stroke on every frame made pan, zoom and pen-up cost grow with the number
 * of strokes on screen. Instead, the scene is rendered once into 512 px tiles at the current zoom
 * on a background thread, and frames only blit tiles. Edits update tiles precisely:
 * - an appended stroke is drawn onto the existing tiles it touches (it is topmost);
 * - any other edit re-renders only tiles that intersect the changed items;
 * - while a selection is dragged, it is excluded from tiles and drawn live on top.
 * Missing tiles fall back to a lower-resolution level, then to direct vector drawing, so a frame
 * is never blank or stale. All public methods run on the UI thread.
 */
internal class SceneTiles(
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val changed: () -> Unit,
    private val strokes: StrokeCache,
) {
    companion object {
        const val SIZE = 512
        private const val BUDGET_BYTES = 96L * 1024 * 1024
        private const val MOTION_MS = 140L
        private const val LOG_LIMIT = 96
        /** Marker opacity. Marker tiles store fills already faded to it. */
        const val MARKER_ALPHA = 85

        /** Fades opaque marker fills within the current clip, like a translucent layer would. */
        fun fade(canvas: Canvas) =
            canvas.drawColor(
                android.graphics.Color.argb(MARKER_ALPHA, 0, 0, 0),
                android.graphics.PorterDuff.Mode.DST_IN,
            )
    }

    private class Level(val scale: Float) {
        val tiles = HashMap<Long, Tile>()
    }

    private class Tile(val level: Level, val x: Int, val y: Int) {
        // Two device pixels of antialiasing margin, in world units.
        val pad = 2f / level.scale
        var ink: Bitmap? = null
        var marker: Bitmap? = null
        var ready = false
        var inFlight = false
        // Scene version whose render failed; vector fallback covers it until the scene changes.
        var failedAt = -1
        var used = 0L
        val world =
            Bounds(
                x * SIZE / level.scale,
                y * SIZE / level.scale,
                (x + 1) * SIZE / level.scale,
                (y + 1) * SIZE / level.scale,
            )
    }

    /** One scene edit, kept so tiles rendered from an older scene can catch up. */
    private class Change(val version: Int, val appended: List<Item>?, val dirty: List<Bounds>?)

    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(
                    {
                        Process.setThreadPriority(
                            Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE
                        )
                        r.run()
                    },
                    "Dotnote-Tiles",
                )
                .apply { isDaemon = true }
        }
    // Helpers that tessellate a tile's missing strokes alongside the tile thread. Opening a dense
    // note is dominated by building stroke meshes, which are independent of each other.
    private val inkThreads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(0, 3)
    private val inkPool: ExecutorService? =
        if (inkThreads == 0) null
        else
            Executors.newFixedThreadPool(inkThreads) { r ->
                Thread(
                        {
                            // Extra parallelism only: never compete with the UI thread.
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                            r.run()
                        },
                        "Dotnote-Ink",
                    )
                    .apply { isDaemon = true }
            }
    private val uiRenderer = ObjectRenderer(vectorHighlights = false, sharedStrokes = strokes)
    private val workerRenderer = ObjectRenderer(vectorHighlights = false, sharedStrokes = strokes)
    private val workerIndex = SceneIndex()
    private val workerScratch = IntList()
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private val quarantine = ArrayDeque<Pair<Long, Bitmap>>()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val tileMatrix = Matrix()

    private var level: Level? = null
    private var previous: Level? = null
    // Whole-note, low-resolution level: covers areas revealed while zooming out.
    private var overview: Level? = null
    private var overviewBounds: Bounds? = null
    private var lastScale = 0f
    private var scaleChangedAt = 0L
    private var settleQueued = false
    private var frame = 0L
    @Volatile private var disposed = false
    private var inFlight = 0

    // Tile scene: the document without hidden (handing-off) and excluded (dragged) items.
    private var allItems: List<Item>? = null
    private var excludedIds: Set<String> = emptySet()
    private var hiddenIds: Set<String> = emptySet()
    var items: List<Item> = emptyList()
        private set

    private var version = 0
    private var resetVersion = 0
    private val log = ArrayDeque<Change>()
    var colorOrder = IntArray(0)
        private set

    var hasMarkers = false
        private set

    private val wanted = ArrayList<Tile>()
    private var bytes = 0L

    internal var tileRenders = 0
        private set

    /** Brings the tile scene up to date with the document; cheap when nothing changed. */
    fun update(all: List<Item>, excluded: Set<String>, hidden: Collection<String>) {
        val hiddenNow = if (hidden.isEmpty()) emptySet() else hidden.toHashSet()
        if (all === allItems && excluded == excludedIds && hiddenNow == hiddenIds) return
        allItems = all
        excludedIds = excluded
        hiddenIds = hiddenNow
        val desired =
            if (excluded.isEmpty() && hiddenNow.isEmpty()) all
            else all.filter { it.id !in excluded && it.id !in hiddenNow }
        val old = items
        if (desired === old) return
        if (desired.size == old.size) {
            var same = true
            for (i in desired.indices) if (desired[i] !== old[i]) {
                same = false
                break
            }
            if (same) return
        }
        items = desired
        val order = markerOrder(desired)
        hasMarkers = order.isNotEmpty()
        val reordered = !order.contentEquals(colorOrder)
        colorOrder = order
        // Pure append: draw the new items on top of every ready tile they touch.
        if (!reordered && desired.size > old.size && old.isNotEmpty()) {
            var prefix = true
            for (i in old.indices) if (desired[i] !== old[i]) {
                prefix = false
                break
            }
            if (prefix) {
                val appended = desired.subList(old.size, desired.size).toList()
                record(Change(++version, appended, null))
                dropPrevious()
                val started = System.nanoTime()
                listOfNotNull(level, overview).forEach { l ->
                    l.tiles.values.forEach { tile -> if (tile.ready) applyAppend(tile, appended) }
                }
                updateOverview()
                if (BuildConfig.DEBUG)
                    android.util.Log.i("DotnoteTiles", "append ${(System.nanoTime() - started) / 1e6} ms")
                return
            }
        }
        val dirty = ArrayList<Bounds>()
        if (desired.size == old.size) {
            for (i in desired.indices) if (desired[i] !== old[i]) {
                dirty.add(old[i].bounds)
                dirty.add(desired[i].bounds)
            }
        } else if (desired.size < old.size && removedOnly(old, desired, dirty)) {
            // Erase/delete: dirty holds the removed items.
        } else {
            dirty.clear()
            val before = IdentityHashMap<Item, Unit>(old.size * 2)
            old.forEach { before[it] = Unit }
            val after = IdentityHashMap<Item, Unit>(desired.size * 2)
            desired.forEach {
                after[it] = Unit
                if (!before.containsKey(it)) dirty.add(it.bounds)
            }
            old.forEach { if (!after.containsKey(it)) dirty.add(it.bounds) }
        }
        if (reordered) {
            // Marker colors stack by last use; every tile with markers must be redrawn.
            old.forEach { if (it.kind == "HIGHLIGHTER") dirty.add(it.bounds) }
            desired.forEach { if (it.kind == "HIGHLIGHTER") dirty.add(it.bounds) }
        }
        if (dirty.size > 4000) {
            reset()
            return
        }
        record(Change(++version, null, dirty))
        dropPrevious()
        listOfNotNull(level, overview).forEach { l ->
            l.tiles.values.forEach { tile ->
                if (!tile.ready) return@forEach
                val touched = dirty.filter { it.outset(tile.pad).intersects(tile.world) }
                if (touched.isNotEmpty() && !repaint(tile, touched)) invalidate(tile)
            }
        }
        updateOverview()
    }

    /** Sizes the overview to the note: at most 2048 px on its longer side. */
    private fun updateOverview() {
        var box: Bounds? = null
        for (item in items) if (item.kind != "PDF") box = box?.union(item.bounds) ?: item.bounds
        val b = box
        if (b == null || !b.width.isFinite() || !b.height.isFinite()) {
            overview?.tiles?.values?.forEach(::invalidate)
            overview = null
            overviewBounds = null
            return
        }
        val known = overviewBounds
        if (
            overview != null &&
                known != null &&
                b.left >= known.left &&
                b.top >= known.top &&
                b.right <= known.right &&
                b.bottom <= known.bottom
        )
            return
        // Leave room to grow so ordinary writing does not resize it.
        val grown = b.outset(maxOf(b.width, b.height, 256f) * .25f)
        val scale = 2048f / maxOf(grown.width, grown.height, 1f)
        overview?.tiles?.values?.forEach(::invalidate)
        if (scale < 1e-4f) {
            overview = null
            overviewBounds = null
            return
        }
        overview = Level(scale)
        overviewBounds = grown
    }

    /** True when [new] is [old] with some items removed; collects their bounds. */
    private fun removedOnly(old: List<Item>, new: List<Item>, dirty: MutableList<Bounds>): Boolean {
        var j = 0
        for (i in old.indices) {
            if (j < new.size && old[i] === new[j]) j++ else dirty.add(old[i].bounds)
        }
        return j == new.size
    }

    private val uiIndex = SceneIndex()
    private val uiHits = IntList()
    private val clip = RectF()

    /**
     * Redraws only the changed parts of a ready tile: each dirty rectangle (pixel-aligned) is
     * cleared and every item touching it is drawn again in order, clipped to it. Coverage of a
     * pixel does not depend on pixels outside it, so the result equals a full render. Returns
     * false when the area is large enough that a background re-render is cheaper.
     */
    private fun repaint(tile: Tile, dirty: List<Bounds>): Boolean {
        if (dirty.size > 48) return false
        val l = tile.level.scale
        val ox = tile.x * SIZE
        val oy = tile.y * SIZE
        val rects = ArrayList<IntArray>(dirty.size)
        var area = 0L
        for (b in dirty) {
            val r = b.outset(tile.pad)
            val left = maxOf(0, floor(r.left * l - ox).toInt())
            val top = maxOf(0, floor(r.top * l - oy).toInt())
            val right = minOf(SIZE, kotlin.math.ceil(r.right * l - ox).toInt())
            val bottom = minOf(SIZE, kotlin.math.ceil(r.bottom * l - oy).toInt())
            if (right <= left || bottom <= top) continue
            area += (right - left).toLong() * (bottom - top)
            rects.add(intArrayOf(left, top, right, bottom))
        }
        if (area > SIZE.toLong() * SIZE / 2) return false
        for (r in rects) {
            val world =
                Bounds((r[0] + ox) / l, (r[1] + oy) / l, (r[2] + ox) / l, (r[3] + oy) / l)
                    .outset(tile.pad)
            uiIndex.query(items, world, uiHits)
            val ink = ArrayList<Item>()
            val marker = ArrayList<Item>()
            for (k in 0 until uiHits.size) {
                val item = items[uiHits[k]]
                if (item.kind == "HIGHLIGHTER") marker.add(item) else if (item.kind != "PDF") ink.add(item)
            }
            clip.set(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
            repaintLayer(tile, false, ink)
            repaintLayer(tile, true, marker)
        }
        return true
    }

    private fun repaintLayer(tile: Tile, markers: Boolean, list: List<Item>) {
        var bitmap = if (markers) tile.marker else tile.ink
        if (bitmap == null) {
            if (list.isEmpty()) return
            bitmap = obtain()
            bytes += bitmap.allocationByteCount
            if (markers) tile.marker = bitmap else tile.ink = bitmap
        }
        val canvas = Canvas(bitmap)
        canvas.clipRect(clip)
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        if (list.isEmpty()) return
        canvas.translate(-tile.x * SIZE.toFloat(), -tile.y * SIZE.toFloat())
        canvas.scale(tile.level.scale, tile.level.scale)
        if (markers) {
            uiRenderer.drawMarkerFills(canvas, list, colorOrder)
            fade(canvas)
        } else {
            val matrix = Matrix(canvas.matrix)
            list.forEach { uiRenderer.draw(canvas, it, matrix) }
        }
    }

    private fun markerOrder(list: List<Item>): IntArray {
        var colors: LinkedHashSet<Int>? = null
        for (i in list.indices.reversed()) {
            val item = list[i]
            if (item.kind == "HIGHLIGHTER") {
                if (colors == null) colors = LinkedHashSet()
                colors.add(item.color)
            }
        }
        // Built from the end, so reverse: the most recently used color is drawn last (on top).
        return colors?.toIntArray()?.reversedArray() ?: IntArray(0)
    }

    private fun record(change: Change) {
        log.addLast(change)
        while (log.size > LOG_LIMIT) log.removeFirst()
    }

    /** Discards every tile; jobs started before now are dropped when they finish. */
    private fun reset() {
        version++
        resetVersion = version
        log.clear()
        listOfNotNull(level, previous, overview).forEach { l -> l.tiles.values.forEach(::invalidate) }
        previous = null
        overview = null
        overviewBounds = null
        updateOverview()
    }

    private fun dropPrevious() {
        previous?.tiles?.values?.forEach(::invalidate)
        previous = null
    }

    private fun invalidate(tile: Tile) {
        tile.ready = false
        release(tile.ink)
        release(tile.marker)
        tile.ink = null
        tile.marker = null
    }

    private fun release(bitmap: Bitmap?) {
        bitmap ?: return
        bytes -= bitmap.allocationByteCount
        quarantine.addLast(SystemClock.uptimeMillis() to bitmap)
    }

    private fun recycleQuarantine() {
        // A bitmap may still be referenced by the frame being displayed; reuse it a bit later.
        val now = SystemClock.uptimeMillis()
        while (quarantine.isNotEmpty() && now - quarantine.first().first > 300) {
            val bitmap = quarantine.removeFirst().second
            if (pool.size < 16) pool.add(bitmap)
        }
    }

    private fun obtain(): Bitmap =
        pool.poll()?.also { it.eraseColor(0) }
            ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    private fun tileCanvas(tile: Tile, bitmap: Bitmap): Pair<Canvas, Matrix> {
        val canvas = Canvas(bitmap)
        canvas.translate(-tile.x * SIZE.toFloat(), -tile.y * SIZE.toFloat())
        canvas.scale(tile.level.scale, tile.level.scale)
        return canvas to Matrix(canvas.matrix)
    }

    /** Draws appended items onto a tile's bitmaps (UI thread or completion handler). */
    private fun applyAppend(tile: Tile, appended: List<Item>) {
        val ink = appended.filter { isInk(it) && it.bounds.outset(tile.pad).intersects(tile.world) }
        val marker =
            appended.filter {
                it.kind == "HIGHLIGHTER" && it.bounds.outset(tile.pad).intersects(tile.world)
            }
        if (ink.isNotEmpty()) {
            val bitmap = tile.ink ?: obtain().also {
                tile.ink = it
                bytes += it.allocationByteCount
            }
            val (canvas, matrix) = tileCanvas(tile, bitmap)
            ink.forEach { uiRenderer.draw(canvas, it, matrix) }
        }
        // Faded marker pixels cannot simply be drawn over: repaint the region instead.
        if (marker.isNotEmpty() && !repaint(tile, marker.map { it.bounds })) invalidate(tile)
    }

    private fun isInk(item: Item) = item.kind != "PDF" && item.kind != "HIGHLIGHTER"

    /** Chooses the tile level for this frame; zoom gestures reuse the previous level scaled. */
    private fun levelFor(scale: Float): Level {
        val now = SystemClock.uptimeMillis()
        if (scale != lastScale) {
            lastScale = scale
            scaleChangedAt = now
        }
        val current = level
        if (current == null) return Level(scale).also { level = it }
        if (current.scale == scale) return current
        val ratio = scale / current.scale
        // A queued settle means this gesture is still treated as moving, so every layer drawn in
        // one traversal agrees even when a slow layer outlasts MOTION_MS.
        val moving = settleQueued || now - scaleChangedAt < MOTION_MS
        if (moving && ratio in .5f..2f) {
            if (!settleQueued) {
                settleQueued = true
                postDelayed(
                    Runnable {
                        settleQueued = false
                        changed()
                    },
                    MOTION_MS + 10,
                )
            }
            return current
        }
        dropPrevious()
        previous = current
        return Level(scale).also { level = it }
    }

    private class Frame(
        val level: Level,
        val scale: Float,
        val ox: Float,
        val oy: Float,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val moving: Boolean,
    )

    private var lastFrameKey: Any? = null
    private var lastFrame: Frame? = null

    private fun frame(camera: Camera, density: Float, width: Int, height: Int): Frame {
        val key = Triple(camera, width, height)
        lastFrame?.let { if (lastFrameKey == key && it.level === level && !it.moving) return it }
        frame++
        recycleQuarantine()
        val scale = camera.zoom * density
        val l = levelFor(scale)
        val ox = camera.x * density
        val oy = camera.y * density
        // Tiles at level scale L cover level pixels [i*SIZE, (i+1)*SIZE).
        val r = l.scale / scale
        val x0 = floor((-ox) * r / SIZE).toInt()
        val y0 = floor((-oy) * r / SIZE).toInt()
        val x1 = floor((width - ox) * r / SIZE).toInt()
        val y1 = floor((height - oy) * r / SIZE).toInt()
        val result = Frame(l, scale, ox, oy, x0, y0, x1, y1, l.scale != scale)
        lastFrameKey = key
        lastFrame = result
        return result
    }

    private fun tile(l: Level, x: Int, y: Int): Tile =
        l.tiles.getOrPut((x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)) { Tile(l, x, y) }

    /** Requests visible tiles first, then one ring around the viewport. */
    private fun schedule(f: Frame) {
        wanted.clear()
        if (f.moving || disposed) return
        val cx = (f.x0 + f.x1) / 2f
        val cy = (f.y0 + f.y1) / 2f
        val visible = ArrayList<Tile>()
        for (y in f.y0..f.y1) for (x in f.x0..f.x1) {
            val t = tile(f.level, x, y)
            t.used = frame
            if (!t.ready && !t.inFlight && t.failedAt != version) visible.add(t)
        }
        visible.sortBy { abs(it.x - cx) + abs(it.y - cy) }
        wanted.addAll(visible)
        for (y in f.y0 - 1..f.y1 + 1) for (x in f.x0 - 1..f.x1 + 1) {
            if (x in f.x0..f.x1 && y in f.y0..f.y1) continue
            val t = tile(f.level, x, y)
            t.used = frame
            if (!t.ready && !t.inFlight && t.failedAt != version) wanted.add(t)
        }
        overview?.let { o ->
            val b = overviewBounds ?: return@let
            val x0 = floor(b.left * o.scale / SIZE).toInt()
            val y0 = floor(b.top * o.scale / SIZE).toInt()
            val x1 = floor(b.right * o.scale / SIZE).toInt()
            val y1 = floor(b.bottom * o.scale / SIZE).toInt()
            for (y in y0..y1) for (x in x0..x1) {
                val t = tile(o, x, y)
                if (!t.ready && !t.inFlight && t.failedAt != version) wanted.add(t)
            }
        }
        pump()
    }

    private fun pump() {
        if (disposed) return
        while (inFlight < 1 && wanted.isNotEmpty()) {
            val t = wanted.removeAt(0)
            if (t.ready || t.inFlight || (t.level !== level && t.level !== overview)) continue
            if (t.failedAt == version) continue
            submit(t)
        }
    }

    private fun submit(t: Tile) {
        t.inFlight = true
        inFlight++
        val snapshot = items
        val jobVersion = version
        val order = colorOrder
        val pooled = arrayOf(pool.poll(), pool.poll())
        worker.execute {
            val started = System.nanoTime()
            var ink: Bitmap? = null
            var marker: Bitmap? = null
            var failed = false
            val unused = pooled.filterNotNull().toMutableList()
            try {
                workerIndex.query(snapshot, t.world.outset(t.pad), workerScratch)
                val inkItems = ArrayList<Item>()
                val markerItems = ArrayList<Item>()
                for (k in 0 until workerScratch.size) {
                    val item = snapshot[workerScratch[k]]
                    if (item.kind == "HIGHLIGHTER") markerItems.add(item)
                    else if (item.kind != "PDF") inkItems.add(item)
                }
                fun bitmap(): Bitmap =
                    (unused.removeFirstOrNull()?.also { it.eraseColor(0) }
                        ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))
                if (inkItems.isNotEmpty()) {
                    tessellate(inkItems)
                    ink = bitmap()
                    val (canvas, matrix) = tileCanvas(t, ink!!)
                    inkItems.forEach { workerRenderer.draw(canvas, it, matrix) }
                    ink!!.prepareToDraw()
                }
                if (markerItems.isNotEmpty()) {
                    marker = bitmap()
                    val (canvas, _) = tileCanvas(t, marker!!)
                    workerRenderer.drawMarkerFills(canvas, markerItems, order)
                    fade(canvas)
                    marker!!.prepareToDraw()
                }
            } catch (e: Throwable) {
                ink = null
                marker = null
                failed = true
                android.util.Log.w("DotnoteTiles", "Tile render failed", e)
            }
            unused.forEach { pool.add(it) }
            if (BuildConfig.DEBUG)
                android.util.Log.i(
                    "DotnoteTiles",
                    "tile ${t.x},${t.y} ${workerScratch.size} items ${(System.nanoTime() - started) / 1e6} ms",
                )
            val inkResult = ink
            val markerResult = marker
            post(Runnable { complete(t, jobVersion, inkResult, markerResult, failed) })
        }
    }

    /** Builds missing stroke meshes for one tile on the tile thread plus the ink helpers. */
    private fun tessellate(items: List<Item>) {
        val helpers = inkPool ?: return
        val missing = ArrayList<Item>()
        for (item in items) {
            if (item.ink != null && !item.fill && strokes.get(item) == null) missing.add(item)
        }
        if (missing.size < 8) return
        // More meshes than the shared cache holds would evict each other before drawing.
        val count = minOf(missing.size, strokes.limit / 2)
        val next = java.util.concurrent.atomic.AtomicInteger()
        val task = Runnable {
            while (!disposed) {
                val i = next.getAndIncrement()
                if (i >= count) break
                val item = missing[i]
                try {
                    strokes.put(item, inkStroke(item))
                } catch (_: Throwable) {
                    // The tile's own draw reports the failure.
                }
            }
        }
        val futures =
            try {
                List(inkThreads) { helpers.submit(task) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                return
            }
        task.run()
        futures.forEach {
            try {
                it.get()
            } catch (_: Exception) {}
        }
    }

    private fun complete(t: Tile, jobVersion: Int, ink: Bitmap?, marker: Bitmap?, failed: Boolean) {
        inFlight--
        t.inFlight = false
        tileRenders++
        if (failed) {
            t.failedAt = jobVersion
            pump()
            return
        }
        fun discard() {
            ink?.let { pool.add(it) }
            marker?.let { pool.add(it) }
        }
        if (
            disposed ||
                (t.level !== level && t.level !== previous && t.level !== overview) ||
                jobVersion < resetVersion
        ) {
            discard()
            pump()
            return
        }
        // Catch up with edits made while this tile was rendering.
        val pending = log.filter { it.version > jobVersion }
        val replayable = pending.size == version - jobVersion
        if (
            !replayable ||
                pending.any { change ->
                    change.dirty?.any { it.outset(t.pad).intersects(t.world) } == true
                }
        ) {
            discard()
            if (t.level === level || t.level === overview) wanted.add(0, t)
            pump()
            return
        }
        t.ink = ink
        t.marker = marker
        ink?.let { bytes += it.allocationByteCount }
        marker?.let { bytes += it.allocationByteCount }
        t.ready = true
        pending.forEach { change -> change.appended?.let { applyAppend(t, it) } }
        trim()
        // Redraw only when the finished tile shows something on screen: empty tiles and the
        // offscreen ring look exactly like what the frame already drew.
        if ((t.ink != null || t.marker != null) && onScreen(t)) changed()
        pump()
    }

    private fun onScreen(t: Tile): Boolean {
        val f = lastFrame ?: return true
        if (t.level !== f.level) return true
        return t.x in f.x0..f.x1 && t.y in f.y0..f.y1
    }

    /** Keeps tile memory bounded, evicting tiles that were not needed by the latest frame. */
    private fun trim() {
        if (bytes <= BUDGET_BYTES) return
        val candidates =
            listOfNotNull(previous, level)
                .flatMap { it.tiles.values }
                .filter { it.ready && it.used < frame && (it.ink != null || it.marker != null) }
                .sortedWith(compareBy({ it.level === level }, { it.used }))
        for (t in candidates) {
            if (bytes <= BUDGET_BYTES * 3 / 4) break
            invalidate(t)
            t.level.tiles.remove((t.x.toLong() shl 32) xor (t.y.toLong() and 0xffffffffL))
        }
        // Forget empty bookkeeping for far-away tiles.
        level?.tiles?.values?.removeAll { !it.ready && !it.inFlight && it.used < frame - 600 }
    }

    /**
     * Draws one layer. [fallback] renders the given world rectangle directly (vector); it is used
     * only where neither the current nor the previous level has a finished tile, in one pass
     * clipped to the missing area. When `complete` is false the area is already being rendered,
     * so the fallback may leave out strokes whose geometry is not built yet; they appear with
     * their tile instead of stalling this frame on tessellation.
     */
    fun draw(
        canvas: Canvas,
        camera: Camera,
        density: Float,
        width: Int,
        height: Int,
        markers: Boolean,
        fallback: (canvas: Canvas, area: Bounds, complete: Boolean) -> Unit,
    ) {
        val f = frame(camera, density, width, height)
        if (!markers) schedule(f)
        // Inside the caller's translucent layer, marker tiles are restored to opaque fills.
        tilePaint = if (markers) unfade else null
        try {
            drawLayer(canvas, f, markers, fallback)
        } finally {
            tilePaint = null
        }
    }

    private val unfade =
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter =
                android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply {
                        setScale(1f, 1f, 1f, 255f / MARKER_ALPHA)
                    }
                )
        }

    private fun drawLayer(
        canvas: Canvas,
        f: Frame,
        markers: Boolean,
        fallback: (canvas: Canvas, area: Bounds, complete: Boolean) -> Unit,
    ) {
        var missing: ArrayList<Tile>? = null
        for (y in f.y0..f.y1) for (x in f.x0..f.x1) {
            val t = tile(f.level, x, y)
            t.used = frame
            if (t.ready) {
                drawTile(canvas, f, t, if (markers) t.marker else t.ink)
                continue
            }
            canvas.save()
            screenRect(f, t)
            canvas.clipRect(dst)
            val covered = drawPrevious(canvas, f, t, markers)
            canvas.restore()
            if (!covered) (missing ?: ArrayList<Tile>().also { missing = it }).add(t)
        }
        val gaps = missing ?: return
        val complete = gaps.any { it.failedAt == version || f.moving }
        // Fill gaps per horizontal run of missing tiles. One bounding box over scattered gaps
        // (e.g. two corners) would make the fallback walk every item on screen.
        var i = 0
        while (i < gaps.size) {
            val first = gaps[i]
            var last = first
            while (
                i + 1 < gaps.size && gaps[i + 1].y == first.y && gaps[i + 1].x == last.x + 1
            ) {
                last = gaps[++i]
            }
            i++
            screenRect(f, first)
            val left = dst.left
            val top = dst.top
            screenRect(f, last)
            canvas.save()
            canvas.clipRect(left, top, dst.right, dst.bottom)
            fallback(canvas, first.world.union(last.world), complete)
            canvas.restore()
        }
        if (BuildConfig.DEBUG && gaps.size > 0)
            android.util.Log.i("DotnoteTiles", "gaps=${gaps.size} moving=${f.moving} markers=$markers")
    }

    private fun screenRect(f: Frame, t: Tile) {
        if (!f.moving) {
            val ox = f.ox.roundToInt()
            val oy = f.oy.roundToInt()
            dst.set(
                (t.x * SIZE + ox).toFloat(),
                (t.y * SIZE + oy).toFloat(),
                ((t.x + 1) * SIZE + ox).toFloat(),
                ((t.y + 1) * SIZE + oy).toFloat(),
            )
        } else {
            val k = f.scale / t.level.scale
            dst.set(
                t.x * SIZE * k + f.ox,
                t.y * SIZE * k + f.oy,
                (t.x + 1) * SIZE * k + f.ox,
                (t.y + 1) * SIZE * k + f.oy,
            )
        }
    }

    private fun drawTile(canvas: Canvas, f: Frame, t: Tile, bitmap: Bitmap?) {
        bitmap ?: return
        if (!f.moving && t.level === f.level) {
            // Whole-pixel placement: no resampling and no seams between tiles.
            canvas.drawBitmap(
                bitmap,
                (t.x * SIZE + f.ox.roundToInt()).toFloat(),
                (t.y * SIZE + f.oy.roundToInt()).toFloat(),
                tilePaint,
            )
        } else {
            val k = f.scale / t.level.scale
            dst.set(
                t.x * SIZE * k + f.ox,
                t.y * SIZE * k + f.oy,
                (t.x + 1) * SIZE * k + f.ox,
                (t.y + 1) * SIZE * k + f.oy,
            )
            canvas.drawBitmap(bitmap, null, dst, tilePaint ?: blit)
        }
    }

    private var tilePaint: Paint? = null

    /**
     * Draws the (already faded) marker tiles directly when every visible tile, or its
     * previous-level cover, is finished. Tiles never overlap, so this equals compositing opaque
     * fills in one translucent layer without allocating that layer. Returns false, drawing
     * nothing, when a gap needs the layered vector fallback.
     */
    fun drawTranslucent(
        canvas: Canvas,
        camera: Camera,
        density: Float,
        width: Int,
        height: Int,
    ): Boolean {
        val f = frame(camera, density, width, height)
        for (y in f.y0..f.y1) for (x in f.x0..f.x1) {
            val t = tile(f.level, x, y)
            if (!t.ready && !drawPrevious(null, f, t, true)) return false
        }
        tilePaint = null
        try {
            for (y in f.y0..f.y1) for (x in f.x0..f.x1) {
                val t = tile(f.level, x, y)
                t.used = frame
                if (t.ready) drawTile(canvas, f, t, t.marker)
                else {
                    canvas.save()
                    screenRect(f, t)
                    canvas.clipRect(dst)
                    drawPrevious(canvas, f, t, true)
                    canvas.restore()
                }
            }
        } finally {
            tilePaint = null
        }
        return true
    }

    /** Covers a missing tile with finished tiles from the previous zoom level, if complete. */
    private fun drawPrevious(canvas: Canvas?, f: Frame, t: Tile, markers: Boolean): Boolean =
        cover(canvas, f, t, markers, previous) || cover(canvas, f, t, markers, overview)

    private fun cover(canvas: Canvas?, f: Frame, t: Tile, markers: Boolean, p: Level?): Boolean {
        p ?: return false
        if (p === f.level) return false
        val x0 = floor(t.world.left * p.scale / SIZE).toInt()
        val y0 = floor(t.world.top * p.scale / SIZE).toInt()
        val x1 = floor((t.world.right * p.scale - .01f) / SIZE).toInt()
        val y1 = floor((t.world.bottom * p.scale - .01f) / SIZE).toInt()
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 16) return false
        for (y in y0..y1) for (x in x0..x1) {
            val pt = p.tiles[(x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)]
            if (pt == null || !pt.ready) return false
        }
        canvas ?: return true
        val frameForPrevious = Frame(p, f.scale, f.ox, f.oy, 0, 0, 0, 0, true)
        for (y in y0..y1) for (x in x0..x1) {
            val pt = p.tiles.getValue((x.toLong() shl 32) xor (y.toLong() and 0xffffffffL))
            pt.used = frame
            drawTile(canvas, frameForPrevious, pt, if (markers) pt.marker else pt.ink)
        }
        return true
    }

    /** Vector fallback helper: world-to-screen matrix for the current camera. */
    fun worldMatrix(camera: Camera, density: Float): Matrix =
        tileMatrix.apply {
            setValues(
                floatArrayOf(
                    camera.zoom * density,
                    0f,
                    camera.x * density,
                    0f,
                    camera.zoom * density,
                    camera.y * density,
                    0f,
                    0f,
                    1f,
                )
            )
        }

    fun release() {
        disposed = true
        worker.shutdown()
        inkPool?.shutdown()
        listOfNotNull(level, previous, overview).forEach { it.tiles.clear() }
        level = null
        previous = null
        pool.clear()
        quarantine.clear()
    }

    /** True when every tile needed for this view is finished (used by tests/benchmarks). */
    internal fun idle(): Boolean = inFlight == 0 && wanted.isEmpty()
}

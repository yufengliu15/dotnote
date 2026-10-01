package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tiled frames must match direct vector rendering after every kind of edit. */
@RunWith(AndroidJUnit4::class)
class TileRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val handler = Handler(Looper.getMainLooper())
    private val width = 900
    private val height = 700
    private val density = 2f

    private fun stroke(seed: Int, x: Float, y: Float, color: Int = Color.BLACK): Item {
        val inputs =
            MutableStrokeInputBatch().apply {
                repeat(30) { n ->
                    val t = n / 30f
                    add(
                        StrokeInput().apply {
                            update(
                                x + t * 120f,
                                y + sin(t * 8f + seed) * 14f,
                                n * 6L,
                                pressure = .5f + .3f * cos(t * 5f),
                            )
                        }
                    )
                }
            }
        return strokeItem(inputs, brush(color, 3f, false), false)
    }

    private fun scene(): List<Item> {
        val items = ArrayList<Item>()
        items.add(
            Item(
                kind = "HIGHLIGHTER",
                color = Color.YELLOW,
                width = 18f,
                points = List(20) { Pt(10f + it * 12f, 60f) },
            )
        )
        repeat(36) { items.add(stroke(it, 5f + (it % 6) * 70f, 20f + (it / 6) * 45f)) }
        items.add(
            Item(
                kind = "HIGHLIGHTER",
                color = 0xff80d8ff.toInt(),
                width = 18f,
                points = List(20) { Pt(100f, 10f + it * 14f) },
            )
        )
        items.add(
            Item(
                kind = "GRID",
                color = Color.BLUE,
                width = 2f,
                points = listOf(Pt(250f, 120f), Pt(420f, 300f)),
                rows = 4,
                cols = 5,
            )
        )
        items.add(
            Item(
                kind = "ELLIPSE",
                color = Color.RED,
                width = 3f,
                points = listOf(Pt(120f, 200f), Pt(260f, 330f)),
            )
        )
        items.add(textItem("Tiles match vectors", 20f, Color.DKGRAY, Pt(20f, 300f), null))
        return items
    }

    private fun order(items: List<Item>): IntArray {
        val colors = LinkedHashSet<Int>()
        items.filter { it.kind == "HIGHLIGHTER" }.reversed().forEach { colors.add(it.color) }
        return colors.toIntArray().reversedArray()
    }

    private fun matrix(camera: Camera) =
        Matrix().apply {
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

    /** What the editor drew before tiles: markers in one translucent layer, then ink on top. */
    private fun reference(items: List<Item>, camera: Camera, onTop: List<Item> = emptyList()): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val renderer = ObjectRenderer(vectorHighlights = false)
        val m = matrix(camera)
        val all = items + onTop
        val layer = canvas.saveLayerAlpha(null, 85)
        canvas.save()
        canvas.concat(m)
        renderer.drawMarkerFills(canvas, items.filter { it.kind == "HIGHLIGHTER" }, order(items))
        renderer.drawMarkerFills(
            canvas,
            onTop.filter { it.kind == "HIGHLIGHTER" },
            order(onTop),
        )
        canvas.restore()
        canvas.restoreToCount(layer)
        canvas.save()
        canvas.concat(m)
        all.filter { it.kind != "HIGHLIGHTER" && it.kind != "PDF" }.forEach {
            renderer.draw(canvas, it, m)
        }
        canvas.restore()
        return bitmap
    }

    private fun tiled(
        tiles: SceneTiles,
        all: List<Item>,
        camera: Camera,
        excluded: Set<String> = emptySet(),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            tiles.update(all, excluded, emptySet())
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val renderer = ObjectRenderer(vectorHighlights = false)
            val m = matrix(camera)
            val visible = tiles.items
            fun fallback(markers: Boolean): (Canvas, Bounds, Boolean) -> Unit = { c, area, _ ->
                c.save()
                c.concat(m)
                val inArea = visible.filter { it.bounds.intersects(area) }
                if (markers)
                    renderer.drawMarkerFills(
                        c,
                        inArea.filter { it.kind == "HIGHLIGHTER" },
                        tiles.colorOrder,
                    )
                else
                    inArea
                        .filter { it.kind != "HIGHLIGHTER" && it.kind != "PDF" }
                        .forEach { renderer.draw(c, it, m) }
                c.restore()
            }
            val dragged = all.filter { it.id in excluded }
            val layer = canvas.saveLayerAlpha(null, 85)
            tiles.draw(canvas, camera, density, width, height, true, fallback(true))
            canvas.save()
            canvas.concat(m)
            renderer.drawMarkerFills(
                canvas,
                dragged.filter { it.kind == "HIGHLIGHTER" },
                order(dragged),
            )
            canvas.restore()
            canvas.restoreToCount(layer)
            tiles.draw(canvas, camera, density, width, height, false, fallback(false))
            canvas.save()
            canvas.concat(m)
            dragged.filter { it.kind != "HIGHLIGHTER" }.forEach { renderer.draw(canvas, it, m) }
            canvas.restore()
        }
        return bitmap
    }

    private fun settle(tiles: SceneTiles, all: List<Item>, camera: Camera, excluded: Set<String> = emptySet()) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            tiled(tiles, all, camera, excluded).recycle()
            var idle = false
            instrumentation.runOnMainSync { idle = tiles.idle() }
            if (idle) return
            SystemClock.sleep(50)
        }
        fail("Tiles did not finish")
    }

    private fun assertSame(expected: Bitmap, actual: Bitmap, label: String) {
        var different = 0
        var worst = 0
        for (y in 0 until height) for (x in 0 until width) {
            val a = expected.getPixel(x, y)
            val b = actual.getPixel(x, y)
            if (a == b) continue
            val d =
                maxOf(
                    abs(Color.red(a) - Color.red(b)),
                    abs(Color.green(a) - Color.green(b)),
                    abs(Color.blue(a) - Color.blue(b)),
                )
            worst = maxOf(worst, d)
            if (d > 40) different++
        }
        assertTrue("$label: $different pixels differ (worst $worst)", different <= width * height / 2000)
    }

    @Test
    fun tilesMatchVectorsThroughEditsZoomAndDrag() {
        val strokes = StrokeCache()
        val tiles =
            SceneTiles(
                post = { handler.post(it) },
                postDelayed = { r, d -> handler.postDelayed(r, d) },
                changed = {},
                strokes = strokes,
            )
        try {
            var items: List<Item> = scene()
            var camera = Camera(13.5f, 20f, 1.25f)
            // First frame: nothing rendered yet, so the vector fallback must be exact.
            assertSame(reference(items, camera), tiled(tiles, items, camera), "fallback")
            settle(tiles, items, camera)
            assertSame(reference(items, camera), tiled(tiles, items, camera), "tiles")
            // Pan by whole pixels: tiles are reused.
            camera = camera.copy(x = camera.x - 61f, y = camera.y + 17.5f)
            settle(tiles, items, camera)
            assertSame(reference(items, camera), tiled(tiles, items, camera), "pan")
            // Append: drawn into existing tiles immediately.
            items = items + stroke(99, 180f, 150f, Color.MAGENTA)
            assertSame(reference(items, camera), tiled(tiles, items, camera), "append")
            // Erase two items: affected tiles fall back, then re-render.
            items = items.filterIndexed { i, _ -> i != 5 && i != 20 }
            assertSame(reference(items, camera), tiled(tiles, items, camera), "erase")
            settle(tiles, items, camera)
            assertSame(reference(items, camera), tiled(tiles, items, camera), "erase settled")
            // New marker color on top reorders marker groups.
            items = items + Item(
                kind = "HIGHLIGHTER",
                color = Color.YELLOW,
                width = 18f,
                points = List(12) { Pt(60f + it * 10f, 30f + it * 9f) },
            )
            assertSame(reference(items, camera), tiled(tiles, items, camera), "marker order")
            settle(tiles, items, camera)
            assertSame(reference(items, camera), tiled(tiles, items, camera), "marker settled")
            // Dragging a selection: excluded from tiles and drawn on top.
            val selected = items.filter { it.ink != null }.take(4).map { it.id }.toSet()
            val moved =
                items.map {
                    if (it.id in selected) it.copy(transform = it.transform.move(30f, 25f)) else it
                }
            val dragged = moved.filter { it.id in selected }
            val rest = moved.filter { it.id !in selected }
            assertSame(reference(rest, camera, dragged), tiled(tiles, moved, camera, selected), "drag")
            settle(tiles, moved, camera, selected)
            assertSame(reference(rest, camera, dragged), tiled(tiles, moved, camera, selected), "drag settled")
            // Releasing the drag restores document order.
            items = moved
            settle(tiles, items, camera)
            assertSame(reference(items, camera), tiled(tiles, items, camera), "drop")
            // Zoom: once settled, a new level renders at the exact scale.
            camera = Camera(-40f, 10f, 1.75f)
            tiled(tiles, items, camera).recycle()
            SystemClock.sleep(300)
            settle(tiles, items, camera)
            assertSame(reference(items, camera), tiled(tiles, items, camera), "zoom")
        } finally {
            instrumentation.runOnMainSync { tiles.release() }
        }
    }
}

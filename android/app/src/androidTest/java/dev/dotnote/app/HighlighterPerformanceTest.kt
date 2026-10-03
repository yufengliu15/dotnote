package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlighterPerformanceTest {
    @Test
    fun liveOverlapStaysTranslucentAndPenStaysAboveIt() {
        val points = (0..300).map { Pt(20f + it * .5f, 50f) }
        val marker = Item(kind = "HIGHLIGHTER", color = Color.YELLOW, width = 20f, points = points)
        val live = LiveHighlight(marker.color, marker.width)
        points.forEach(live::append)
        val pen =
            Item(
                kind = "LINE",
                color = Color.BLACK,
                width = 4f,
                points = listOf(Pt(90f, 20f), Pt(90f, 90f)),
            )
        val renderer = ObjectRenderer(vectorHighlights = false)
        val bitmap = Bitmap.createBitmap(400, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val matrix = Matrix().apply { setScale(2f, 2f) }
        fun render(items: List<Item>, preview: LiveHighlight?) {
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.concat(matrix)
            renderer.drawScene(canvas, items + pen, matrix, preview)
            canvas.restore()
        }
        try {
            render(List(8) { marker.copy(id = newId()) }, live)
            // Overlap and chunk joins must remain one-third opaque, including live ink.
            for (x in listOf(60, 168, 200, 296, 320)) assertTrue(
                "Opacity at $x",
                Color.blue(bitmap.getPixel(x, 100)) in 168..171,
            )
            assertEquals(Color.BLACK, bitmap.getPixel(180, 100))
            val saved = DocumentCodec.decode(DocumentCodec.encode(Document(listOf(marker))))
            render(saved.items, null)
            assertTrue(Color.blue(bitmap.getPixel(200, 100)) in 168..171)
            assertEquals(Color.BLACK, bitmap.getPixel(180, 100))
            // Dropping a cancelled preview must leave no ink behind.
            render(emptyList(), null)
            assertEquals(Color.WHITE, bitmap.getPixel(200, 100))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun longLiveStrokeOnMarkedPageHasBoundedFrameTime() {
        val existing =
            List(60) { row ->
                Item(
                    kind = "HIGHLIGHTER",
                    color = Color.YELLOW,
                    width = 8f,
                    points = List(100) { x -> Pt(x * 3f, 10f + row * 3f + sin(x.toFloat()) * 2f) },
                )
            }
        val renderer = ObjectRenderer(vectorHighlights = false)
        val live = LiveHighlight(Color.YELLOW, 12f)
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val matrix = Matrix()
        val times = mutableListOf<Double>()
        try {
            repeat(200) { frame ->
                val start = System.nanoTime()
                repeat(12) { sample ->
                    val n = frame * 12 + sample
                    live.append(Pt(20f + n % 280, 120f + sin(n * .025f) * 65f))
                }
                canvas.drawColor(Color.WHITE)
                renderer.drawScene(canvas, existing, matrix, live)
                times.add((System.nanoTime() - start) / 1_000_000.0)
            }
            val sorted = times.drop(5).sorted()
            val p95 = sorted[(sorted.size * .95).toInt()]
            InstrumentationRegistry.getInstrumentation()
                .sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Highlighter: 2,400 live points / 60 existing strokes; " +
                                "software frame median=${sorted[sorted.size / 2]} ms, p95=$p95 ms\n",
                        )
                    },
                )
            // A generous emulator budget catches geometric-union stalls without claiming
            // that software bitmap timings establish S Pen/physical GPU latency.
            assertTrue("Highlighter frame p95 was $p95 ms", p95 < 100)
        } finally {
            bitmap.recycle()
        }
    }
}

package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VectorPerformanceTest {
    @Test
    fun denseSceneReusesMeshesAcrossRedrawPanAndTransform() {
        val inputs =
            MutableStrokeInputBatch().apply {
                repeat(40) { n ->
                    add(StrokeInput().apply { update(4f + n * .5f, 4f + n % 4, n * 8L) })
                }
            }
        val prototype = strokeItem(inputs, brush(Color.BLACK, 2f, false), false)
        val items =
            List(900) { n ->
                prototype.copy(
                    id = "pen-$n",
                    transform = Transform(tx = (n % 30) * 24f, ty = (n / 30) * 10f),
                )
            }
        val renderer = ObjectRenderer(vectorHighlights = false)
        val bitmap = Bitmap.createBitmap(760, 340, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val matrix = Matrix()
        fun render(scene: List<Item>): Double {
            val start = System.nanoTime()
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.concat(matrix)
            renderer.drawScene(canvas, scene, matrix)
            canvas.restore()
            return (System.nanoTime() - start) / 1_000_000.0
        }
        try {
            val cold = render(items)
            assertEquals(900, renderer.strokeBuildCount)
            val first = Bitmap.createBitmap(bitmap)
            val warm = List(5) { render(items.toList()) }.sorted()
            assertEquals(
                "Redrawing more than 400 strokes must not reconstruct meshes",
                900,
                renderer.strokeBuildCount,
            )
            assertTrue("Cached drawing must preserve pixels", first.sameAs(bitmap))
            first.recycle()
            matrix.setScale(1.2f, 1.2f)
            render(items.drop(100) + items.take(100))
            render(items.map { it.copy(transform = it.transform.move(1f, 2f)) })
            assertEquals(
                "Camera and object transforms reuse native geometry",
                900,
                renderer.strokeBuildCount,
            )
            render(items.dropLast(1) + items.last().copy(color = Color.RED))
            assertEquals(
                "Only the recolored stroke should be rebuilt",
                901,
                renderer.strokeBuildCount,
            )
            val changedInput =
                prototype.copy(
                    id = items.last().id,
                    color = Color.RED,
                    ink =
                        strokeItem(
                                MutableStrokeInputBatch().apply {
                                    add(StrokeInput().apply { update(3f, 20f, 0L) })
                                    add(StrokeInput().apply { update(15f, 20f, 8L) })
                                },
                                brush(Color.BLACK, 2f, false),
                                false,
                            )
                            .ink,
                )
            render(items.dropLast(1) + changedInput)
            assertEquals(902, renderer.strokeBuildCount)
            InstrumentationRegistry.getInstrumentation()
                .sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Vectors: 900 strokes / 36,000 input points; cold=$cold ms, warm median=${warm[2]} ms; zero repeated mesh builds\n",
                        )
                    },
                )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun nativeHandoffVisibilityChangesWithoutReplacingScene() {
        val item =
            Item(
                kind = "GRID",
                color = Color.BLACK,
                width = 4f,
                points = listOf(Pt(10f, 10f), Pt(90f, 90f)),
            )
        val scene = listOf(item)
        val renderer = ObjectRenderer(vectorHighlights = false)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            fun draw(hidden: Set<String>) {
                canvas.drawColor(Color.WHITE)
                renderer.drawScene(canvas, scene, Matrix(), hiddenIds = hidden)
            }
            draw(emptySet())
            assertEquals(Color.BLACK, bitmap.getPixel(10, 50))
            draw(setOf(item.id))
            assertEquals(Color.WHITE, bitmap.getPixel(10, 50))
            draw(emptySet())
            assertEquals(Color.BLACK, bitmap.getPixel(10, 50))
            val moved = item.copy(transform = Transform(tx = 5f))
            canvas.drawColor(Color.WHITE)
            renderer.drawScene(canvas, listOf(moved), Matrix())
            assertEquals(Color.WHITE, bitmap.getPixel(10, 50))
            assertEquals(Color.BLACK, bitmap.getPixel(15, 50))
        } finally {
            bitmap.recycle()
        }
    }
}

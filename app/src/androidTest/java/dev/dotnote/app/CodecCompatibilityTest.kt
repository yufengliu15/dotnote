package dev.dotnote.app

import android.graphics.Color
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The direct encoder must write exactly the bytes Android's org.json wrote before 0.10.0. */
@RunWith(AndroidJUnit4::class)
class CodecCompatibilityTest {
    private fun legacyItem(i: Item): JSONObject =
        JSONObject()
            .put("id", i.id)
            .put("kind", i.kind)
            .put("color", i.color)
            .put("width", i.width)
            .put("points", JSONArray(i.points.map { JSONArray(listOf(it.x, it.y)) }))
            .put("ink", i.ink)
            .put(
                "transform",
                JSONArray(listOf(i.transform.sx, i.transform.sy, i.transform.tx, i.transform.ty)),
            )
            .put("rows", i.rows)
            .put("cols", i.cols)
            .put("asset", i.asset)
            .put("page", i.page)
            .apply {
                if (i.image) put("image", true)
                if (i.kind == "TEXT") {
                    put("text", i.text)
                    put("fontSize", i.fontSize)
                }
            }

    private fun legacy(doc: Document): String =
        JSONObject()
            .put("version", 1)
            .put("dots", doc.dots)
            .put("camera", JSONArray(listOf(doc.camera.x, doc.camera.y, doc.camera.zoom)))
            .put("items", JSONArray(doc.items.map(::legacyItem)))
            .toString()

    @Test
    fun encoderMatchesLegacyBytesAndDecoderRoundTrips() {
        val random = Random(7)
        val inputs =
            MutableStrokeInputBatch().apply {
                repeat(50) {
                    add(
                        StrokeInput().apply {
                            update(random.nextFloat() * 900, random.nextFloat() * 300, it * 5L)
                        }
                    )
                }
            }
        val ink = strokeItem(inputs, brush(Color.BLUE, 2.7f, false), false)
        val items =
            listOf(
                ink,
                ink.copy(id = newId(), transform = Transform(1.5f, 1.5f, -3.25f, 1e-7f)),
                Item(kind = "HIGHLIGHTER", color = Color.YELLOW, width = 14f, points = List(20) { Pt(it * 3.3f, -0f) }),
                Item(kind = "GRID", points = listOf(Pt(1f, 2f), Pt(300.125f, 4e10f)), rows = 7, cols = 2),
                Item(kind = "PDF", asset = "0123abcd-ef.pdf", page = 3, image = true, points = listOf(Pt(0f, 0f), Pt(800f, 600f))),
                textItem("Quote \" slash / back \\ tab\t newline\n ctrl \u0001 ünï 😀", 18.5f, Color.RED, Pt(10f, 20f), null),
            )
        val doc = Document(items, Camera(-12.5f, 1e-3f, 0.333f), dots = false)
        assertEquals(legacy(doc), DocumentCodec.encode(doc))
        assertEquals(doc, DocumentCodec.decode(DocumentCodec.encode(doc)))
        assertEquals(doc, DocumentCodec.decode(legacy(doc)))
        // Files written by org.json after a parse/serialize cycle are read identically.
        assertEquals(doc, DocumentCodec.decode(JSONObject(legacy(doc)).toString(2)))
        repeat(200) {
            val f = Float.fromBits(random.nextInt()).takeIf { it.isFinite() } ?: 1f
            val d = Document(listOf(Item(kind = "LINE", points = listOf(Pt(f, -f), Pt(0f, f)))))
            assertEquals(legacy(d), DocumentCodec.encode(d))
            assertEquals(d, DocumentCodec.decode(DocumentCodec.encode(d)))
        }
    }
}

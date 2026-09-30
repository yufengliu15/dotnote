package dev.dotnote.app

import java.util.UUID
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

fun newId(): String = UUID.randomUUID().toString()

data class Pt(val x: Float, val y: Float)

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width
        get() = right - left

    val height
        get() = bottom - top

    fun contains(p: Pt) = p.x in left..right && p.y in top..bottom

    fun intersects(b: Bounds) =
        left <= b.right && right >= b.left && top <= b.bottom && bottom >= b.top

    fun outset(d: Float) = Bounds(left - d, top - d, right + d, bottom + d)

    fun union(b: Bounds) =
        Bounds(min(left, b.left), min(top, b.top), max(right, b.right), max(bottom, b.bottom))

    companion object {
        fun of(points: List<Pt>): Bounds =
            if (points.isEmpty()) Bounds(0f, 0f, 0f, 0f)
            else
                Bounds(
                    points.minOf { it.x },
                    points.minOf { it.y },
                    points.maxOf { it.x },
                    points.maxOf { it.y },
                )
    }
}

data class Transform(
    val sx: Float = 1f,
    val sy: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun map(p: Pt) = Pt(p.x * sx + tx, p.y * sy + ty)

    fun map(b: Bounds): Bounds =
        Bounds.of(listOf(map(Pt(b.left, b.top)), map(Pt(b.right, b.bottom))))

    fun move(dx: Float, dy: Float) = copy(tx = tx + dx, ty = ty + dy)

    fun resize(origin: Pt, fx: Float, fy: Float) =
        Transform(
            sx * fx,
            sy * fy,
            origin.x + (tx - origin.x) * fx,
            origin.y + (ty - origin.y) * fy,
        )
}

enum class Tool(val label: String) {
    PEN("Pen"),
    HIGHLIGHTER("Highlighter"),
    ERASER("Eraser"),
    LASSO("Select"),
    TEXT("Text"),
    LINE("Line"),
    ARROW("Arrow"),
    RECTANGLE("Rectangle"),
    SQUARE("Square"),
    ELLIPSE("Ellipse"),
    CIRCLE("Circle"),
    GRID("Grid"),
}

data class Item(
    val id: String = newId(),
    val kind: String,
    val color: Int = 0xff25342e.toInt(),
    val width: Float = 3f,
    val points: List<Pt> = emptyList(),
    val ink: String? = null,
    val transform: Transform = Transform(),
    val rows: Int = 3,
    val cols: Int = 3,
    val asset: String? = null,
    val page: Int = 0,
    val image: Boolean = false,
    val text: String? = null,
    val fontSize: Float = 24f,
) {
    val bounds: Bounds =
        transform
            .map(Bounds.of(points))
            .outset(
                if (kind == "PDF" || kind == "TEXT") 0f else width * max(transform.sx, transform.sy)
            )
    val locked
        get() = kind == "PDF" && !image
}

data class Camera(val x: Float = 40f, val y: Float = 40f, val zoom: Float = 1f) {
    fun world(p: Pt) = Pt((p.x - x) / zoom, (p.y - y) / zoom)

    fun zoomAt(focus: Pt, factor: Float): Camera {
        val z = (zoom * factor).coerceIn(.08f, 8f)
        val w = world(focus)
        return Camera(focus.x - w.x * z, focus.y - w.y * z, z)
    }
}

data class Document(
    val items: List<Item> = emptyList(),
    val camera: Camera = Camera(),
    val dots: Boolean = true,
) {
    val bounds: Bounds?
        get() = items.map { it.bounds }.reduceOrNull { a, b -> a.union(b) }
}

/** Snapshot history shares immutable item data; a gesture is one undo operation. */
class History {
    private val past = ArrayDeque<List<Item>>()
    private val future = ArrayDeque<List<Item>>()
    val canUndo
        get() = past.isNotEmpty()

    val canRedo
        get() = future.isNotEmpty()

    fun push(items: List<Item>) {
        past.addLast(items)
        if (past.size > 80) past.removeFirst()
        future.clear()
    }

    fun undo(current: List<Item>): List<Item>? {
        if (past.isEmpty()) return null
        future.addLast(current)
        return past.removeLast()
    }

    fun redo(current: List<Item>): List<Item>? {
        if (future.isEmpty()) return null
        past.addLast(current)
        return future.removeLast()
    }
}

fun segmentDistance(p: Pt, a: Pt, b: Pt): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val t =
        if (dx * dx + dy * dy == 0f) 0f
        else (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}

fun insidePolygon(p: Pt, polygon: List<Pt>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x)
            inside = !inside
        j = i
    }
    return inside
}

fun shapeSegments(item: Item): List<Pair<Pt, Pt>> {
    if (item.points.size < 2) return emptyList()
    val a = item.points.first()
    val b = item.points.last()
    val r = Bounds.of(item.points)
    val raw =
        when (item.kind) {
            "RECTANGLE",
            "SQUARE",
            "GRID" ->
                buildList {
                    val rows = if (item.kind == "GRID") item.rows else 1
                    val cols = if (item.kind == "GRID") item.cols else 1
                    for (i in 0..rows) {
                        val y = r.top + r.height * i / rows
                        add(Pt(r.left, y) to Pt(r.right, y))
                    }
                    for (i in 0..cols) {
                        val x = r.left + r.width * i / cols
                        add(Pt(x, r.top) to Pt(x, r.bottom))
                    }
                }
            "CIRCLE",
            "ELLIPSE" ->
                (0 until 64).map { i ->
                    fun at(n: Int) =
                        Pt(
                            (r.left + r.right) / 2 + r.width / 2 * cos(n * 2 * PI / 64).toFloat(),
                            (r.top + r.bottom) / 2 + r.height / 2 * sin(n * 2 * PI / 64).toFloat(),
                        )
                    at(i) to at(i + 1)
                }
            "ARROW" -> {
                val angle = atan2(b.y - a.y, b.x - a.x)
                val length = max(12f, item.width * 4)
                listOf(
                    a to b,
                    b to Pt(b.x - length * cos(angle - .5f), b.y - length * sin(angle - .5f)),
                    b to Pt(b.x - length * cos(angle + .5f), b.y - length * sin(angle + .5f)),
                )
            }
            else -> listOf(a to b)
        }
    return raw.map { item.transform.map(it.first) to item.transform.map(it.second) }
}

fun hitItem(item: Item, p: Pt, radius: Float): Boolean {
    if (item.locked || !item.bounds.outset(radius).contains(p)) return false
    if (item.image || item.kind == "TEXT") return true
    val tolerance = radius + item.width * max(item.transform.sx, item.transform.sy) / 2
    val pts = item.points.map(item.transform::map)
    if (pts.size == 1) return hypot(p.x - pts[0].x, p.y - pts[0].y) <= tolerance
    val lines = if (item.ink != null) pts.zipWithNext() else shapeSegments(item)
    return lines.any { segmentDistance(p, it.first, it.second) <= tolerance }
}

fun lassoHits(item: Item, polygon: List<Pt>): Boolean {
    if (item.locked || polygon.size < 3) return false
    if (!item.image && item.kind != "TEXT")
        return item.points.any { insidePolygon(item.transform.map(it), polygon) }
    val b = item.bounds
    return polygon.any(b::contains) ||
        listOf(Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom))
            .any { insidePolygon(it, polygon) }
}

object DocumentCodec {
    fun encode(doc: Document): String =
        JSONObject()
            .put("version", 1)
            .put("dots", doc.dots)
            .put("camera", JSONArray(listOf(doc.camera.x, doc.camera.y, doc.camera.zoom)))
            .put("items", JSONArray(doc.items.map(::itemJson)))
            .toString()

    fun itemJson(i: Item): JSONObject =
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

    fun decode(text: String): Document {
        val o = JSONObject(text)
        require(o.getInt("version") == 1) { "Unsupported note version" }
        val c = o.getJSONArray("camera")
        val a = o.getJSONArray("items")
        val items =
            (0 until a.length()).map { n ->
                val i = a.getJSONObject(n)
                val t = i.getJSONArray("transform")
                val pts = i.getJSONArray("points")
                val kind = i.getString("kind")
                require(kind == "PDF" || kind == "HAND" || Tool.entries.any { it.name == kind }) {
                    "Unknown object type"
                }
                val asset = if (i.has("asset")) i.getString("asset") else null
                require(asset == null || asset.matches(Regex("[a-f0-9-]+\\.pdf"))) {
                    "Invalid attachment name"
                }
                val width = i.getDouble("width").toFloat()
                val image = i.optBoolean("image", false)
                val content = if (kind == "TEXT") i.getString("text") else null
                val fontSize = i.optDouble("fontSize", 24.0).toFloat()
                if (kind == "TEXT") {
                    require(content != null && content.isNotBlank() && content.length <= 10000) {
                        "Invalid text content"
                    }
                    require(fontSize.isFinite() && fontSize in 8f..144f && pts.length() == 2) {
                        "Invalid text dimensions"
                    }
                }
                require(!image || (kind == "PDF" && asset != null)) { "Invalid image attachment" }
                require(width.isFinite() && width in .1f..100f)
                val transform = Transform(t.finite(0), t.finite(1), t.finite(2), t.finite(3))
                require(transform.sx > 0 && transform.sy > 0)
                Item(
                    id = i.getString("id"),
                    kind = kind,
                    color = i.getInt("color"),
                    width = width,
                    points =
                        (0 until pts.length()).map {
                            val p = pts.getJSONArray(it)
                            Pt(p.finite(0), p.finite(1))
                        },
                    ink = if (i.has("ink")) i.getString("ink") else null,
                    transform = transform,
                    rows = i.getInt("rows").coerceIn(1, 30),
                    cols = i.getInt("cols").coerceIn(1, 30),
                    asset = asset,
                    page = i.getInt("page").also { require(it >= 0) },
                    image = image,
                    text = content,
                    fontSize = fontSize,
                )
            }
        require(items.map { it.id }.distinct().size == items.size) { "Duplicate object IDs" }
        return Document(
            items,
            Camera(c.finite(0), c.finite(1), c.finite(2).coerceIn(.08f, 8f)),
            o.getBoolean("dots"),
        )
    }

    private fun JSONArray.finite(index: Int) =
        getDouble(index).toFloat().also { require(it.isFinite()) }
}

fun canMoveFolder(id: String, target: String?, parents: Map<String, String?>): Boolean {
    var cursor = target
    val visited = mutableSetOf<String>()
    while (cursor != null) {
        if (cursor == id || !visited.add(cursor) || !parents.containsKey(cursor)) return false
        cursor = parents[cursor]
    }
    return true
}

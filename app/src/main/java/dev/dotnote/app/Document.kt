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
        fun of(points: List<Pt>): Bounds {
            if (points.isEmpty()) return Bounds(0f, 0f, 0f, 0f)
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            var nan = false
            for (index in points.indices) {
                val p = points[index]
                if (p.x.isNaN() || p.y.isNaN()) nan = true
                if (p.x < left) left = p.x
                if (p.x > right) right = p.x
                if (p.y < top) top = p.y
                if (p.y > bottom) bottom = p.y
            }
            // NaN coordinates keep the previous minOf/maxOf behavior.
            if (nan)
                return Bounds(
                    points.minOf { it.x },
                    points.minOf { it.y },
                    points.maxOf { it.x },
                    points.maxOf { it.y },
                )
            return Bounds(left, top, right, bottom)
        }
    }
}

data class Transform(
    val sx: Float = 1f,
    val sy: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun map(p: Pt) = Pt(p.x * sx + tx, p.y * sy + ty)

    fun map(b: Bounds): Bounds {
        val l = b.left * sx + tx
        val t = b.top * sy + ty
        val r = b.right * sx + tx
        val bt = b.bottom * sy + ty
        return Bounds(min(l, r), min(t, bt), max(l, r), max(t, bt))
    }

    fun move(dx: Float, dy: Float) = copy(tx = tx + dx, ty = ty + dy)

    fun resize(origin: Pt, fx: Float, fy: Float) =
        Transform(
            sx * fx,
            sy * fy,
            origin.x + (tx - origin.x) * fx,
            origin.y + (ty - origin.y) * fy,
        )
}

enum class SelectionSizing {
    RESIZE,
    SCALE,
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

    /** Cached JSON for this immutable item; copies start without it. */
    @Volatile @Transient internal var encoded: String? = null
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

fun segmentDistance(p: Pt, a: Pt, b: Pt): Float = segmentDistance(p.x, p.y, a.x, a.y, b.x, b.y)

internal fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val t =
        if (dx * dx + dy * dy == 0f) 0f
        else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    return hypot(px - ax - t * dx, py - ay - t * dy)
}

fun insidePolygon(p: Pt, polygon: List<Pt>): Boolean = insidePolygon(p.x, p.y, polygon)

internal fun insidePolygon(x: Float, y: Float, polygon: List<Pt>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x)
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
    if (item.locked) return false
    val b = item.bounds
    if (!(p.x >= b.left - radius && p.x <= b.right + radius && p.y >= b.top - radius && p.y <= b.bottom + radius))
        return false
    if (item.image || item.kind == "TEXT") return true
    val t = item.transform
    val tolerance = radius + item.width * max(t.sx, t.sy) / 2
    val points = item.points
    if (points.size == 1) {
        val q = points[0]
        return hypot(p.x - (q.x * t.sx + t.tx), p.y - (q.y * t.sy + t.ty)) <= tolerance
    }
    if (item.ink == null) return shapeSegments(item).any { segmentDistance(p, it.first, it.second) <= tolerance }
    // Ink strokes: test each segment without allocating mapped points.
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val c = points[i]
        if (
            segmentDistance(
                p.x,
                p.y,
                a.x * t.sx + t.tx,
                a.y * t.sy + t.ty,
                c.x * t.sx + t.tx,
                c.y * t.sy + t.ty,
            ) <= tolerance
        )
            return true
    }
    return false
}

fun lassoHits(item: Item, polygon: List<Pt>): Boolean {
    if (item.locked || polygon.size < 3) return false
    if (!item.image && item.kind != "TEXT") {
        val area = Bounds.of(polygon)
        if (!item.bounds.intersects(area)) return false
        val t = item.transform
        for (point in item.points) {
            val x = point.x * t.sx + t.tx
            val y = point.y * t.sy + t.ty
            if (x < area.left || x > area.right || y < area.top || y > area.bottom) continue
            if (insidePolygon(x, y, polygon)) return true
        }
        return false
    }
    val b = item.bounds
    return polygon.any(b::contains) ||
        listOf(Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom))
            .any { insidePolygon(it, polygon) }
}

object DocumentCodec {
    private val kinds = (Tool.entries.map { it.name } + listOf("PDF", "HAND")).toHashSet()
    private val assetPattern = Regex("[a-f0-9-]+\\.pdf")

    /**
     * Byte-for-byte the same text Android's org.json produced, built directly. Each immutable item
     * keeps its encoded form, so saving after one stroke only formats that stroke.
     */
    /** Streams the same text as [encode] without building the whole document in memory. */
    fun encodeTo(doc: Document, out: Appendable) {
        val head = StringBuilder(96)
        head.append("{\"version\":1,\"dots\":").append(doc.dots).append(",\"camera\":[")
        JsonText.number(head, doc.camera.x)
        head.append(',')
        JsonText.number(head, doc.camera.y)
        head.append(',')
        JsonText.number(head, doc.camera.zoom)
        head.append("],\"items\":[")
        out.append(head)
        doc.items.forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            out.append(itemText(item))
        }
        out.append("]}")
    }

    fun encode(doc: Document): String {
        var size = 96
        val parts = arrayOfNulls<String>(doc.items.size)
        doc.items.forEachIndexed { index, item ->
            val text = itemText(item)
            parts[index] = text
            size += text.length + 1
        }
        val out = StringBuilder(size)
        out.append("{\"version\":1,\"dots\":").append(doc.dots).append(",\"camera\":[")
        JsonText.number(out, doc.camera.x)
        out.append(',')
        JsonText.number(out, doc.camera.y)
        out.append(',')
        JsonText.number(out, doc.camera.zoom)
        out.append("],\"items\":[")
        parts.forEachIndexed { index, text ->
            if (index > 0) out.append(',')
            out.append(text)
        }
        return out.append("]}").toString()
    }

    internal fun itemText(i: Item): String {
        i.encoded?.let {
            return it
        }
        val out = StringBuilder(64 + i.points.size * 20 + (i.ink?.length ?: 0) * 21 / 20)
        out.append("{\"id\":")
        JsonText.string(out, i.id)
        out.append(",\"kind\":")
        JsonText.string(out, i.kind)
        out.append(",\"color\":").append(i.color).append(",\"width\":")
        JsonText.number(out, i.width)
        out.append(",\"points\":[")
        i.points.forEachIndexed { index, p ->
            if (index > 0) out.append(',')
            out.append('[')
            JsonText.number(out, p.x)
            out.append(',')
            JsonText.number(out, p.y)
            out.append(']')
        }
        out.append(']')
        i.ink?.let {
            out.append(",\"ink\":")
            JsonText.string(out, it)
        }
        out.append(",\"transform\":[")
        JsonText.number(out, i.transform.sx)
        out.append(',')
        JsonText.number(out, i.transform.sy)
        out.append(',')
        JsonText.number(out, i.transform.tx)
        out.append(',')
        JsonText.number(out, i.transform.ty)
        out.append("],\"rows\":").append(i.rows).append(",\"cols\":").append(i.cols)
        i.asset?.let {
            out.append(",\"asset\":")
            JsonText.string(out, it)
        }
        out.append(",\"page\":").append(i.page)
        if (i.image) out.append(",\"image\":true")
        if (i.kind == "TEXT") {
            i.text?.let {
                out.append(",\"text\":")
                JsonText.string(out, it)
            }
            out.append(",\"fontSize\":")
            JsonText.number(out, i.fontSize)
        }
        return out.append('}').toString().also { i.encoded = it }
    }

    fun itemJson(i: Item): JSONObject = JSONObject(itemText(i))

    fun decode(text: String): Document = parse(text, true) {}!!

    internal fun decode(o: JSONObject): Document = read(o, true) {}

    /** Startup checks the same fields without allocating a renderable scene for every note. */
    internal fun validate(o: JSONObject, onAsset: (String) -> Unit = {}) {
        read(o, false, onAsset)
    }

    /** Validates document text without building items; reports each attachment. */
    internal fun validate(text: String, onAsset: (String) -> Unit = {}) {
        parse(text, false, onAsset)
    }

    private class ItemFields {
        var id: String? = null
        var kind: String? = null
        var color: Int? = null
        var width = Float.NaN
        var hasWidth = false
        var points: ArrayList<Pt>? = null
        var pointCount = -1
        var ink: String? = null
        var transform: FloatArray? = null
        var rows: Int? = null
        var cols: Int? = null
        var asset: String? = null
        var page: Int? = null
        var image = false
        var text: String? = null
        var fontSize = 24f
    }

    /** One streaming pass with the same validation as [read]. */
    internal fun parse(text: String, materialize: Boolean, onAsset: (String) -> Unit): Document? {
        val json = JsonCursor(text)
        var version: Int? = null
        var dots: Boolean? = null
        var camera: FloatArray? = null
        var items: ArrayList<Item>? = null
        val ids = HashSet<String>()
        json.beginObject()
        var first = true
        while (true) {
            val key = json.nextKey(first) ?: break
            first = false
            when (key) {
                "version" -> version = json.int()
                "dots" -> dots = json.boolean()
                "camera" -> camera = floats(json, 3)
                "items" -> {
                    val list = ArrayList<Item>()
                    json.beginArray()
                    var firstItem = true
                    while (json.hasNext(firstItem)) {
                        firstItem = false
                        item(json, materialize, ids, onAsset)?.let(list::add)
                    }
                    items = list
                }
                else -> json.skip()
            }
        }
        json.end()
        require(version == 1) { "Unsupported note version" }
        val c = requireNotNull(camera) { "Missing camera" }
        val parsed = requireNotNull(items) { "Missing items" }
        val showDots = requireNotNull(dots) { "Missing dots" }
        if (!materialize) return null
        return Document(parsed, Camera(c[0], c[1], c[2].coerceIn(.08f, 8f)), showDots)
    }

    private fun floats(json: JsonCursor, minimum: Int): FloatArray {
        val values = FloatArray(4)
        var count = 0
        json.beginArray()
        while (json.hasNext(count == 0)) {
            val value = json.finite()
            if (count < 4) values[count] = value
            count++
        }
        require(count >= minimum) { "Missing coordinates" }
        return values
    }

    private fun item(
        json: JsonCursor,
        materialize: Boolean,
        ids: HashSet<String>,
        onAsset: (String) -> Unit,
    ): Item? {
        val f = ItemFields()
        json.beginObject()
        var first = true
        while (true) {
            val key = json.nextKey(first) ?: break
            first = false
            when (key) {
                "id" -> f.id = json.string()
                "kind" -> f.kind = json.string()
                "color" -> f.color = json.int()
                "width" -> {
                    f.width = json.number().toFloat()
                    f.hasWidth = true
                }
                "points" -> {
                    val list = if (materialize) ArrayList<Pt>() else null
                    var count = 0
                    json.beginArray()
                    while (json.hasNext(count == 0)) {
                        // [x, y, ...]: two finite numbers; extra elements are ignored.
                        json.beginArray()
                        if (!json.hasNext(true)) json.fail("Missing coordinates")
                        val x = json.finite()
                        if (!json.hasNext(false)) json.fail("Missing coordinates")
                        val y = json.finite()
                        while (json.hasNext(false)) json.finite()
                        list?.add(Pt(x, y))
                        count++
                    }
                    f.points = list
                    f.pointCount = count
                }
                "ink" ->
                    if (json.isNull()) json.fail("Invalid ink")
                    else if (materialize) f.ink = json.string()
                    else {
                        json.skipString()
                        f.ink = ""
                    }
                "transform" -> f.transform = floats(json, 4)
                "rows" -> f.rows = json.int()
                "cols" -> f.cols = json.int()
                "asset" -> f.asset = if (json.isNull()) "null" else json.string()
                "page" -> f.page = json.int()
                "image" -> f.image = if (json.isNull()) false else json.boolean()
                "text" -> f.text = if (json.isNull()) null else json.string()
                "fontSize" -> f.fontSize = if (json.isNull()) 24f else json.number().toFloat()
                else -> json.skip()
            }
        }
        val transform = requireNotNull(f.transform) { "Missing transform" }
        require(f.pointCount >= 0) { "Missing points" }
        val kind = requireNotNull(f.kind) { "Missing object type" }
        require(kind in kinds) { "Unknown object type" }
        val asset = f.asset
        require(asset == null || assetPattern.matches(asset)) { "Invalid attachment name" }
        require(f.hasWidth) { "Missing width" }
        val width = f.width
        val content = if (kind == "TEXT") requireNotNull(f.text) { "Invalid text content" } else null
        if (kind == "TEXT") {
            require(content != null && content.isNotBlank() && content.length <= 10000) {
                "Invalid text content"
            }
            require(f.fontSize.isFinite() && f.fontSize in 8f..144f && f.pointCount == 2) {
                "Invalid text dimensions"
            }
        }
        require(!f.image || (kind == "PDF" && asset != null)) { "Invalid image attachment" }
        require(width.isFinite() && width in .1f..100f)
        require(transform[0] > 0 && transform[1] > 0)
        val id = requireNotNull(f.id) { "Missing object ID" }
        require(ids.add(id)) { "Duplicate object IDs" }
        val color = requireNotNull(f.color) { "Missing color" }
        val rows = requireNotNull(f.rows) { "Missing rows" }.coerceIn(1, 30)
        val cols = requireNotNull(f.cols) { "Missing columns" }.coerceIn(1, 30)
        val page = requireNotNull(f.page) { "Missing page" }.also { require(it >= 0) }
        asset?.let(onAsset)
        if (!materialize) return null
        return Item(
            id = id,
            kind = kind,
            color = color,
            width = width,
            points = requireNotNull(f.points),
            ink = f.ink,
            transform = Transform(transform[0], transform[1], transform[2], transform[3]),
            rows = rows,
            cols = cols,
            asset = asset,
            page = page,
            image = f.image,
            text = content,
            fontSize = f.fontSize,
        )
    }

    private fun read(o: JSONObject, materialize: Boolean, onAsset: (String) -> Unit): Document {
        require(o.getInt("version") == 1) { "Unsupported note version" }
        val c = o.getJSONArray("camera")
        val a = o.getJSONArray("items")
        val ids = HashSet<String>()
        val items =
            (0 until a.length()).mapNotNull { n ->
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
                val sx = t.finite(0)
                val sy = t.finite(1)
                val tx = t.finite(2)
                val ty = t.finite(3)
                require(sx > 0 && sy > 0)
                val id = i.getString("id")
                require(ids.add(id)) { "Duplicate object IDs" }
                val color = i.getInt("color")
                val points = if (materialize) ArrayList<Pt>(pts.length()) else null
                repeat(pts.length()) { index ->
                    val point = pts.getJSONArray(index)
                    val x = point.finite(0)
                    val y = point.finite(1)
                    points?.add(Pt(x, y))
                }
                val ink = if (i.has("ink")) i.getString("ink") else null
                val rows = i.getInt("rows").coerceIn(1, 30)
                val cols = i.getInt("cols").coerceIn(1, 30)
                val page = i.getInt("page").also { require(it >= 0) }
                asset?.let(onAsset)
                if (!materialize) return@mapNotNull null
                Item(
                    id = id,
                    kind = kind,
                    color = color,
                    width = width,
                    points = requireNotNull(points),
                    ink = ink,
                    transform = Transform(sx, sy, tx, ty),
                    rows = rows,
                    cols = cols,
                    asset = asset,
                    page = page,
                    image = image,
                    text = content,
                    fontSize = fontSize,
                )
            }
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

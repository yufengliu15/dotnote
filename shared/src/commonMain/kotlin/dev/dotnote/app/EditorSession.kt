package dev.dotnote.app

import kotlin.math.*

/** Platform-neutral editor. UI hosts own input capture, rendering and durable IO. */
class EditorSession {
    var document = Document()
        private set
    private var history = History()
    private val index = SceneIndex()
    fun visible(viewport: Bounds): VisibleScene = index.visible(document.items, viewport)
    var selection: Set<String> = emptySet()
        private set
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    @Throws(IllegalArgumentException::class)
    fun load(json: String) {
        val parsed = DocumentCodec.decode(json)
        document = parsed
        history = History()
        selection = emptySet()
    }
    fun encode(): String = DocumentCodec.encode(document)
    fun commit(items: List<Item>) {
        if (items == document.items) return
        history.push(document.items)
        document = document.copy(items = items)
        selection = selection.intersect(items.map { it.id }.toSet())
    }
    fun undo() { history.undo(document.items)?.let { document = document.copy(items = it) }; selection = emptySet() }
    fun redo() { history.redo(document.items)?.let { document = document.copy(items = it) }; selection = emptySet() }
    fun camera(x: Float, y: Float, zoom: Float) {
        if (x.isFinite() && y.isFinite() && zoom.isFinite()) document = document.copy(camera = Camera(x, y, zoom.coerceIn(.08f, 8f)))
    }
    fun fit(width: Float, height: Float) { document = document.copy(camera = fittedCamera(document.bounds, width, height)) }
    fun toggleDots() { document = document.copy(dots = !document.dots) }
    fun stroke(points: List<Pt>, pressures: List<Float>, color: Int, width: Float, highlight: Boolean) {
        if (points.isEmpty()) return
        commit(document.items + Item(kind = if (highlight) "HIGHLIGHTER" else "PEN", color = color,
            width = width.coerceIn(.1f, 100f), points = points, pressures = pressures))
    }
    fun shape(kind: String, start: Pt, end: Pt, color: Int, width: Float) {
        if (kind !in setOf("LINE", "ARROW", "RECTANGLE", "SQUARE", "ELLIPSE", "CIRCLE", "GRID")) return
        var b = end
        if (kind == "SQUARE" || kind == "CIRCLE") {
            val size = max(abs(end.x - start.x), abs(end.y - start.y))
            b = Pt(start.x + size * if (end.x < start.x) -1 else 1, start.y + size * if (end.y < start.y) -1 else 1)
        }
        commit(document.items + Item(kind = kind, color = color, width = width, points = listOf(start, b)))
    }
    fun text(content: String, at: Pt, color: Int, fontSize: Float, width: Float, height: Float) {
        if (content.isBlank() || content.length > 10000) return
        commit(document.items + Item(kind = "TEXT", color = color, points = listOf(at, Pt(at.x + width, at.y + height)), text = content, fontSize = fontSize.coerceIn(8f, 144f)))
    }
    fun erase(path: List<Pt>, radius: Float) {
        commit(document.items.filterNot { item -> !item.image && path.any { hitItem(item, it, radius) } })
    }
    fun select(path: List<Pt>, radius: Float) {
        selection = if (path.size < 3 || Bounds.of(path).let { it.width + it.height < radius })
            document.items.lastOrNull { hitItem(it, path.firstOrNull() ?: Pt(0f, 0f), radius) }?.let { setOf(it.id) } ?: emptySet()
        else document.items.filter { lassoHits(it, path) }.map { it.id }.toSet()
    }
    fun isSelected(id: String) = id in selection
    fun moveSelection(dx: Float, dy: Float) { commit(document.items.map { if (it.id in selection) it.copy(transform = it.transform.move(dx, dy)) else it }) }
    fun scaleSelection(factor: Float) {
        val bounds = document.items.filter { it.id in selection }.map { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: return
        commit(document.items.map { if (it.id in selection) it.copy(transform = it.transform.resize(Pt(bounds.left, bounds.top), factor.coerceIn(.05f, 20f), factor.coerceIn(.05f, 20f))) else it })
    }
    fun recolor(color: Int) { commit(document.items.map { if (it.id in selection && !it.image) it.copy(color = color) else it }) }
    fun deleteSelection() { commit(document.items.filterNot { it.id in selection }) }
    fun addPages(asset: String, widths: List<Float>, heights: List<Float>, image: Boolean) {
        require(Regex("[a-f0-9-]+\\.pdf").matches(asset))
        require(widths.size == heights.size)
        var y = (document.bounds?.bottom ?: -40f) + 40f
        val pages = widths.indices.map { page ->
            val width = widths[page]; val height = heights[page]
            require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
            Item(kind = "PDF", asset = asset, page = page, image = image,
                points = listOf(Pt(0f, y), Pt(width, y + height))).also { y += height + 40f }
        }
        commit(document.items + pages)
    }
}

/** Swift-friendly entry points keep native UI independent of generated top-level names. */
class Core {
    fun newIdentifier(): String = newId()
    fun emptyDocument(): String = DocumentCodec.encode(Document())
    @Throws(IllegalArgumentException::class)
    fun validate(json: String) { DocumentCodec.validate(json) }
    fun segments(item: Item): List<LineSegment> = shapeSegments(item).map { LineSegment(it.first, it.second) }
}
data class LineSegment(val start: Pt, val end: Pt)

package dev.dotnote.app

import kotlin.math.floor

/** Keep the entire visible working set, plus a bounded LRU of offscreen resources. */
internal class VisibleResourceCache<V>(private val recentLimit: Int = 400) {
    private var visible = emptySet<String>()
    private val active = mutableMapOf<String, V>()
    private val recent = LinkedHashMap<String, V>(16, .75f, true)

    fun retainVisible(ids: Set<String>) {
        visible = ids
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in ids) {
                recent[entry.key] = entry.value
                iterator.remove()
            }
        }
        // Promote before trimming so newly visible resources cannot evict each other.
        ids.forEach { id -> recent.remove(id)?.let { active[id] = it } }
        trim()
    }

    operator fun get(id: String): V? = active[id] ?: recent[id]

    fun put(id: String, value: V) {
        if (id in visible) active[id] = value
        else {
            recent[id] = value
            trim()
        }
    }

    private fun trim() {
        val iterator = recent.entries.iterator()
        while (recent.size > recentLimit && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

internal data class VisibleScene(
    val pages: List<Item>,
    val highlights: List<Item>,
    val foreground: List<Item>,
)

/** Growable primitive int list; avoids boxing in spatial queries. */
internal class IntList(capacity: Int = 8) {
    var data = IntArray(capacity)
        private set

    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(maxOf(8, size * 2))
        data[size++] = value
    }

    fun remove(value: Int): Boolean {
        for (i in 0 until size) if (data[i] == value) {
            System.arraycopy(data, i + 1, data, i, size - i - 1)
            size--
            return true
        }
        return false
    }

    operator fun get(index: Int) = data[index]

    fun clear() {
        size = 0
    }

    fun sort() = data.sort(0, size)
}

/**
 * World-space grid of item positions. Edits update it incrementally: appended strokes and in-place
 * changes (moving a selection, recoloring) touch only the changed items; other edits rebuild it.
 * It is never rebuilt on pan.
 */
internal class SceneIndex {
    private var source: List<Item>? = null
    private val cells = HashMap<Long, IntList>()
    private val oversized = IntList()
    private var lastViewport: Bounds? = null
    private var lastScene = VisibleScene(emptyList(), emptyList(), emptyList())
    private var marks = IntArray(0)
    private var generation = 0
    internal var rebuilds = 0
        private set

    fun sync(items: List<Item>) {
        val old = source
        if (old === items) return
        source = items
        lastViewport = null
        if (old != null && items.size >= old.size && old.isNotEmpty()) {
            var prefix = true
            for (i in old.indices) if (items[i] !== old[i]) {
                prefix = false
                break
            }
            if (prefix) {
                for (i in old.size until items.size) insert(i, items[i])
                return
            }
        }
        if (old != null && items.size == old.size) {
            var changed = 0
            val limit = maxOf(64, items.size / 8)
            for (i in items.indices) if (items[i] !== old[i] && ++changed > limit) break
            if (changed <= limit) {
                for (i in items.indices) if (items[i] !== old[i]) {
                    delete(i, old[i])
                    insert(i, items[i])
                }
                return
            }
        }
        rebuilds++
        cells.clear()
        oversized.clear()
        items.forEachIndexed(::insert)
    }

    private fun insert(index: Int, item: Item) {
        val range = cellRange(item.bounds, 64)
        if (range == null) oversized.add(index)
        else
            for (x in range[0]..range[2]) for (y in range[1]..range[3]) {
                cells.getOrPut(key(x, y)) { IntList(4) }.add(index)
            }
    }

    private fun delete(index: Int, item: Item) {
        val range = cellRange(item.bounds, 64)
        if (range == null) oversized.remove(index)
        else
            for (x in range[0]..range[2]) for (y in range[1]..range[3]) {
                cells[key(x, y)]?.remove(index)
            }
    }

    /** Indices of items whose bounds intersect [area], in document (z) order. */
    fun query(items: List<Item>, area: Bounds, out: IntList) {
        sync(items)
        out.clear()
        val range = cellRange(area, 4096)
        if (range == null) {
            for (i in items.indices) if (items[i].bounds.intersects(area)) out.add(i)
            return
        }
        if (marks.size < items.size) marks = IntArray(maxOf(items.size, marks.size * 2))
        if (++generation == Int.MAX_VALUE) {
            marks.fill(0)
            generation = 1
        }
        val g = generation
        fun visit(list: IntList) {
            for (k in 0 until list.size) {
                val i = list[k]
                if (marks[i] != g) {
                    marks[i] = g
                    if (items[i].bounds.intersects(area)) out.add(i)
                }
            }
        }
        visit(oversized)
        for (x in range[0]..range[2]) for (y in range[1]..range[3]) cells[key(x, y)]?.let(::visit)
        // Cell order must never change compositing or topmost-item order.
        out.sort()
    }

    private val scratch = IntList()

    fun visible(items: List<Item>, viewport: Bounds): VisibleScene {
        sync(items)
        if (lastViewport == viewport) return lastScene
        query(items, viewport, scratch)
        val pages = mutableListOf<Item>()
        val highlights = mutableListOf<Item>()
        val foreground = mutableListOf<Item>()
        for (k in 0 until scratch.size) {
            val item = items[scratch[k]]
            when (item.kind) {
                "PDF" -> pages.add(item)
                "HIGHLIGHTER" -> highlights.add(item)
                else -> foreground.add(item)
            }
        }
        lastViewport = viewport
        return VisibleScene(pages, highlights, foreground).also { lastScene = it }
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    private fun cellRange(bounds: Bounds, limit: Long): IntArray? {
        val coordinates =
            doubleArrayOf(
                floor(bounds.left.toDouble() / 512),
                floor(bounds.top.toDouble() / 512),
                floor(bounds.right.toDouble() / 512),
                floor(bounds.bottom.toDouble() / 512),
            )
        if (coordinates.any { !it.isFinite() || it < Int.MIN_VALUE || it > Int.MAX_VALUE })
            return null
        val left = coordinates[0].toInt()
        val top = coordinates[1].toInt()
        val right = coordinates[2].toInt()
        val bottom = coordinates[3].toInt()
        val width = right.toLong() - left + 1
        val height = bottom.toLong() - top + 1
        if (width <= 0 || height <= 0 || width > limit || height > limit || width * height > limit)
            return null
        return intArrayOf(left, top, right, bottom)
    }
}

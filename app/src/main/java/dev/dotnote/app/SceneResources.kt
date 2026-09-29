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

/** World-space grid, rebuilt only when the immutable item list changes, never on pan. */
internal class SceneIndex {
    private var source: List<Item>? = null
    private val cells = mutableMapOf<Long, MutableList<Int>>()
    private val oversized = mutableListOf<Int>()
    private var lastViewport: Bounds? = null
    private var lastScene = VisibleScene(emptyList(), emptyList(), emptyList())

    fun visible(items: List<Item>, viewport: Bounds): VisibleScene {
        if (source !== items) {
            source = items
            cells.clear()
            oversized.clear()
            lastViewport = null
            items.forEachIndexed { index, item ->
                val range = cellRange(item.bounds, 64)
                if (range == null) oversized.add(index)
                else
                    for (x in range[0]..range[2]) for (y in range[1]..range[3]) {
                        cells.getOrPut(key(x, y)) { mutableListOf() }.add(index)
                    }
            }
        }
        if (lastViewport == viewport) return lastScene
        val range = cellRange(viewport, 4096)
        val candidates =
            if (range == null) items.indices.toList()
            else {
                val found = oversized.toMutableSet()
                for (x in range[0]..range[2]) for (y in range[1]..range[3]) {
                    cells[key(x, y)]?.let(found::addAll)
                }
                // Cell order must never change compositing or topmost-item order.
                found.sorted()
            }
        val pages = mutableListOf<Item>()
        val highlights = mutableListOf<Item>()
        val foreground = mutableListOf<Item>()
        candidates.forEach { index ->
            val item = items[index]
            if (item.bounds.intersects(viewport)) {
                when (item.kind) {
                    "PDF" -> pages.add(item)
                    "HIGHLIGHTER" -> highlights.add(item)
                    else -> foreground.add(item)
                }
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

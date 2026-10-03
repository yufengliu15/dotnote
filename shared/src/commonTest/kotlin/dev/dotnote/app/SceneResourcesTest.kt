package dev.dotnote.app

import kotlin.random.Random
import kotlin.test.*

class SceneResourcesTest {
    @Test
    fun visibleWorkingSetLargerThanLruNeverThrashes() {
        val cache = VisibleResourceCache<Any>()
        val ids = (0 until 1200).map { "stroke-$it" }.toSet()
        cache.retainVisible(ids)
        val resources = ids.associateWith { Any().also { value -> cache.put(it, value) } }
        repeat(3) { ids.forEach { assertSame(resources[it], cache[it]) } }
        // Panning into new objects must not evict the still-visible old objects.
        val next = (100 until 1300).map { "stroke-$it" }.toSet()
        cache.retainVisible(next)
        next.filter { it !in ids }.forEach { cache.put(it, Any()) }
        (ids intersect next).forEach { assertSame(resources[it], cache[it]) }
        cache.retainVisible(ids)
        ids.forEach { assertSame(resources[it], cache[it]) }
    }

    @Test
    fun offscreenResourcesRemainBoundedAndRecentlyUsedSurvive() {
        val cache = VisibleResourceCache<Int>(2)
        cache.retainVisible(setOf("a", "b", "c"))
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        cache.retainVisible(emptySet())
        assertNull(cache["a"])
        assertEquals(2, cache["b"])
        cache.put("d", 4)
        assertNull(cache["c"])
        assertEquals(2, cache["b"])
        assertEquals(4, cache["d"])
    }

    @Test
    fun spatialQueryMatchesLinearCullingAndOrderAcrossZoomLevels() {
        val random = Random(43)
        val items =
            List(10_000) { index ->
                val x = random.nextFloat() * 50_000 - 25_000
                val y = random.nextFloat() * 50_000 - 25_000
                Item(
                    id = "$index",
                    kind = listOf("PEN", "PDF", "HIGHLIGHTER", "GRID")[index % 4],
                    points = listOf(Pt(x, y), Pt(x + 600, y + 900)),
                )
            } + Item(kind = "LINE", points = listOf(Pt(-1e9f, -1e9f), Pt(1e9f, 1e9f)))
        val index = SceneIndex()
        repeat(40) {
            val x = random.nextFloat() * 40_000 - 20_000
            val y = random.nextFloat() * 40_000 - 20_000
            val size = if (it == 0) 100_000f else 100f + random.nextFloat() * 10_000
            assertMatches(index, items, Bounds(x, y, x + size, y + size))
        }
        assertMatches(
            index,
            items,
            Bounds(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE),
        )
    }

    @Test
    fun panningOnlyVisitsNearbyItemsAndLayersShareTheResult() {
        val items =
            List(20_000) { n ->
                val x = (n % 100) * 1024f
                val y = (n / 100) * 1024f
                Item(kind = "LINE", points = listOf(Pt(x, y), Pt(x + 20f, y + 20f)))
            }
        var reads = 0
        val counted =
            object : AbstractList<Item>() {
                override val size
                    get() = items.size

                override fun get(index: Int): Item {
                    reads++
                    return items[index]
                }
            }
        val index = SceneIndex()
        index.visible(counted, Bounds(0f, 0f, 100f, 100f))
        reads = 0
        val area = Bounds(1024f, 1024f, 1124f, 1124f)
        val scene = index.visible(counted, area)
        assertEquals(listOf(items[101]), scene.foreground)
        assertTrue(reads < 20, "Pan read $reads of 20,000 items")
        reads = 0
        assertSame(scene, index.visible(counted, area))
        assertEquals(0, reads, "Other layers must reuse the same query")
    }

    @Test
    fun editsUndoAndViewportChangesInvalidateSharedScene() {
        val a = Item(kind = "LINE", points = listOf(Pt(-512f, 0f), Pt(0f, 0f)))
        val items = listOf(a)
        val index = SceneIndex()
        val area = Bounds(-512f, -10f, 0f, 10f)
        val first = index.visible(items, area)
        assertSame(first, index.visible(items, area))
        val moved = listOf(a.copy(transform = Transform(tx = 2000f)))
        assertTrue(index.visible(moved, area).foreground.isEmpty())
        assertMatches(index, moved, Bounds(1480f, -10f, 2010f, 10f))
        assertEquals(listOf(a), index.visible(items, area).foreground)
        val appended = items + a.copy(id = "new", color = 12)
        assertMatches(index, appended, area)
        assertTrue(index.visible(emptyList(), area).foreground.isEmpty())
    }

    private fun assertMatches(index: SceneIndex, items: List<Item>, area: Bounds) {
        val expected = items.filter { it.bounds.intersects(area) }
        val actual = index.visible(items, area)
        assertEquals(expected.filter { it.kind == "PDF" }, actual.pages)
        assertEquals(expected.filter { it.kind == "HIGHLIGHTER" }, actual.highlights)
        assertEquals(
            expected.filter { it.kind != "PDF" && it.kind != "HIGHLIGHTER" },
            actual.foreground,
        )
    }
}

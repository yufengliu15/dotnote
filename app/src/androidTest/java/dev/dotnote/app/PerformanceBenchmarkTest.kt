package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repeatable workloads for every place Dotnote can make the user wait. The same file is compiled
 * against the previous release and the optimized build, so it only uses APIs both versions have.
 * Results are logged under the `DotnoteBench` tag and streamed to the instrumentation output.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    private fun report(name: String, values: List<Double>) {
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[((sorted.size - 1) * .9).toInt()]
        val line =
            "BENCH $name median=${"%.3f".format(median)} ms p90=${"%.3f".format(p90)} ms n=${values.size}"
        Log.i("DotnoteBench", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0

    /** A handwriting-like pressure stroke made of [count] real Ink inputs. */
    private fun handwriting(seed: Int, x: Float, y: Float, count: Int = 40): Item {
        val inputs =
            MutableStrokeInputBatch().apply {
                repeat(count) { n ->
                    val t = n / count.toFloat()
                    add(
                        StrokeInput().apply {
                            update(
                                x + t * 28f + sin(t * 9f + seed) * 4f,
                                y + cos(t * 7f + seed * .3f) * 6f,
                                n * 4L,
                                pressure = .4f + .4f * sin(t * 3f + seed).let { it * it },
                            )
                        }
                    )
                }
            }
        return strokeItem(
            inputs,
            brush(defaultPalette[seed % defaultPalette.size], 2.5f, false),
            false,
        )
    }

    /** Roughly two pages of dense writing with highlights, shapes and typed text. */
    private fun denseScene(strokes: Int = 2000): List<Item> {
        val items = ArrayList<Item>()
        repeat(strokes) { n ->
            val col = n % 40
            val row = n / 40
            items.add(handwriting(n, 20f + col * 30f, 30f + row * 15f))
        }
        repeat(60) { n ->
            items.add(
                Item(
                    kind = "HIGHLIGHTER",
                    color = if (n % 3 == 0) Color.YELLOW else 0xff80d8ff.toInt(),
                    width = 14f,
                    points = List(30) { p -> Pt(30f + p * 12f + (n % 3) * 350f, 40f + n * 12f) },
                )
            )
        }
        repeat(80) { n ->
            items.add(
                Item(
                    kind = listOf("RECTANGLE", "ELLIPSE", "ARROW", "GRID")[n % 4],
                    color = Color.DKGRAY,
                    width = 2f,
                    points =
                        listOf(
                            Pt(40f + (n % 10) * 115f, 820f + (n / 10) * 60f),
                            Pt(120f + (n % 10) * 115f, 860f + (n / 10) * 60f),
                        ),
                    rows = 3,
                    cols = 4,
                )
            )
        }
        repeat(12) { n ->
            items.add(
                textItem(
                    "Typed note $n: dense scene benchmark",
                    18f,
                    Color.BLACK,
                    Pt(40f + (n % 3) * 400f, 1350f + (n / 3) * 40f),
                    null,
                )
            )
        }
        return items
    }

    private fun await(timeout: Long = 120_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ok = false
            instrumentation.runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(20)
        }
        fail("Timed out")
    }

    private fun find(root: View): NotebookView? {
        if (root is NotebookView) return root
        if (root is ViewGroup)
            for (i in 0 until root.childCount) find(root.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private class Editor(
        val scenario: ActivityScenario<MainActivity>,
        val state: AppState,
        val view: NotebookView,
        val window: Window,
        val noteId: String,
    )

    private fun withDenseEditor(items: List<Item>, block: (Editor) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            scenario.onActivity {
                state = ViewModelProvider(it)[AppState::class.java]
                state.dock = "Top"
                state.createNote("Benchmark ${newId()}")
            }
            await { state.note != null && !state.busy }
            val id = state.note!!.id
            try {
                instrumentation.runOnMainSync {
                    state.commit(items)
                    state.camera(Camera(0f, 0f, 1f))
                }
                // 0.9.x cannot re-read index rows over Android's 2 MB CursorWindow; later saves of
                // a large note fail there. Rendering workloads continue in memory either way.
                val firstSave = runBlocking { state.flush() }
                Log.i("DotnoteBench", "initial save ok=$firstSave")
                var view: NotebookView? = null
                var window: Window? = null
                await {
                    scenario.onActivity { activity ->
                        window = activity.window
                        view = find(activity.window.decorView)
                    }
                    view != null && view!!.width > 0
                }
                block(Editor(scenario, state, view!!, window!!, id))
            } finally {
                // 0.9.x cannot close a note whose save fails (rows over 2 MB); clean up anyway.
                instrumentation.runOnMainSync { state.closeNote() }
                runCatching { await(30_000) { state.note == null && !state.busy } }
                runBlocking { state.store.dao.deleteNote(id) }
            }
        }
    }

    /**
     * Waits until background tile rendering (0.10.0+) has finished for the current camera, so
     * frames are measured complete. Builds without tiles return immediately. Uses reflection so
     * this file also compiles against older builds.
     */
    private fun waitTiles(view: NotebookView) {
        val field = runCatching { NotebookView::class.java.getDeclaredField("tiles") }.getOrNull()
        field ?: return
        field.isAccessible = true
        val tiles = field.get(view)
        val idle = tiles.javaClass.declaredMethods.first { it.name.startsWith("idle") }
        idle.isAccessible = true
        val deadline = SystemClock.uptimeMillis() + 600_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { view.refresh() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            instrumentation.waitForIdleSync()
            var done = false
            instrumentation.runOnMainSync { done = idle.invoke(tiles) == true }
            if (done) return
        }
        fail("Tiles did not finish")
    }

    /** UI-thread cost of a full canvas frame: background, highlight and ink layers. */
    private fun drawLayers(
        view: NotebookView,
        canvas: Canvas,
        layers: List<MutableList<Double>>? = null,
    ): Double {
        val start = System.nanoTime()
        for (i in 0 until minOf(3, view.childCount)) {
            val layer = System.nanoTime()
            view.getChildAt(i).draw(canvas)
            layers?.get(i)?.add(ms(layer))
        }
        return ms(start)
    }

    private fun pan(e: Editor, offset: Float) {
        e.state.camera(Camera(-offset, -offset * .4f, 1f))
        e.view.refresh()
    }

    @Test
    fun denseNotePanFrames() {
        withDenseEditor(denseScene()) { e ->
            val bitmap = Bitmap.createBitmap(e.view.width, e.view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            try {
                val path = List(8) { 300f * sin(it * Math.PI / 4).toFloat() + 300f }
                // Cold: the first frame of a freshly opened dense note.
                var cold = 0.0
                instrumentation.runOnMainSync {
                    pan(e, path[0])
                    cold = drawLayers(e.view, canvas)
                }
                report("pan.first.frame", listOf(cold))
                // Warm every position so steady-state frames are complete.
                path.forEach {
                    instrumentation.runOnMainSync {
                        pan(e, it)
                        drawLayers(e.view, canvas)
                    }
                    waitTiles(e.view)
                }
                SystemClock.sleep(1000)
                instrumentation.waitForIdleSync()
                val times = mutableListOf<Double>()
                val layers = List(3) { mutableListOf<Double>() }
                instrumentation.runOnMainSync {
                    path.forEach {
                        pan(e, it)
                        times.add(drawLayers(e.view, canvas, layers))
                    }
                }
                report("pan.frame.software", times)
                report("pan.layer.background", layers[0])
                report("pan.layer.markers", layers[1])
                report("pan.layer.ink", layers[2])
                // Real hardware-rendered frames, including RenderThread work.
                val totals = mutableListOf<Double>()
                val listener =
                    Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                        synchronized(totals) {
                            totals.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6)
                        }
                    }
                instrumentation.runOnMainSync {
                    e.window.addOnFrameMetricsAvailableListener(
                        listener,
                        android.os.Handler(android.os.Looper.getMainLooper()),
                    )
                }
                SystemClock.sleep(500)
                synchronized(totals) { totals.clear() }
                path.forEach {
                    instrumentation.runOnMainSync { pan(e, it) }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(30)
                }
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    e.window.removeOnFrameMetricsAvailableListener(listener)
                }
                synchronized(totals) { if (totals.size > 4) report("pan.frame.hardware", totals) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun denseNotePinchZoomFrames() {
        withDenseEditor(denseScene()) { e ->
            val bitmap = Bitmap.createBitmap(e.view.width, e.view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            try {
                val zooms = List(8) { .55f + .45f * (1 + sin(it * Math.PI / 4).toFloat()) / 2 }
                fun frame(z: Float): Double {
                    e.state.camera(Camera(-100f * z, -50f * z, z))
                    e.view.refresh()
                    return drawLayers(e.view, canvas)
                }
                zooms.forEach {
                    instrumentation.runOnMainSync { frame(it) }
                    SystemClock.sleep(200)
                    instrumentation.runOnMainSync { frame(it) }
                    waitTiles(e.view)
                }
                val times = mutableListOf<Double>()
                instrumentation.runOnMainSync { zooms.forEach { times.add(frame(it)) } }
                report("zoom.frame.software", times)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun denseNoteStrokeCommit() {
        withDenseEditor(denseScene()) { e ->
            val bitmap = Bitmap.createBitmap(e.view.width, e.view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            try {
                instrumentation.runOnMainSync {
                    pan(e, 0f)
                    drawLayers(e.view, canvas)
                }
                waitTiles(e.view)
                val times = mutableListOf<Double>()
                val stateTimes = mutableListOf<Double>()
                val layers = List(3) { mutableListOf<Double>() }
                repeat(8) { n ->
                    val stroke = handwriting(5000 + n, 200f + n * 30f, 200f + (n % 4) * 20f)
                    instrumentation.runOnMainSync {
                        val start = System.nanoTime()
                        e.state.commit(e.state.document.items + stroke)
                        e.view.refresh()
                        stateTimes.add(ms(start))
                        drawLayers(e.view, canvas, layers)
                        times.add(ms(start))
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(150)
                }
                report("stroke.commit.ui", times)
                report("stroke.commit.state", stateTimes)
                report("stroke.commit.layer.background", layers[0])
                report("stroke.commit.layer.markers", layers[1])
                report("stroke.commit.layer.ink", layers[2])
                // Durable save of the edited note, measured as the user-visible "Saved" latency.
                val saves = mutableListOf<Double>()
                var failures = 0
                repeat(3) { n ->
                    val stroke = handwriting(7000 + n, 300f + n * 30f, 260f)
                    instrumentation.runOnMainSync {
                        e.state.commit(e.state.document.items + stroke)
                    }
                    val start = System.nanoTime()
                    if (!runBlocking { e.state.flush() }) failures++
                    saves.add(ms(start))
                }
                report("save.after.stroke", saves)
                Log.i("DotnoteBench", "BENCH save.after.stroke failures=$failures of 3")
                instrumentation.sendStatus(
                    0,
                    Bundle().apply { putString("stream", "BENCH save failures=$failures of 3\n") },
                )
                // Rapid writing: ten pen-ups queued faster than a large note can be encoded.
                val burst = System.nanoTime()
                instrumentation.runOnMainSync {
                    repeat(10) { n ->
                        e.state.commit(
                            e.state.document.items + handwriting(9000 + n, 100f + n * 20f, 500f)
                        )
                    }
                }
                val burstOk = runBlocking { e.state.flush() }
                report("save.burst10", listOf(ms(burst)))
                Log.i("DotnoteBench", "BENCH save.burst10 ok=$burstOk")
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun stylus(view: View, action: Int, down: Long, x: Float, y: Float): MotionEvent {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = .7f
                }
            )
        return MotionEvent.obtain(
            down,
            SystemClock.uptimeMillis(),
            action,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_STYLUS,
            0,
        )
    }

    private fun gesture(
        e: Editor,
        canvas: Canvas,
        points: List<Pair<Float, Float>>,
        times: MutableList<Double>?,
    ): Double {
        val density = context.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        var release = 0.0
        points.forEachIndexed { index, (x, y) ->
            val action =
                when (index) {
                    0 -> MotionEvent.ACTION_DOWN
                    points.lastIndex -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            instrumentation.runOnMainSync {
                val event = stylus(e.view, action, down, x * density, y * density)
                val start = System.nanoTime()
                e.view.dispatchTouchEvent(event)
                drawLayers(e.view, canvas)
                val elapsed = ms(start)
                if (action == MotionEvent.ACTION_MOVE) times?.add(elapsed)
                if (action == MotionEvent.ACTION_UP) release = elapsed
                event.recycle()
            }
        }
        return release
    }

    @Test
    fun denseNoteEraserAndSelection() {
        withDenseEditor(denseScene()) { e ->
            val bitmap = Bitmap.createBitmap(e.view.width, e.view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            try {
                instrumentation.runOnMainSync {
                    pan(e, 0f)
                    drawLayers(e.view, canvas)
                }
                waitTiles(e.view)
                // Eraser sweeping across writing (removes strokes) and hovering in a gap.
                instrumentation.runOnMainSync { e.state.tool = Tool.ERASER }
                val erase = mutableListOf<Double>()
                gesture(e, canvas, List(14) { (300f + it * 16f) to 92f }, erase)
                gesture(e, canvas, List(14) { (300f + it * 16f) to 1200f }, erase)
                report("eraser.move", erase)
                // Lasso: draw a loop around ~200 strokes, then drag the selection.
                instrumentation.runOnMainSync { e.state.tool = Tool.LASSO }
                val loop =
                    List(34) {
                        val a = it * 2 * Math.PI / 32
                        (500f + 160f * cos(a).toFloat()) to (300f + 80f * sin(a).toFloat())
                    }
                val select = gesture(e, canvas, loop, null)
                report("lasso.select.release", listOf(select))
                var selected = 0
                instrumentation.runOnMainSync { selected = e.state.selection.size }
                assertTrue("Lasso selected $selected", selected > 50)
                waitTiles(e.view)
                val moves = mutableListOf<Double>()
                gesture(e, canvas, List(14) { (500f + it * 8f) to (300f + it * 4f) }, moves)
                report("selection.move", moves)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun openMediumNote() = openNote(1200, "note.open.1200")

    @Test
    fun openLargeNote() = openNote(3000, "note.open.3000")

    private fun openNote(strokes: Int, name: String) {
        withDenseEditor(denseScene(strokes)) { e ->
            val times = mutableListOf<Double>()
            var opened = 0
            repeat(3) {
                instrumentation.runOnMainSync { e.state.closeNote() }
                val closed =
                    runCatching { await(60_000) { e.state.note == null && !e.state.busy } }
                        .isSuccess
                val start = System.nanoTime()
                instrumentation.runOnMainSync { e.state.open(e.noteId) }
                runCatching { await(60_000) { !e.state.busy && e.state.note != null } }
                times.add(ms(start))
                if (closed && e.state.note?.id == e.noteId) opened++
            }
            report(name, times)
            val line = "BENCH $name opened=$opened of 3 message=${e.state.message}"
            Log.i("DotnoteBench", line)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
            if (opened == 0) {
                // Leave the editor open for cleanup.
                instrumentation.runOnMainSync { e.state.open(e.noteId) }
            }
        }
    }

    @Test
    fun vaultStartupAndSync() = runBlocking {
        val databaseName = "bench-${newId()}.db"
        var store = Store(context, databaseName)
        val folder = Folder(name = "Benchmark")
        val notes =
            List(60) { n ->
                Note(
                    folderId = folder.id,
                    title = "Bench note $n",
                    document =
                        DocumentCodec.encode(
                            Document(List(120) { handwriting(n * 1000 + it, it * 10f, n * 20f) })
                        ),
                )
            }
        try {
            store.replaceLibrary(listOf(folder), notes)
            store.db.close()
            context.deleteDatabase(databaseName)
            // A truly cold start: no index and (0.10.0+) no record of validated files either.
            File(context.noBackupFilesDir, "vault-scan").deleteRecursively()
            // First launch after install/restore: no index yet.
            var start = System.nanoTime()
            store = Store(context, databaseName)
            store.ready.await()
            val cold = ms(start)
            assertEquals(60, store.dao.allNotes().size)
            val warm = mutableListOf<Double>()
            repeat(3) {
                store.db.close()
                start = System.nanoTime()
                store = Store(context, databaseName)
                store.ready.await()
                warm.add(ms(start))
            }
            assertEquals(notes[7].title, store.dao.note(notes[7].id)!!.title)
            report("startup.cold.60x120", listOf(cold))
            report("startup.warm.60x120", warm)
        } finally {
            store.db.close()
            context.deleteDatabase(databaseName)
            store.root.deleteRecursively()
        }
    }

    /** GitHub stand-in with a fixed round-trip delay per request. */
    private class SlowGitHub(private val delayMs: Long) : GitHub("") {
        var requests = 0
        var head: String? = "initial"
        private val blobs = mutableMapOf<String, ByteArray>()
        private val trees = mutableMapOf("initial" to emptyMap<String, GitEntry>())
        private val commits = mutableMapOf("initial" to "initial")

        private fun call() {
            synchronized(this) { requests++ }
            // Network latency overlaps for concurrent requests, as it does against GitHub.
            if (delayMs > 0) Thread.sleep(delayMs)
        }

        override fun repo(name: String) = GitRepo(name, "main", true, true).also { call() }

        override fun head(repo: String, branch: String) = head.also { call() }

        override fun tree(repo: String, commit: String): Pair<String, List<GitEntry>> {
            call()
            call()
            val tree = commits.getValue(commit)
            return tree to trees.getValue(tree).values.toList()
        }

        override fun upload(repo: String, file: File): String {
            call()
            return digest(file, true).also { synchronized(blobs) { blobs[it] = file.readBytes() } }
        }

        override fun download(repo: String, entry: GitEntry, destination: File) {
            call()
            destination.parentFile!!.mkdirs()
            destination.writeBytes(synchronized(blobs) { blobs.getValue(entry.sha) })
        }

        override fun request(path: String, method: String, body: JSONObject?): String {
            call()
            val data = requireNotNull(body)
            return when {
                path.endsWith("/git/trees") -> {
                    val map = trees.getValue(data.getString("base_tree")).toMutableMap()
                    val a = data.getJSONArray("tree")
                    for (i in 0 until a.length()) {
                        val item = a.getJSONObject(i)
                        val name = item.getString("path")
                        if (item.has("content")) {
                            val bytes = item.getString("content").toByteArray()
                            val file = File.createTempFile("blob", null)
                            file.writeBytes(bytes)
                            val sha = digest(file, true)
                            file.delete()
                            synchronized(blobs) { blobs[sha] = bytes }
                            map[name] = GitEntry(name, sha, bytes.size.toLong())
                        } else if (item.isNull("sha")) map.remove(name)
                        else {
                            val sha = item.getString("sha")
                            map[name] =
                                GitEntry(
                                    name,
                                    sha,
                                    synchronized(blobs) { blobs.getValue(sha) }.size.toLong(),
                                )
                        }
                    }
                    val id = "tree-${newId()}"
                    trees[id] = map
                    JSONObject().put("sha", id).toString()
                }
                path.endsWith("/git/commits") -> {
                    val id = "commit-${newId()}"
                    commits[id] = data.getString("tree")
                    JSONObject().put("sha", id).toString()
                }
                path.contains("/git/refs/heads/") -> {
                    head = data.getString("sha")
                    "{}"
                }
                else -> error("Unexpected API call $path")
            }
        }
    }

    @Test
    fun githubBackupAndRestore() = runBlocking {
        val catalog = VaultCatalog(context)
        val vault = catalog.create("Benchmark sync")
        val root = catalog.root(vault.localId)
        val files = VaultFiles(root)
        val api = SlowGitHub(80)
        val backup = GitBackup(context, { api }, { "test-token" })
        var restored: VaultInfo? = null
        try {
            files.read()
            val notes =
                List(40) { n ->
                    Note(
                        title = "Sync note $n",
                        document =
                            DocumentCodec.encode(
                                Document(List(60) { handwriting(n * 100 + it, it * 10f, 0f) })
                            ),
                    )
                }
            notes.forEach(files::writeNote)
            catalog.update(vault.localId) {
                it.put("repo", "owner/vault")
                    .put("branch", "main")
                    .put("base", "initial")
                    .put("revision", 1)
                    .put("automatic", false)
            }
            var start = System.nanoTime()
            api.requests = 0
            backup.backup(vault.localId)
            report("sync.backup.40notes", listOf(ms(start)))
            Log.i("DotnoteBench", "sync.backup.40notes requests=${api.requests}")
            // Incremental: three notes edited since the last backup.
            notes.take(3).forEach { files.writeNote(it.copy(title = it.title + " edited")) }
            catalog.update(vault.localId) { it.put("revision", 2) }
            start = System.nanoTime()
            api.requests = 0
            backup.backup(vault.localId)
            report("sync.backup.3changed", listOf(ms(start)))
            Log.i("DotnoteBench", "sync.backup.3changed requests=${api.requests}")
            // Nothing changed: the common automatic-backup case.
            start = System.nanoTime()
            api.requests = 0
            backup.backup(vault.localId)
            report("sync.backup.unchanged", listOf(ms(start)))
            start = System.nanoTime()
            api.requests = 0
            restored = backup.restore("owner/vault")
            report("sync.restore.40notes", listOf(ms(start)))
            Log.i("DotnoteBench", "sync.restore requests=${api.requests}")
            assertEquals(40, VaultFiles(catalog.root(restored!!.localId)).read().second.size)
        } finally {
            BackupScheduler.cancel(context, vault.localId)
            root.deleteRecursively()
            restored?.let {
                BackupScheduler.cancel(context, it.localId)
                catalog.root(it.localId).deleteRecursively()
            }
        }
    }
}

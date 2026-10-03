package dev.dotnote.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityOfLifeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun await(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20000
        while (!check()) {
            check(SystemClock.uptimeMillis() < end) { "UI condition timed out" }
            SystemClock.sleep(40)
        }
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let {
                find(it)?.let { result ->
                    return result
                }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun click(label: String) {
        await {
            var target = node(label)
            while (target != null && !target.isClickable) target = target.parent
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun drag(source: String, destination: String, whileHeld: () -> Unit) {
        await { node(source) != null && node(destination) != null }
        val a = Rect().also { node(source)!!.getBoundsInScreen(it) }
        val b = Rect().also { node(destination)!!.getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, a.exactCenterX(), a.exactCenterY())
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        for (i in 1..12) {
            send(
                MotionEvent.ACTION_MOVE,
                a.exactCenterX() + (b.exactCenterX() - a.exactCenterX()) * i / 12,
                a.exactCenterY() + (b.exactCenterY() - a.exactCenterY()) * i / 12,
            )
            SystemClock.sleep(35)
        }
        whileHeld()
        send(MotionEvent.ACTION_UP, b.exactCenterX(), b.exactCenterY())
    }

    @Test
    fun heldMovesAndPaletteSwitchAndFillOptions() {
        val context = instrumentation.targetContext
        val catalog = VaultCatalog(context)
        val original = catalog.selected()
        val vault = catalog.create("QoL UI")
        catalog.select(vault.localId)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var state: AppState
                scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
                await { !state.busy }
                val target = Folder(name = "Drop target")
                val child = Folder(name = "Held folder")
                val note = Note(title = "Held note")
                runBlocking {
                    state.store.dao.put(target)
                    state.store.dao.put(child)
                    state.store.dao.put(note)
                }
                drag(note.title, target.name) {
                    assertNull(runBlocking { state.store.dao.note(note.id)!!.folderId })
                }
                await {
                    runBlocking { state.store.dao.note(note.id)!!.folderId == target.id } &&
                        !state.busy
                }
                drag(child.name, target.name) {
                    assertNull(
                        runBlocking {
                            state.store.dao.allFolders().first { it.id == child.id }.parentId
                        }
                    )
                }
                await {
                    runBlocking {
                        state.store.dao.allFolders().first { it.id == child.id }.parentId ==
                            target.id
                    } && !state.busy
                }
                click(target.name)
                drag(note.title, "All notes") {
                    assertEquals(
                        target.id,
                        runBlocking { state.store.dao.note(note.id)!!.folderId },
                    )
                }
                await {
                    runBlocking { state.store.dao.note(note.id)!!.folderId == null } && !state.busy
                }
                drag(child.name, "All notes") {
                    assertEquals(
                        target.id,
                        runBlocking {
                            state.store.dao.allFolders().first { it.id == child.id }.parentId
                        },
                    )
                }
                await {
                    runBlocking {
                        state.store.dao.allFolders().first { it.id == child.id }.parentId == null
                    } && !state.busy
                }
                click("All notes")
                click(note.title)
                await { state.note?.id == note.id && !state.busy }
                scenario.onActivity {
                    state.tool = Tool.ERASER
                    state.dock = "Top"
                }
                click("Color slot 2")
                await { state.tool == Tool.PEN && state.color == state.palette[1] }
                val color = state.color
                click("Fill")
                await { node("Pen fill") != null && node("Highlighter base") != null }
                val pen = Rect().also { node("Pen fill")!!.getBoundsInScreen(it) }
                val marker = Rect().also { node("Highlighter base")!!.getBoundsInScreen(it) }
                assertTrue(kotlin.math.abs(pen.centerY() - marker.centerY()) < 10)
                click("Highlighter base")
                await { state.tool == Tool.FILL && state.fillHighlighter }
                assertEquals(color, state.color)
                var tapX = 0f
                var tapY = 0f
                scenario.onActivity { activity ->
                    fun canvas(view: android.view.View): NotebookView? {
                        if (view is NotebookView) return view
                        if (view is android.view.ViewGroup)
                            for (i in 0 until view.childCount) {
                                canvas(view.getChildAt(i))?.let {
                                    return it
                                }
                            }
                        return null
                    }
                    val view = requireNotNull(canvas(activity.window.decorView))
                    state.camera(Camera(0f, 0f, 1f))
                    state.commit(
                        listOf(
                            Item(kind = "RECTANGLE", points = listOf(Pt(20f, 20f), Pt(160f, 140f)))
                        )
                    )
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    tapX = location[0] + 70f * view.resources.displayMetrics.density
                    tapY = location[1] + 70f * view.resources.displayMetrics.density
                }
                await { node("Highlighter base") == null }
                SystemClock.sleep(
                    350
                ) // Allow the popup exit transition to release its input window.
                instrumentation.waitForIdleSync()
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event =
                        MotionEvent.obtain(
                            downTime,
                            SystemClock.uptimeMillis(),
                            action,
                            1,
                            arrayOf(
                                MotionEvent.PointerProperties().apply {
                                    id = 0
                                    toolType = MotionEvent.TOOL_TYPE_FINGER
                                }
                            ),
                            arrayOf(
                                MotionEvent.PointerCoords().apply {
                                    x = tapX
                                    y = tapY
                                    pressure = 1f
                                    size = 1f
                                }
                            ),
                            0,
                            0,
                            1f,
                            1f,
                            0,
                            0,
                            android.view.InputDevice.SOURCE_TOUCHSCREEN,
                            0,
                        )
                    try {
                        instrumentation.sendPointerSync(event)
                    } finally {
                        event.recycle()
                    }
                    SystemClock.sleep(50)
                }
                SystemClock.sleep(500)
                scenario.onActivity {
                    println(
                        "Fill tap: tool=${state.tool}, camera=${state.document.camera}, busy=${state.busy}, message=${state.message}, items=${state.document.items.map { it.kind to it.fill }}, point=$tapX,$tapY"
                    )
                }
                await { !state.busy && state.document.items.any { it.fill } }
                assertEquals("HIGHLIGHTER", state.document.items.last().kind)
                scenario.onActivity { state.undo() }
                assertFalse(state.document.items.any { it.fill })
                scenario.onActivity { state.redo() }
                assertTrue(state.document.items.any { it.fill })
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
            }
        } finally {
            catalog.select(original)
        }
    }
}

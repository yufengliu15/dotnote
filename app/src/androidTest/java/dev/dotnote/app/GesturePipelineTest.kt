package dev.dotnote.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GesturePipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun findCanvas(view: View): NotebookView? {
        if (view is NotebookView) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for editor")
    }

    private fun draw(view: NotebookView, x: Float, y: Float, dx: Float, dy: Float) {
        val down = SystemClock.uptimeMillis()
        val density = view.resources.displayMetrics.density
        for (step in 0..12) {
            val action =
                when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    12 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            val props =
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }
            val coords =
                MotionEvent.PointerCoords().apply {
                    this.x = (x + dx * step / 12) * density
                    this.y = (y + dy * step / 12) * density
                    pressure = .6f
                    size = .02f
                }
            val event =
                MotionEvent.obtain(
                    down,
                    down + step * 8,
                    action,
                    1,
                    arrayOf(props),
                    arrayOf(coords),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_STYLUS,
                    0,
                )
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun stylusCoordinatesUndoAndReopenSurviveZoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            var view: NotebookView? = null
            scenario.onActivity { activity ->
                state = ViewModelProvider(activity)[AppState::class.java]
                state.createNote("Gesture test")
            }
            await { state.note != null && !state.busy }
            val id = state.note!!.id
            try {
                await {
                    scenario.onActivity { view = findCanvas(it.window.decorView) }
                    view?.width?.let { it > 0 } == true
                }
                scenario.onActivity {
                    state.tool = Tool.SQUARE
                    state.camera(Camera(20f, 30f, 2f))
                    draw(view!!, 200f, 200f, 120f, 90f)
                }
                await { state.document.items.size == 1 }
                val shape = state.document.items.single()
                assertEquals(90f, shape.points[0].x, .1f)
                assertEquals(85f, shape.points[0].y, .1f)
                assertEquals(
                    shape.points[1].x - shape.points[0].x,
                    shape.points[1].y - shape.points[0].y,
                    .1f,
                )
                scenario.onActivity {
                    state.tool = Tool.PEN
                    draw(view!!, 200f, 350f, 130f, 30f)
                    view!!.settle()
                }
                await { state.document.items.size == 2 && state.saved }
                val stroke = state.document.items.last()
                assertEquals("PEN", stroke.kind)
                assertEquals(90f, stroke.points.first().x, 2f)
                assertEquals(160f, stroke.points.first().y, 2f)
                scenario.onActivity { state.undo() }
                assertEquals(1, state.document.items.size)
                scenario.onActivity { state.redo() }
                assertEquals(2, state.document.items.size)
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
                scenario.onActivity { state.open(id) }
                await { state.note?.id == id && !state.busy }
                assertEquals(2, state.document.items.size)
                assertEquals(2f, state.document.camera.zoom, .001f)
                await {
                    scenario.onActivity { view = findCanvas(it.window.decorView) }
                    view?.width?.let { it > 0 } == true
                }
                scenario.onActivity {
                    state.tool = Tool.HIGHLIGHTER
                    draw(view!!, 200f, 300f, 130f, 30f)
                }
                await { state.document.items.size == 3 && state.saved }
                assertEquals("HIGHLIGHTER", state.document.items.last().kind)
                assertEquals(90f, state.document.items.last().points.first().x, 2f)
                scenario.onActivity { state.undo() }
                assertEquals(2, state.document.items.size)
                scenario.onActivity {
                    state.redo()
                    state.closeNote()
                }
                await { state.note == null && !state.busy }
                scenario.onActivity { state.open(id) }
                await { state.note?.id == id && !state.busy }
                assertEquals("HIGHLIGHTER", state.document.items.last().kind)
                scenario.onActivity { state.closeNote() }
                await { state.note == null && !state.busy }
            } finally {
                runBlocking { state.store.dao.deleteNote(id) }
            }
        }
    }
}

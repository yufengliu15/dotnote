package dev.dotnote.app

import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlingNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun canvas(view: View): NotebookView? {
        if (view is NotebookView) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) {
                canvas(view.getChildAt(i))?.let {
                    return it
                }
            }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Timed out waiting for navigation")
    }

    private fun withEditor(test: (AppState, NotebookView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            var view: NotebookView? = null
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            await { !state.busy }
            scenario.onActivity { state.createNote("Fling regression test") }
            await {
                scenario.onActivity { view = canvas(it.window.decorView) }
                state.note != null && !state.busy && (view?.width ?: 0) > 0
            }
            val id = state.note!!.id
            val fingerDrawing = state.fingerDrawing
            try {
                instrumentation.runOnMainSync {
                    state.fingerDrawing = false
                    state.tool = Tool.PEN
                    state.camera(Camera())
                }
                test(state, view!!)
            } finally {
                scenario.onActivity { activity ->
                    view = canvas(activity.window.decorView) ?: view
                    view!!.settle()
                    val ink =
                        (0 until view!!.childCount)
                            .map { view!!.getChildAt(it) }
                            .filterIsInstance<androidx.ink.authoring.InProgressStrokesView>()
                            .single()
                    @Suppress("RestrictedApi") ink.sync(2, java.util.concurrent.TimeUnit.SECONDS)
                    state.fingerDrawing = fingerDrawing
                    state.closeNote()
                }
                await { state.note == null && !state.busy }
                runBlocking { state.store.dao.deleteNote(id) }
            }
        }
    }

    // Real pointer IDs intentionally differ from indices. Coordinates are screen dp.
    private fun send(
        view: NotebookView,
        down: Long,
        elapsed: Long,
        action: Int,
        points: List<Pt>,
        tool: Int = MotionEvent.TOOL_TYPE_FINGER,
        flags: Int = 0,
    ) {
        val density = view.resources.displayMetrics.density
        val event =
            MotionEvent.obtain(
                down,
                down + elapsed,
                action,
                points.size,
                points.indices
                    .map { i ->
                        MotionEvent.PointerProperties().apply {
                            id = 7 + i
                            toolType = tool
                        }
                    }
                    .toTypedArray(),
                points
                    .map { p ->
                        MotionEvent.PointerCoords().apply {
                            x = p.x * density
                            y = p.y * density
                            pressure = .7f
                        }
                    }
                    .toTypedArray(),
                0,
                0,
                1f,
                1f,
                0,
                0,
                if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS
                else InputDevice.SOURCE_TOUCHSCREEN,
                flags,
            )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun swipe(
        view: NotebookView,
        duration: Long = 120,
        distance: Float = -180f,
        ending: Int = MotionEvent.ACTION_UP,
        flags: Int = 0,
        tool: Int = MotionEvent.TOOL_TYPE_FINGER,
    ) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) send(
            view,
            down,
            duration * step / 12,
            when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> ending
                else -> MotionEvent.ACTION_MOVE
            },
            listOf(Pt(200f, 320f + distance * step / 12)),
            tool,
            if (step == 12) flags else 0,
        )
    }

    private fun camera(state: AppState): Camera {
        var result = Camera()
        instrumentation.runOnMainSync { result = state.document.camera }
        return result
    }

    private fun assertStopped(state: AppState) {
        val stopped = camera(state)
        SystemClock.sleep(180)
        assertEquals(stopped, camera(state))
    }

    @Test
    fun pdfFlickCoastsScalesWithSpeedAndPersists() = withEditor { state, view ->
        val file = File(state.getApplication<android.app.Application>().cacheDir, "fling-test.pdf")
        try {
            val pdf = PdfDocument()
            try {
                repeat(40) { page ->
                    val p = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, page + 1).create())
                    pdf.finishPage(p)
                }
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            val pages = PdfFiles.import(state.store, Uri.fromFile(file), 0f)
            instrumentation.runOnMainSync { state.commit(pages) }
            fun coast(duration: Long, zoom: Float): Float {
                var released = 0f
                instrumentation.runOnMainSync {
                    view.settle()
                    state.camera(Camera(20f, -10000f, zoom))
                    swipe(view, duration)
                    released = state.document.camera.y
                }
                await { state.document.camera.y < released - 20f }
                SystemClock.sleep(150)
                val after = camera(state)
                assertEquals(20f, after.x, .01f)
                assertEquals(zoom, after.zoom, .001f)
                instrumentation.runOnMainSync { view.settle() }
                return released - after.y
            }
            val slow = coast(240, 1f)
            val fast = coast(80, 2f)
            assertTrue("Faster flick should travel farther: $fast versus $slow", fast > slow * 1.5f)
            assertStopped(state)
            // Let an un-interrupted downward flick decay to rest and save its final camera.
            instrumentation.runOnMainSync { swipe(view, distance = 120f) }
            val released = camera(state)
            await { state.document.camera.y > released.y + 20f }
            var lastCamera = camera(state)
            var lastMotion = SystemClock.uptimeMillis()
            await {
                val current = state.document.camera
                if (current != lastCamera) {
                    lastCamera = current
                    lastMotion = SystemClock.uptimeMillis()
                }
                SystemClock.uptimeMillis() - lastMotion > 500 && state.saved
            }
            assertStopped(state)
            val settled = camera(state)
            val id = state.note!!.id
            instrumentation.runOnMainSync { state.closeNote() }
            await { state.note == null && !state.busy }
            instrumentation.runOnMainSync { state.open(id) }
            await { state.note?.id == id && !state.busy }
            assertEquals(settled, camera(state))
            assertEquals(40, state.document.items.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun touchStylusAndPageJumpStopMomentum() = withEditor { state, view ->
        fun startFling() {
            var released = 0f
            instrumentation.runOnMainSync {
                swipe(view)
                released = state.document.camera.y
            }
            await { state.document.camera.y < released - 20f }
        }
        startFling()
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            send(view, down, 0, MotionEvent.ACTION_DOWN, listOf(Pt(200f, 200f)))
            send(view, down, 10, MotionEvent.ACTION_UP, listOf(Pt(200f, 200f)))
        }
        assertStopped(state)
        startFling()
        instrumentation.runOnMainSync {
            state.tool = Tool.LINE
            swipe(view, tool = MotionEvent.TOOL_TYPE_STYLUS)
        }
        assertStopped(state)
        assertEquals("LINE", state.document.items.single().kind)
        startFling()
        instrumentation.runOnMainSync {
            view.page(Item(kind = "PDF", points = listOf(Pt(0f, 0f), Pt(400f, 600f))))
        }
        assertStopped(state)
        startFling()
        instrumentation.runOnMainSync { view.fit() }
        assertStopped(state)
        startFling()
        instrumentation.runOnMainSync { view.release() }
        assertStopped(state)
    }

    @Test
    fun slowDragCancellationPinchAndFingerDrawingNeverFling() = withEditor { state, view ->
        instrumentation.runOnMainSync { swipe(view, duration = 2400, distance = -30f) }
        assertStopped(state)
        instrumentation.runOnMainSync { swipe(view, ending = MotionEvent.ACTION_CANCEL) }
        assertStopped(state)
        instrumentation.runOnMainSync { swipe(view, flags = MotionEvent.FLAG_CANCELED) }
        assertStopped(state)
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            send(view, down, 0, MotionEvent.ACTION_DOWN, listOf(Pt(200f, 300f)))
            send(
                view,
                down,
                20,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),
                listOf(Pt(200f, 300f), Pt(400f, 300f)),
            )
            send(view, down, 40, MotionEvent.ACTION_MOVE, listOf(Pt(150f, 240f), Pt(450f, 240f)))
            send(
                view,
                down,
                60,
                MotionEvent.ACTION_POINTER_UP or (1 shl 8),
                listOf(Pt(150f, 220f), Pt(450f, 220f)),
            )
            send(view, down, 80, MotionEvent.ACTION_MOVE, listOf(Pt(150f, 200f)))
            send(view, down, 100, MotionEvent.ACTION_MOVE, listOf(Pt(150f, 150f)))
            send(view, down, 120, MotionEvent.ACTION_UP, listOf(Pt(150f, 100f)))
        }
        assertEquals(1.5f, camera(state).zoom, .001f)
        assertStopped(state)
        instrumentation.runOnMainSync {
            state.fingerDrawing = true
            state.tool = Tool.LINE
            swipe(view)
        }
        assertStopped(state)
        assertEquals("LINE", state.document.items.single().kind)
        instrumentation.runOnMainSync {
            state.tool = Tool.HAND
            swipe(view)
        }
        val released = camera(state)
        await { state.document.camera.y < released.y - 20f }
    }
}

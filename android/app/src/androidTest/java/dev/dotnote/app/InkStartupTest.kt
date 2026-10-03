package dev.dotnote.app

import android.graphics.Color
import android.os.Bundle
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
class InkStartupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun canvas(view: View): NotebookView? {
        if (view is NotebookView) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) canvas(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun await(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = test() }
            if (ready) return
            SystemClock.sleep(10)
        }
        fail("Editor did not become ready")
    }

    @Test
    fun firstPenStrokeIsVisibleBeforePenUp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var state: AppState
            var view: NotebookView? = null
            var previousColor = 0
            scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
            await { !state.busy }
            scenario.onActivity {
                previousColor = state.color
                state.color = Color.BLACK
                state.strokeWidth = 6f
                state.tool = Tool.PEN
                state.createNote("Ink startup test")
            }
            await {
                scenario.onActivity { view = canvas(it.window.decorView) }
                state.note != null && !state.busy && (view?.width ?: 0) > 0
            }
            val id = state.note!!.id
            fun send(step: Int, action: Int, began: Long) {
                val v = view!!
                val density = v.resources.displayMetrics.density
                val event =
                    MotionEvent.obtain(
                        began,
                        began + step * 5L,
                        action,
                        1,
                        arrayOf(
                            MotionEvent.PointerProperties().apply {
                                this.id = 0
                                toolType = MotionEvent.TOOL_TYPE_STYLUS
                            }
                        ),
                        arrayOf(
                            MotionEvent.PointerCoords().apply {
                                x = (180f + step * 10f) * density
                                y = 140f * density
                                pressure = .8f
                            }
                        ),
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_STYLUS,
                        0,
                    )
                v.dispatchTouchEvent(event)
                event.recycle()
            }
            var began = 0L
            try {
                // Surface/native warmup must never create a phantom document stroke.
                assertTrue(state.document.items.isEmpty())
                val location = IntArray(2)
                var density = 1f
                began = SystemClock.uptimeMillis()
                scenario.onActivity {
                    val v = view!!
                    v.getLocationOnScreen(location)
                    density = v.resources.displayMetrics.density
                    for (step in 0..9) {
                        send(
                            step,
                            if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                            began,
                        )
                    }
                }

                val dispatchMs = SystemClock.uptimeMillis() - began
                assertTrue("First stroke blocked input for $dispatchMs ms", dispatchMs < 250)
                var visible = false
                while (!visible && SystemClock.uptimeMillis() - began < 500) {
                    val shot = instrumentation.uiAutomation.takeScreenshot()
                    if (shot != null) {
                        val y = location[1] + (140 * density).toInt()
                        val x = location[0] + (230 * density).toInt()
                        for (dy in -3..3) for (dx in -3..3) {
                            val pixel =
                                shot.getPixel(
                                    (x + dx).coerceIn(0, shot.width - 1),
                                    (y + dy).coerceIn(0, shot.height - 1),
                                )
                            if (
                                Color.red(pixel) < 80 &&
                                    Color.green(pixel) < 80 &&
                                    Color.blue(pixel) < 80
                            )
                                visible = true
                        }
                        shot.recycle()
                    }
                }
                val visibleMs = SystemClock.uptimeMillis() - began
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "First wet stroke input: $dispatchMs ms; visible: $visibleMs ms\n",
                        )
                    },
                )
                assertTrue(
                    "First stroke was not visible while the pen was down within 500 ms",
                    visible && visibleMs < 500,
                )
                assertTrue(
                    "Ink must be visible before committing on pen-up",
                    state.document.items.isEmpty(),
                )
                scenario.onActivity { send(10, MotionEvent.ACTION_UP, began) }
                await { state.saved }
                assertEquals(1, state.document.items.size)
                assertEquals("PEN", state.document.items.single().kind)
            } finally {
                // Drain native rendering before destroying the emulator's drawing surface.
                // This is test cleanup after the latency measurement, never a production delay.
                scenario.onActivity {
                    send(11, MotionEvent.ACTION_CANCEL, began)
                    val ink =
                        (0 until view!!.childCount)
                            .map { view!!.getChildAt(it) }
                            .filterIsInstance<androidx.ink.authoring.InProgressStrokesView>()
                            .single()
                    @Suppress("RestrictedApi") ink.sync(2, java.util.concurrent.TimeUnit.SECONDS)
                    view!!.settle()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    state.color = previousColor
                    state.strokeWidth = 3f
                    state.closeNote()
                }
                await { state.note == null && !state.busy }
                runBlocking { state.store.dao.deleteNote(id) }
            }
        }
    }
}

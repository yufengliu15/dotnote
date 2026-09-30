package dev.dotnote.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarWidgetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    @Test
    fun realRemoteViewsShowWholeMonthsTodayAndFitResizing() {
        instrumentation.runOnMainSync {
            val today = LocalDate.of(2026, 11, 30)
            listOf(280 to 200, 360 to 220, 500 to 260).forEach { (width, height) ->
                val root =
                    CalendarWidgets.render(context, today, width.toFloat())
                        .apply(context, FrameLayout(context))
                assertEquals("NOV", root.findViewById<TextView>(R.id.calendar_current_title).text)
                assertEquals("DEC", root.findViewById<TextView>(R.id.calendar_next_title).text)
                val grid = root.findViewById<LinearLayout>(R.id.calendar_current_grid)
                val next = root.findViewById<LinearLayout>(R.id.calendar_next_grid)
                assertEquals(6, grid.childCount)
                assertEquals(6, next.childCount)
                val lastWeek = grid.getChildAt(5) as LinearLayout
                val selected = lastWeek.getChildAt(0).findViewById<TextView>(R.id.calendar_day)
                assertEquals("30", selected.text)
                assertEquals(Color.WHITE, selected.currentTextColor)
                assertNotNull(selected.background)
                assertTrue(selected.contentDescription.startsWith("Today"))
                assertEquals(
                    "31",
                    (next.getChildAt(4) as LinearLayout)
                        .getChildAt(3)
                        .findViewById<TextView>(R.id.calendar_day)
                        .text,
                )
                measure(root, width, height)
                assertTrue(selected.width > 0)
                assertTrue(selected.height <= lastWeek.height)
                assertTrue(selected.paint.measureText("30") <= selected.width)
                savePreview(root, "calendar-$width.png")
            }
            val root =
                CalendarWidgets.layouts(context, Bundle(), LocalDate.of(2026, 12, 31))
                    .apply(context, FrameLayout(context))
            assertEquals("JAN", root.findViewById<TextView>(R.id.calendar_next_title).text)
            assertEquals(
                "January 2027",
                root.findViewById<TextView>(R.id.calendar_next_title).contentDescription,
            )
            // Ensure the launcher's static preview is also a valid RemoteViews layout.
            android.widget
                .RemoteViews(context.packageName, R.layout.widget_calendar_preview)
                .apply(context, FrameLayout(context))
            val current =
                CalendarWidgets.render(context, LocalDate.of(2026, 9, 29))
                    .apply(context, FrameLayout(context))
            measure(current, 360, 220)
            savePreview(current, "calendar-current.png")
        }
    }

    @Test
    fun calendarTapOpensAppWithoutRequestingNoteCreation() {
        val intent = CalendarWidgets.launchIntent(context)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(WidgetAction.from(intent))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[AppState::class.java]
                assertFalse(state.newNoteRequested)
                val root =
                    CalendarWidgets.render(activity, LocalDate.now())
                        .apply(activity, FrameLayout(activity))
                assertTrue(root.findViewById<View>(R.id.calendar_widget).performClick())
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(ViewModelProvider(activity)[AppState::class.java].newNoteRequested)
            }
        }
    }

    private fun measure(view: View, width: Int, height: Int) {
        val density = context.resources.displayMetrics.density
        val w = (width * density).toInt()
        val h = (height * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
    }

    private fun savePreview(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(75, 125, 156))
        view.draw(canvas)
        File(context.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

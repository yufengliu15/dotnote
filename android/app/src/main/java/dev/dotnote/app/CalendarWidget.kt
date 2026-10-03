package dev.dotnote.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Monday-first, including empty cells before and after the month's dates. */
fun calendarWeeks(month: YearMonth): List<List<LocalDate?>> {
    val offset = month.atDay(1).dayOfWeek.value - 1
    val count = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    return List(count) { index ->
            val day = index - offset + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }
        .chunked(7)
}

fun nextCalendarMidnight(now: ZonedDateTime): ZonedDateTime =
    now.toLocalDate().plusDays(1).atStartOfDay(now.zone)

object CalendarWidgets {
    const val ACTION_REFRESH = "dev.dotnote.app.REFRESH_CALENDAR"
    private val ink = Color.rgb(38, 40, 47)
    private val saturday = Color.rgb(74, 114, 182)
    private val sunday = Color.rgb(198, 93, 121)

    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CalendarWidget::class.java))
        val today = LocalDate.now()
        ids.forEach { id ->
            manager.updateAppWidget(id, layouts(context, manager.getAppWidgetOptions(id), today))
        }
        schedule(context, ids.isNotEmpty())
    }

    fun layouts(
        context: Context,
        options: Bundle,
        today: LocalDate = LocalDate.now(),
    ): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes =
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                return RemoteViews(
                    sizes.distinct().take(16).associateWith {
                        render(context, today, it.width, it.height)
                    }
                )
            }
        }
        return RemoteViews(
            render(
                context,
                today,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 360).toFloat(),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160).toFloat(),
            ),
            render(
                context,
                today,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).toFloat(),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220).toFloat(),
            ),
        )
    }

    fun render(
        context: Context,
        today: LocalDate,
        width: Float = 360f,
        height: Float = 220f,
    ): RemoteViews {
        val locale = context.resources.configuration.locales[0]
        val current = YearMonth.from(today)
        val months = listOf(current, current.plusMonths(1))
        val rows = months.maxOf { calendarWeeks(it).size }
        val compact = height < 180f
        val fontSize =
            if (compact) {
                // Two-row launchers can offer as little as 110dp, including six-week months.
                val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
                ((height - 46f) / rows * 0.78f / fontScale).coerceIn(8f, 13f)
            } else if (width < 320f) 12f else if (width >= 440f) 16f else 14f
        val layout = if (compact) R.layout.widget_calendar_compact else R.layout.widget_calendar
        return RemoteViews(context.packageName, layout).apply {
            val density = context.resources.displayMetrics.density
            val horizontalPadding = (8 * density).toInt()
            val verticalPadding =
                ((if (compact) 4 else if (rows == 6) 14 else 20) * density).toInt()
            setViewPadding(
                R.id.calendar_widget,
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                verticalPadding,
            )
            setOnClickPendingIntent(
                R.id.calendar_widget,
                PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            months.forEachIndexed { index, month ->
                val title =
                    if (index == 0) R.id.calendar_current_title else R.id.calendar_next_title
                val weekdays =
                    if (index == 0) R.id.calendar_current_weekdays else R.id.calendar_next_weekdays
                val grid = if (index == 0) R.id.calendar_current_grid else R.id.calendar_next_grid
                setTextViewText(
                    title,
                    month.month.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                )
                setContentDescription(
                    title,
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
                )
                removeAllViews(weekdays)
                DayOfWeek.entries.forEach { day ->
                    addView(
                        weekdays,
                        cell(
                            context,
                            day.getDisplayName(TextStyle.NARROW, locale),
                            day.value - 1,
                            if (compact) 11f else fontSize - 1f,
                            compact = compact,
                            description = day.getDisplayName(TextStyle.FULL, locale),
                        ),
                    )
                }
                removeAllViews(grid)
                val weeks = calendarWeeks(month)
                repeat(rows) { rowIndex ->
                    val row = RemoteViews(context.packageName, R.layout.widget_calendar_week)
                    val dates = weeks.getOrNull(rowIndex) ?: List(7) { null }
                    dates.forEachIndexed { column, date ->
                        val description =
                            date?.format(
                                DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
                                    .withLocale(locale)
                            )
                        row.addView(
                            R.id.calendar_week,
                            cell(
                                context,
                                date?.dayOfMonth?.toString().orEmpty(),
                                column,
                                fontSize,
                                date == today,
                                if (date == today)
                                    context.getString(R.string.widget_calendar_today, description)
                                else description,
                                compact,
                            ),
                        )
                    }
                    addView(grid, row)
                }
            }
        }
    }

    private fun cell(
        context: Context,
        text: String,
        column: Int,
        size: Float,
        today: Boolean = false,
        description: String? = null,
        compact: Boolean = false,
    ): RemoteViews =
        RemoteViews(
                context.packageName,
                if (compact) R.layout.widget_calendar_cell_compact
                else R.layout.widget_calendar_cell,
            )
            .apply {
                setTextViewText(R.id.calendar_day, text)
                setTextViewTextSize(R.id.calendar_day, TypedValue.COMPLEX_UNIT_SP, size)
                setTextColor(
                    R.id.calendar_day,
                    if (today) Color.WHITE
                    else
                        when (column) {
                            5 -> saturday
                            6 -> sunday
                            else -> ink
                        },
                )
                setInt(
                    R.id.calendar_day,
                    "setBackgroundResource",
                    if (today) R.drawable.widget_calendar_today else 0,
                )
                setContentDescription(R.id.calendar_day, description)
            }

    fun schedule(context: Context, enabled: Boolean) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CalendarWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        if (enabled) {
            val midnight = nextCalendarMidnight(ZonedDateTime.now(ZoneId.systemDefault()))
            // Inexact, non-waking update: no alarms permission or overnight battery wakeup.
            alarm.setWindow(
                AlarmManager.RTC,
                midnight.toInstant().toEpochMilli(),
                600_000L,
                pending,
            )
        } else {
            alarm.cancel(pending)
            pending.cancel()
        }
    }
}

class CalendarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        CalendarWidgets.updateAll(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) = CalendarWidgets.updateAll(context)

    override fun onDisabled(context: Context) = CalendarWidgets.schedule(context, false)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (
            intent.action in
                setOf(
                    CalendarWidgets.ACTION_REFRESH,
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_LOCALE_CHANGED,
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                )
        ) {
            CalendarWidgets.updateAll(context)
        }
    }
}

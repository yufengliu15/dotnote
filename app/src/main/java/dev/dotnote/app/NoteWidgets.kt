package dev.dotnote.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors

const val ACTION_NEW_NOTE = "dev.dotnote.app.NEW_NOTE"
const val ACTION_OPEN_NOTE = "dev.dotnote.app.OPEN_NOTE"

data class WidgetAction(val vault: String? = null, val note: String? = null) {
    companion object {
        fun from(intent: Intent?): WidgetAction? =
            when (intent?.action) {
                ACTION_NEW_NOTE -> WidgetAction()
                ACTION_OPEN_NOTE -> {
                    val vault = intent.getStringExtra("vault")
                    val note = intent.getStringExtra("note")
                    if (vault != null && note != null && validId(vault) && validId(note))
                        WidgetAction(vault, note)
                    else null
                }
                else -> null
            }
    }
}

data class WidgetSpace(val columns: Int, val rows: Int) {
    val capacity
        get() = columns * rows
}

// 16dp top/bottom padding, 44dp header, 28dp section label, 56dp per note.
fun widgetSpace(width: Float, height: Float): WidgetSpace =
    WidgetSpace(if (width >= 440f) 2 else 1, ((height - 104f) / 56f).toInt().coerceIn(0, 16))

object NoteWidgets {
    private val executor = Executors.newSingleThreadExecutor()

    fun refresh(context: Context) {
        val app = context.applicationContext
        executor.execute { runCatching { updateAll(app) } }
    }

    fun updateAsync(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        executor.execute {
            try {
                runCatching { updateAll(app) }
            } finally {
                done()
            }
        }
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val vaults = VaultCatalog(context).list().associate { it.localId to it.name }
        val recent = RecentNotes(context).list().filter { it.vaultId in vaults }
        listOf(QuickNoteWidget::class.java, RecentNotesWidget::class.java).forEach { provider ->
            val quick = provider == QuickNoteWidget::class.java
            manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                manager.updateAppWidget(id, layouts(context, options, quick, recent, vaults))
            }
        }
    }

    fun launchIntent(context: Context, recent: RecentNote? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = if (recent == null) ACTION_NEW_NOTE else ACTION_OPEN_NOTE
            data =
                Uri.Builder()
                    .scheme("dotnote-widget")
                    .authority(if (recent == null) "new" else "open")
                    .apply {
                        if (recent != null) {
                            appendPath(recent.vaultId)
                            appendPath(recent.noteId)
                        }
                    }
                    .build()
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (recent != null) {
                putExtra("vault", recent.vaultId)
                putExtra("note", recent.noteId)
            }
        }

    private fun click(context: Context, recent: RecentNote? = null) =
        PendingIntent.getActivity(
            context,
            0,
            launchIntent(context, recent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun layouts(
        context: Context,
        options: Bundle,
        quick: Boolean,
        recent: List<RecentNote>,
        vaults: Map<String, String>,
    ): RemoteViews {
        if (quick)
            return RemoteViews(context.packageName, R.layout.widget_quick_note).apply {
                setOnClickPendingIntent(R.id.widget_quick, click(context))
            }
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes =
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                val mapping =
                    sizes.distinct().take(16).associateWith {
                        render(context, it.width, it.height, recent, vaults)
                    }
                return RemoteViews(mapping)
            }
        }
        val narrow = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).toFloat()
        val tall = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 280).toFloat()
        val wide = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 350).toFloat()
        val short = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).toFloat()
        return RemoteViews(
            render(context, wide, short, recent, vaults),
            render(context, narrow, tall, recent, vaults),
        )
    }

    fun render(
        context: Context,
        width: Float,
        height: Float,
        recent: List<RecentNote>,
        vaults: Map<String, String>,
    ): RemoteViews {
        val space = widgetSpace(width, height)
        return RemoteViews(context.packageName, R.layout.widget_recent_notes).apply {
            setOnClickPendingIntent(R.id.widget_create, click(context))
            removeAllViews(R.id.widget_rows)
            setViewVisibility(R.id.widget_section, if (space.rows > 0) View.VISIBLE else View.GONE)
            setViewVisibility(
                R.id.widget_empty,
                if (space.rows > 0 && recent.isEmpty()) View.VISIBLE else View.GONE,
            )
            recent.take(space.capacity).chunked(space.columns).forEach { entries ->
                val row = RemoteViews(context.packageName, R.layout.widget_note_row)
                entries.forEachIndexed { column, note ->
                    val box = if (column == 0) R.id.widget_first else R.id.widget_second
                    val title =
                        if (column == 0) R.id.widget_first_title else R.id.widget_second_title
                    val subtitle =
                        if (column == 0) R.id.widget_first_subtitle else R.id.widget_second_subtitle
                    row.setViewVisibility(box, View.VISIBLE)
                    row.setTextViewText(title, note.title)
                    row.setTextViewText(
                        subtitle,
                        "${vaults[note.vaultId] ?: "Vault"} · ${note.folder}",
                    )
                    row.setOnClickPendingIntent(box, click(context, note))
                }
                addView(R.id.widget_rows, row)
            }
        }
    }
}

open class BaseNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        NoteWidgets.updateAsync(context) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        val pending = goAsync()
        NoteWidgets.updateAsync(context) { pending.finish() }
    }
}

class QuickNoteWidget : BaseNoteWidget()

class RecentNotesWidget : BaseNoteWidget()

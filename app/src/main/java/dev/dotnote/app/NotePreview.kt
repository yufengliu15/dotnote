package dev.dotnote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun NotePreview(store: Store, note: NoteSummary, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(0xfff2f4ec))) {
        // Fixed small resolution; match the actual card aspect ratio without stretching.
        val width = 448
        val height = (width * maxHeight.value / maxWidth.value).roundToInt().coerceIn(1, 448)
        val key = PreviewKey(store.root.absolutePath, note.id, note.modified, width, height)
        key(key) {
            val preview by
                produceState<Bitmap?>(null, key) { value = NotePreviews.load(store, key) }
            preview?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

internal data class PreviewKey(
    val vault: String,
    val note: String,
    val modified: Long,
    val width: Int,
    val height: Int,
)

internal object NotePreviews {
    // Serialize decoding/rendering too, so rapid scrolling cannot load many large notes at once.
    private val mutex = Mutex()
    private val cache =
        object : LruCache<PreviewKey, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: PreviewKey, value: Bitmap) = value.allocationByteCount
        }

    suspend fun load(store: Store, key: PreviewKey): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(key)?.let {
                    return@withLock it
                }
                try {
                    val note = store.dao.note(key.note) ?: return@withLock null
                    if (note.modified != key.modified) return@withLock null
                    coroutineContext.ensureActive()
                    render(DocumentCodec.decode(note.document), store.assets, key.width, key.height)
                        .also { cache.put(key, it) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A preview failure must never prevent opening the original note.
                    null
                }
            }
        }

    internal suspend fun render(document: Document, assets: File, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(242, 244, 236))
            val bounds = document.bounds
            val firstPage =
                document.items
                    .firstOrNull { it.kind == "PDF" && !it.image }
                    ?.takeIf { bounds != null && bounds.height > bounds.width * 3f }
            val region = firstPage?.bounds ?: bounds
            val items =
                if (firstPage == null) document.items
                else
                    document.items.filter {
                        it.id == firstPage.id ||
                            (it.kind != "PDF" && it.bounds.intersects(firstPage.bounds))
                    }
            // Long PDF notes use one page; ordinary notes retain the full-scene snapshot.
            val camera =
                fittedCamera(
                    region,
                    width.toFloat(),
                    height.toFloat(),
                    padding = 16f,
                    minZoom = Float.MIN_VALUE,
                )
            val matrix = camera.matrix()
            canvas.concat(matrix)
            if (firstPage != null) canvas.clipRect(firstPage.bounds.rect())
            PdfPageSource(assets).use { source ->
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                for (item in items) {
                    coroutineContext.ensureActive()
                    if (item.kind != "PDF") continue
                    val page = source.render(item, (item.bounds.width * camera.zoom).roundToInt())
                    try {
                        canvas.drawBitmap(page, null, item.bounds.rect(), paint)
                    } finally {
                        page.recycle()
                    }
                }
            }
            coroutineContext.ensureActive()
            ObjectRenderer(vectorHighlights = false).drawScene(canvas, items, matrix)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
}

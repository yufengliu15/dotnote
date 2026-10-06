package dev.dotnote.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * Velocity-sensitive page scrubbing. A slow drag moves one page per [SLOW_STEP_DP]; at or above
 * [FAST_SPEED_DP] a drag the height of the canvas spans the whole document. Speeds in between
 * blend linearly, so faster swipes skip more pages.
 */
object PageScrub {
    const val SLOW_STEP_DP = 40f
    const val SLOW_SPEED_DP = 250f
    const val FAST_SPEED_DP = 1500f

    /** The scrubber hides after this long without panning, zooming or scrubbing. */
    const val HIDE_AFTER_MS = 2000L

    /** Pages advanced per dp of finger travel at [speedDp] dp/s. */
    fun gain(speedDp: Float, pageCount: Int, heightDp: Float): Float {
        val slow = 1f / SLOW_STEP_DP
        if (pageCount < 2 || heightDp <= 0f) return slow
        val fast = (pageCount - 1) / heightDp
        if (fast <= slow) return slow
        val t = ((abs(speedDp) - SLOW_SPEED_DP) / (FAST_SPEED_DP - SLOW_SPEED_DP)).coerceIn(0f, 1f)
        return slow + (fast - slow) * t
    }

    /** Index of the page whose vertical center is closest to [centerY] (world units). */
    fun nearest(pages: List<Bounds>, centerY: Float): Int =
        pages.indices.minByOrNull { abs((pages[it].top + pages[it].bottom) / 2 - centerY) } ?: 0
}

/** Locked PDF pages in reading order (top to bottom, then left to right). */
fun pdfPages(items: List<Item>): List<Item> =
    items.filter { it.locked }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

@Composable
fun BoxScope.PageScrubber(state: AppState, canvas: () -> NotebookView?) {
    val pages by remember(state) { derivedStateOf { pdfPages(state.document.items) } }
    if (pages.size < 2) return
    val config = LocalConfiguration.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var dragTarget by remember { mutableStateOf<Int?>(null) }
    val current by
        remember(state) {
            derivedStateOf {
                val view = canvas()
                val camera = state.document.camera
                val heightDp = view?.let { it.height / it.resources.displayMetrics.density }
                    ?: config.screenHeightDp.toFloat()
                val centerY = (heightDp / 2 - camera.y) / camera.zoom
                PageScrub.nearest(pdfPages(state.document.items).map { it.bounds }, centerY)
            }
        }
    // Any camera change (pan, zoom, fling, page jump) or touch on the scrubber restarts the
    // inactivity timer; it hides HIDE_AFTER_MS after the last one. Hidden, it takes no touches.
    // Observed with snapshotFlow so panning does not recompose this overlay every frame.
    var held by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        snapshotFlow { state.document.camera to held }
            .collectLatest { (_, touching) ->
                visible = true
                if (!touching) {
                    delay(PageScrub.HIDE_AFTER_MS)
                    visible = false
                }
            }
    }
    val shown = (dragTarget ?: current).coerceIn(0, pages.lastIndex)
    fun go(index: Int) {
        val target = index.coerceIn(0, pages.lastIndex)
        canvas()?.page(pages[target])
    }
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.TopEnd),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
    Surface(
        Modifier.padding(12.dp)
            .width(56.dp)
            .semantics { contentDescription = "PDF page ${shown + 1} of ${pages.size}" }
            .pointerInput(pages) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    held = true
                    try {
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val heightDp =
                            canvas()?.let { it.height / density } ?: config.screenHeightDp.toFloat()
                        var position = current.toFloat()
                        var moved = 0f
                        var dragging = false
                        val start = current
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!dragging) {
                                    // Tap: upper half goes back a page, lower half goes forward.
                                    go(if (change.position.y < size.height / 2) start - 1 else start + 1)
                                }
                                break
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val dy = change.positionChange().y
                            moved += abs(dy)
                            if (!dragging && moved > touchSlop) {
                                dragging = true
                                canvas()?.settle()
                            }
                            if (dragging) {
                                change.consume()
                                val speedDp = tracker.calculateVelocity().y / density
                                position =
                                    (position + dy / density * PageScrub.gain(speedDp, pages.size, heightDp))
                                        .coerceIn(0f, pages.lastIndex.toFloat())
                                val target = position.roundToInt()
                                if (target != dragTarget) {
                                    dragTarget = target
                                    go(target)
                                }
                            }
                        }
                    } finally {
                        dragTarget = null
                        held = false
                    }
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xfff7f7f2).copy(alpha = .94f),
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, Color(0xffe0e4da)),
    ) {
        Column(
            Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.KeyboardArrowUp, null, Modifier.size(20.dp), tint = Color(0xff255a4e))
            Text("${shown + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("of ${pages.size}", fontSize = 10.sp, color = Color(0xff6f776d))
            Spacer(Modifier.height(20.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(20.dp), tint = Color(0xff255a4e))
        }
    }
}
}

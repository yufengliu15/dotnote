package dev.dotnote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class LibraryDragItem(val id: String, val label: String, val folder: Boolean)

/** One pointer owns the lifted item until release/cancel, including across drop targets. */
internal class LibraryDragState {
    var item by mutableStateOf<LibraryDragItem?>(null)
    var position by mutableStateOf(Offset.Zero)
    val targets = mutableStateMapOf<String, Rect>()
    var allowed: (String?) -> Boolean = { true }
    val target: String?
        get() =
            targets.entries
                .lastOrNull {
                    it.value.contains(position) && allowed(it.key.takeUnless { key -> key == ROOT })
                }
                ?.key

    fun clear() {
        item = null
    }

    companion object {
        const val ROOT = "__root__"
    }
}

internal fun Modifier.libraryDropTarget(drag: LibraryDragState, id: String?): Modifier = composed {
    val key = id ?: LibraryDragState.ROOT
    DisposableEffect(drag, key) { onDispose { drag.targets.remove(key) } }
    this.onGloballyPositioned { drag.targets[key] = it.boundsInRoot() }
        .then(
            if (drag.item != null && drag.target == key)
                Modifier.border(2.dp, Color(0xff255a4e), RoundedCornerShape(16.dp))
            else Modifier
        )
}

internal fun Modifier.libraryDragSource(
    drag: LibraryDragState,
    item: LibraryDragItem,
    onDrop: (LibraryDragItem, String?) -> Unit,
): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val drop by rememberUpdatedState(onDrop)
    this.onGloballyPositioned { origin = it.boundsInRoot().topLeft }
        .pointerInput(drag, item) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    drag.position = origin + it
                    drag.item = item
                },
                onDragCancel = { drag.clear() },
                onDragEnd = {
                    val target = drag.target
                    drag.clear()
                    if (target != null)
                        drop(item, target.takeUnless { it == LibraryDragState.ROOT })
                },
                onDrag = { change, amount ->
                    change.consume()
                    drag.position += amount
                },
            )
        }
}

@Composable
internal fun LibraryDragPreview(drag: LibraryDragState, origin: Offset) {
    drag.item?.let { item ->
        Box(
            Modifier.offset {
                    IntOffset(
                        (drag.position.x - origin.x + 16).roundToInt(),
                        (drag.position.y - origin.y - 48).roundToInt(),
                    )
                }
                .background(Color(0xffdfebdf), RoundedCornerShape(12.dp))
                .border(2.dp, Color(0xff255a4e), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(item.label)
        }
    }
}

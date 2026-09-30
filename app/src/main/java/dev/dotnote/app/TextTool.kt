package dev.dotnote.app

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Drafts remain separate from the durable scene until the user saves. */
data class TextEditRequest(
    val noteId: String,
    val item: Item?,
    val position: Pt,
    val color: Int,
    val size: Float,
)

internal fun textLayout(
    content: String,
    size: Float,
    color: Int,
    width: Int? = null,
): StaticLayout {
    val paint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
        }
    val measured =
        width ?: ceil(Layout.getDesiredWidth(content, paint).toDouble()).toInt().coerceIn(1, 4096)
    return StaticLayout.Builder.obtain(content, 0, content.length, paint, measured)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(true)
        .build()
}

internal fun textItem(
    content: String,
    size: Float,
    color: Int,
    origin: Pt,
    existing: Item? = null,
): Item {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val layout = textLayout(normalized, size, existing?.color ?: color)
    val anchor = existing?.points?.firstOrNull() ?: origin
    return (existing ?: Item(kind = "TEXT", color = color)).copy(
        text = normalized,
        fontSize = size,
        points = listOf(anchor, Pt(anchor.x + layout.width, anchor.y + layout.height)),
    )
}

@Composable
internal fun TextEditorDialog(state: AppState, request: TextEditRequest) {
    var content by
        rememberSaveable(request.noteId, request.item?.id, request.position) {
            mutableStateOf(request.item?.text ?: "")
        }
    var size by
        rememberSaveable(request.noteId, request.item?.id, request.position) {
            mutableFloatStateOf(request.item?.fontSize ?: request.size)
        }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = state::dismissText,
        title = { Text(if (request.item == null) "Add text" else "Edit text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (it.length <= 10000) content = it },
                    label = { Text("Text") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text("Font size: ${size.toInt()}")
                Slider(
                    value = size,
                    onValueChange = { size = it.toInt().toFloat() },
                    valueRange = 8f..144f,
                )
                if (request.item != null)
                    TextButton(onClick = state::deleteText) { Text("Delete text") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = { state.applyText(content, size) },
            ) {
                Text("Save text")
            }
        },
        dismissButton = { TextButton(onClick = state::dismissText) { Text("Cancel") } },
    )
}

package dev.dotnote.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    val initial =
        remember(initialColor) {
            FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
        }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    fun color() = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    var hex by remember { mutableStateOf("%06X".format(initialColor and 0xffffff)) }
    fun syncHex() {
        hex = "%06X".format(color() and 0xffffff)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(
                    Modifier.size(220.dp)
                        .semantics { contentDescription = "Color wheel; hue and saturation" }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun select(position: Offset) {
                                    val delta = position - Offset(size.width / 2f, size.height / 2f)
                                    hue =
                                        ((atan2(delta.y, delta.x) * 180f / PI.toFloat()) + 360f) %
                                            360f
                                    saturation =
                                        (delta.getDistance() / (min(size.width, size.height) / 2f))
                                            .coerceIn(0f, 1f)
                                    syncHex()
                                }
                                select(down.position)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    select(change.position)
                                    change.consume()
                                } while (change.pressed)
                            }
                        }
                ) {
                    val radius = size.minDimension / 2
                    drawCircle(
                        Brush.sweepGradient(
                            listOf(
                                Color.Red,
                                Color.Yellow,
                                Color.Green,
                                Color.Cyan,
                                Color.Blue,
                                Color.Magenta,
                                Color.Red,
                            )
                        )
                    )
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color.White, Color.Transparent),
                            radius = radius,
                        )
                    )
                    drawCircle(Color.Black.copy(alpha = 1f - value))
                    val angle = hue * PI.toFloat() / 180f
                    val marker =
                        center +
                            Offset(cos(angle), sin(angle)) * (saturation * (radius - 3.dp.toPx()))
                    drawCircle(Color.Black, 7.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(Color(color()), CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text("Brightness · ${(value * 100).roundToInt()}%")
                }
                Slider(
                    value,
                    {
                        value = it
                        syncHex()
                    },
                    modifier = Modifier.semantics { contentDescription = "Brightness" },
                )
                OutlinedTextField(
                    hex,
                    { text ->
                        hex =
                            text
                                .filter { it.uppercaseChar() in "0123456789ABCDEF" }
                                .take(6)
                                .uppercase()
                        if (hex.length == 6) {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(
                                (0xff000000L or hex.toLong(16)).toInt(),
                                hsv,
                            )
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    },
                    label = { Text("Hex color") },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(color()) }, enabled = hex.length == 6) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

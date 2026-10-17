package dev.local.player.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Цветовой круг (HSV): угол — тон, радиус — насыщенность, нижний ползунок — яркость.
 * Выдаёт выбранный цвет через [onPick].
 */
@Composable
fun ColorWheel(initial: Color, onPick: (Color) -> Unit) {
    val hsv = remember { FloatArray(3) }
    remember(initial) {
        java.awt.Color.RGBtoHSB(
            (initial.red * 255).toInt(), (initial.green * 255).toInt(), (initial.blue * 255).toInt(), hsv,
        )
        true
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2].coerceAtLeast(0.3f)) }
    var sizePx by remember { mutableStateOf(0f) }

    fun emit() {
        onPick(Color(java.awt.Color.HSBtoRGB(hue, sat, value)))
    }

    fun pick(pos: Offset) {
        val c = sizePx / 2f
        if (c <= 0f) return
        val dx = pos.x - c
        val dy = pos.y - c
        val r = hypot(dx, dy)
        val radius = c
        sat = (r / radius).coerceIn(0f, 1f)
        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (deg < 0) deg += 360f
        hue = deg / 360f
        emit()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(180.dp)) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { sizePx = it.width.toFloat() }
                    .pointerInput(Unit) { detectTapGestures { pick(it) } }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { pick(it) }) { ch, _ -> pick(ch.position) }
                    },
            ) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                // Тон по кругу
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                            Color.Blue, Color.Magenta, Color.Red,
                        ),
                        center = center,
                    ),
                    radius = radius, center = center,
                )
                // Насыщенность: белый в центре → прозрачный к краю
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White, Color.Transparent), center = center, radius = radius,
                    ),
                    radius = radius, center = center,
                )
                // Яркость: затемнение по значению
                drawCircle(Color.Black.copy(alpha = 1f - value), radius = radius, center = center)

                // Маркер выбранного цвета
                val mr = sat * radius
                val ang = Math.toRadians((hue * 360f).toDouble())
                val mx = center.x + (mr * cos(ang)).toFloat()
                val my = center.y + (mr * sin(ang)).toFloat()
                drawCircle(Color.White, radius = 7f, center = Offset(mx, my))
                drawCircle(Color.Black, radius = 7f, center = Offset(mx, my), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Яркость", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = value,
                onValueChange = { value = it; emit() },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.width(180.dp).padding(start = 8.dp),
            )
        }
    }
}

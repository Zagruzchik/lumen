package app.lumen.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Настоящее стекло: размытие того, что под панелью (Android 12+), на старых — полупрозрачная подложка. */
fun Modifier.glassSource(state: HazeState): Modifier = this.hazeSource(state)

fun Modifier.glassBlur(state: HazeState, tint: Color = Color(0x660E0A16)): Modifier {
    val t = app.lumen.player.ui.theme.ThemeStore.current
    // Тема без размытия (AMOLED): просто плотная подложка — ноль работы для видеочипа.
    if (t.blur <= 0.5f) return this.background(t.panelTint)
    return this.hazeEffect(
        state = state,
        style = HazeStyle(
            backgroundColor = t.background,
            tints = listOf(HazeTint(if (tint == DEFAULT_PANEL) t.panelTint else tint)),
            blurRadius = t.blur.dp,
            noiseFactor = t.noise,
        ),
    ) {
        // Размываем уменьшенную вдвое картинку: на радиусе 28dp разницы не видно, а работы в 4 раза меньше.
        inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.5f)
    }
}

/** Цвет «по умолчанию» для панелей — заменяется цветом из темы. */
val DEFAULT_PANEL = Color(0x8C281630)

/** Волнистая полоса прогресса в стиле Android 13: волна «бежит», пока играет музыка. */
@Composable
fun WavySlider(
    fraction: Float,
    playing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val seek by rememberUpdatedState(onSeek)
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "phase",
    )
    val amp by animateFloatAsState(if (playing && drag == null) 1f else 0f, label = "amp")

    Canvas(
        modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { seek(it) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    change.consume()
                    drag = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    ) {
        val w = size.width
        val cy = size.height / 2
        val x = w * shown
        val stroke = 3.5.dp.toPx()
        val wl = 26.dp.toPx()
        val a = 3.5.dp.toPx() * amp
        // Волна плавно «вырастает» у начала и затухает у ползунка — без рывка и кривого старта.
        val ramp = wl * 0.75f
        val path = Path().apply {
            moveTo(0f, cy)
            var px = 0f
            while (px < x) {
                px = min(px + 1.5f, x)
                val env = min(1f, px / ramp) * min(1f, (x - px) / ramp)
                val y = cy + a * env * sin(((px / wl) - phase) * 2 * PI).toFloat()
                lineTo(px, y)
            }
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        val restStart = x + 10.dp.toPx()
        if (restStart < w) {
            drawLine(color.copy(alpha = 0.3f), Offset(restStart, cy), Offset(w, cy), stroke, StrokeCap.Round)
        }
        drawRoundRect(
            color,
            topLeft = Offset(x - 3.dp.toPx(), cy - 10.dp.toPx()),
            size = Size(6.dp.toPx(), 20.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}

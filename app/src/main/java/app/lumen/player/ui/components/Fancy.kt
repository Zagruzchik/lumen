package app.lumen.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent

/**
 * Текст, у которого меняющиеся символы «перетекают»: старая цифра уходит вверх и тает,
 * новая выплывает снизу. Одинаковые символы стоят на месте.
 */
@Composable
fun RollingText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    val style = TextStyle(fontSize = fontSize, color = color, fontWeight = fontWeight, fontFeatureSettings = "tnum")
    Row(modifier) {
        // Ключ — позиция с конца: так при смене 9:59 → 10:00 секунды не «прыгают».
        text.forEachIndexed { i, ch ->
            val fromEnd = text.length - 1 - i
            androidx.compose.runtime.key(fromEnd) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        (slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(380))) togetherWith
                            (slideOutVertically(tween(380, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(260)))
                    },
                    label = "roll",
                ) { c -> Text(c.toString(), style = style) }
            }
        }
    }
}

/**
 * Круглая стеклянная кнопка: матовый градиент, светлый блик сверху, тонкий
 * переливающийся край; активная — подсвечивается акцентом.
 */
@Composable
fun GlassRoundButton(icon: ImageVector, label: String, active: Boolean, size: Dp = 60.dp, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "glow")
    val tint by animateColorAsState(if (active) accent else LC.Text, tween(300), label = "tint")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .pressBounce(interaction)
                .drawBehind {
                    // Мягкое свечение под активной кнопкой.
                    if (glow > 0.01f) {
                        drawCircle(
                            Brush.radialGradient(listOf(accent.copy(alpha = 0.45f * glow), Color.Transparent), center, this.size.minDimension * 0.85f),
                            this.size.minDimension * 0.85f,
                        )
                    }
                }
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.20f + 0.06f * glow),
                            Color.White.copy(alpha = 0.06f),
                            accent.copy(alpha = 0.10f * glow + 0.02f),
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), accent.copy(alpha = 0.25f + 0.4f * glow))),
                    CircleShape,
                )
                .drawBehind {
                    // Блик-полумесяц сверху, как на стекле.
                    drawOval(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), startY = 0f, endY = this.size.height * 0.45f),
                        topLeft = Offset(this.size.width * 0.18f, this.size.height * 0.06f),
                        size = Size(this.size.width * 0.64f, this.size.height * 0.36f),
                    )
                }
                .clickable(interaction, null, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(size * 0.4f), tint = tint)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint.copy(alpha = 0.9f), maxLines = 1)
    }
}

/**
 * Простой ползунок-«комета»: серая дорожка, белая заливка, за головой — светящийся хвост.
 * Перемотка относительная: неважно, где коснулся, — полоса едет вслед за движением пальца.
 */
@Composable
fun CometSlider(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Свежие значения для жеста (сам жест создаётся один раз).
    val frac by rememberUpdatedState(fraction.coerceIn(0f, 1f))
    val seekState by rememberUpdatedState(onSeek)
    var drag by remember { mutableStateOf<Float?>(null) }
    // После отпускания держим выбранное место, пока плеер не догонит, — чтобы полоса не дёргалась назад.
    var pending by remember { mutableStateOf<Float?>(null) }
    androidx.compose.runtime.LaunchedEffect(pending) {
        if (pending != null) {
            kotlinx.coroutines.delay(1500)
            pending = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(fraction) {
        val p = pending
        if (p != null && kotlin.math.abs(fraction - p) < 0.01f) pending = null
    }
    val shown = drag ?: pending ?: fraction.coerceIn(0f, 1f)
    val thick by animateFloatAsState(if (drag != null) 1f else 0f, spring(dampingRatio = 0.7f), label = "thick")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // Касание само по себе ничего не перематывает: важно только движение пальца.
                    var moved = false
                    var travelled = 0f
                    var value = frac
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.position.x - ch.previousPosition.x
                        ch.consume()
                        if (!moved) {
                            travelled += kotlin.math.abs(dx)
                            if (travelled > viewConfiguration.touchSlop / 2f) {
                                moved = true
                                value = frac
                                drag = value
                            }
                            continue
                        }
                        value = (value + dx / size.width).coerceIn(0f, 1f)
                        drag = value
                    }
                    if (moved) {
                        pending = value
                        seekState(value)
                    }
                    drag = null
                }
            }
    ) {
        // Прямая полоса без скруглений; всё свечение — только внутри неё.
        val h = 6.dp.toPx() + 3.dp.toPx() * thick
        val y = size.height / 2f - h / 2f
        drawRect(Color.White.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, h))
        val head = size.width * shown
        if (head > 0f) {
            // Пройденная часть — бело-серая.
            drawRect(Color(0xFFCFCFD6), Offset(0f, y), Size(head, h))
            // Хвост кометы: короткий плавный переход от бело-серого к ярко-белой голове.
            val tail = minOf(head, 46.dp.toPx())
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color(0xFFCFCFD6), Color.White),
                    startX = head - tail,
                    endX = head,
                ),
                Offset(head - tail, y),
                Size(tail, h),
            )
        }
    }
}

/** Нажатие «проседает» и пружинит обратно. */
@Composable
fun Modifier.pressBounce(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 700f),
        label = "press",
    )
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

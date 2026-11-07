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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val seekState by rememberUpdatedState(onSeek)
    val shown = if (dragging) dragFrac else fraction.coerceIn(0f, 1f)
    val thick by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.7f), label = "thick")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragFrac = fraction.coerceIn(0f, 1f)
                        dragging = true
                    },
                    onDragEnd = {
                        seekState(dragFrac)
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                ) { change, dx ->
                    change.consume()
                    dragFrac = (dragFrac + dx / size.width).coerceIn(0f, 1f)
                }
            }
    ) {
        val h = (4.dp.toPx() + 4.dp.toPx() * thick)
        val y = size.height / 2f - h / 2f
        val r = CornerRadius(h / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
        val head = size.width * shown
        if (head > 0f) {
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(0f, y), Size(head, h), r)
            // Хвост кометы: от ярко-белой головы назад угасает до обычного белого.
            val tail = (size.width * 0.28f).coerceAtMost(head)
            drawRoundRect(
                Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0f), Color.White),
                    startX = head - tail,
                    endX = head,
                ),
                Offset(head - tail, y - h * 0.15f),
                Size(tail, h * 1.3f),
                CornerRadius(h * 0.65f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset(head, size.height / 2f), h * 3.2f),
                h * 3.2f,
                Offset(head, size.height / 2f),
            )
            drawCircle(Color.White, h * (0.75f + 0.25f * thick), Offset(head, size.height / 2f))
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

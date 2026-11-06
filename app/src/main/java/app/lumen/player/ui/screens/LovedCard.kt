package app.lumen.player.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.theme.LocalAccent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Акцент «обратной стороны луны» — холодный лунный голубой. */
val MoonAccent = Color(0xFF9CC8FF)

/**
 * Большая карточка «Самое любимое».
 * Тап — сразу играет музыку (плеер не открывается). Смахни вбок — карточка
 * переворачивается на «обратную сторону луны», и акцент всего приложения резко меняется.
 */
@Composable
fun LovedCard(
    moon: Boolean,
    trackCount: Int,
    onFlip: (Boolean) -> Unit,
    onPlay: (mostFirst: Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rotation by animateFloatAsState(
        if (moon) 180f else 0f,
        spring(dampingRatio = 0.72f, stiffness = 210f),
        label = "flip",
    )
    var drag by remember { mutableFloatStateOf(0f) }
    val tilt by animateFloatAsState(drag / 14f, spring(dampingRatio = 0.6f), label = "tilt")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "press")

    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(214.dp)
            .graphicsLayer {
                rotationY = rotation + tilt
                cameraDistance = 16f * density.density
                scaleX = press
                scaleY = press
            }
            .pointerInput(moon) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(drag) > 70f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onFlip(!moon)
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, d ->
                    change.consume()
                    drag = (drag + d).coerceIn(-260f, 260f)
                }
            }
            .clickable(interaction, null, role = Role.Button, onClickLabel = if (moon) "Играть от забытых к любимым" else "Играть самое любимое") {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlay(!moon)
            }
    ) {
        val showBack = (rotation % 360f) > 90f
        if (!showBack) {
            FrontFace(trackCount)
        } else {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { MoonFace(trackCount) }
        }
    }
}

@Composable
private fun FrontFace(trackCount: Int) {
    val accent = LocalAccent.current
    val t = rememberInfiniteTransition(label = "front")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "flow")
    val sheen by t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sheen")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    val shape = RoundedCornerShape(30.dp)
    val hot = Color(0xFFFF3D7F)
    val deep = Color(0xFF2A0B4A)

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // Цветное свечение вокруг карточки.
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), center, size.maxDimension * 0.75f),
                    radius = size.maxDimension * 0.75f,
                    center = center,
                )
            }
            .clip(shape)
            .drawBehind {
                // Переливающийся градиент: точки цвета медленно гуляют по кругу.
                drawRect(deep)
                val a = flow * 2f * PI.toFloat()
                blob(accent, Offset(size.width * (0.25f + 0.15f * cos(a)), size.height * (0.3f + 0.2f * sin(a))), size.width * 0.75f, 0.95f)
                blob(hot, Offset(size.width * (0.8f + 0.12f * sin(a * 1.3f)), size.height * (0.75f + 0.15f * cos(a))), size.width * 0.7f, 0.85f)
                blob(Color(0xFFFFC371), Offset(size.width * (0.95f - 0.1f * cos(a)), size.height * 0.05f), size.width * 0.45f, 0.6f)
                // Блик, пробегающий по стеклу.
                val x = size.width * sheen
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                        start = Offset(x - size.width * 0.25f, 0f),
                        end = Offset(x + size.width * 0.05f, size.height),
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))), shape)
    ) {
        Column(Modifier.padding(22.dp)) {
            Chip("ТВОЙ ТОП")
            Spacer(Modifier.height(12.dp))
            Text(
                "Самое\nлюбимое",
                fontSize = 36.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$trackCount треков · от любимых к забытым",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.78f),
            )
        }
        PlayOrb(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            ring = accent,
            pulse = pulse,
            iconTint = Color(0xFF2A0B4A),
        )
        Hint("смахни →", Modifier.align(Alignment.TopEnd).padding(18.dp))
    }
}

@Composable
private fun MoonFace(trackCount: Int) {
    val t = rememberInfiniteTransition(label = "moon")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "tw")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "drift")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    val shape = RoundedCornerShape(30.dp)
    val stars = remember {
        val r = java.util.Random(42)
        List(46) { Triple(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * 1.4f) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(MoonAccent.copy(alpha = 0.35f), Color.Transparent), center, size.maxDimension * 0.75f),
                    radius = size.maxDimension * 0.75f,
                    center = center,
                )
            }
            .clip(shape)
            .drawBehind {
                drawRect(Brush.linearGradient(listOf(Color(0xFF060A1C), Color(0xFF121C44), Color(0xFF070B1F))))
                // Звёзды мерцают.
                stars.forEachIndexed { i, (sx, sy, r) ->
                    val a = 0.35f + 0.65f * ((twinkle + i * 0.137f) % 1f)
                    drawCircle(Color.White.copy(alpha = a), r.dp.toPx(), Offset(sx * size.width, sy * size.height))
                }
                drawMoon(drift)
            }
            .border(1.dp, Brush.linearGradient(listOf(MoonAccent.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f))), shape)
    ) {
        Column(Modifier.padding(22.dp)) {
            Chip("ОБРАТНАЯ СТОРОНА", MoonAccent)
            Spacer(Modifier.height(12.dp))
            Text(
                "Забытое\nи редкое",
                fontSize = 36.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$trackCount треков · от забытых к любимым",
                fontSize = 13.sp,
                color = MoonAccent.copy(alpha = 0.85f),
            )
        }
        PlayOrb(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            ring = MoonAccent,
            pulse = pulse,
            iconTint = Color(0xFF0A1030),
        )
        Hint("← смахни", Modifier.align(Alignment.TopEnd).padding(18.dp))
    }
}

private fun DrawScope.blob(color: Color, c: Offset, r: Float, alpha: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), c, r), r, c)
}

private fun DrawScope.drawMoon(drift: Float) {
    val r = size.height * 0.62f
    val c = Offset(size.width * 0.86f, size.height * (0.62f + 0.03f * sin(drift * 2f * PI.toFloat())))
    drawCircle(Brush.radialGradient(listOf(MoonAccent.copy(alpha = 0.25f), Color.Transparent), c, r * 1.5f), r * 1.5f, c)
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFE8F1FF), Color(0xFFA9B8D6), Color(0xFF5D6A8C)), c - Offset(r * 0.3f, r * 0.3f), r * 1.4f),
        r,
        c,
    )
    // Кратеры.
    val craters = listOf(Triple(-0.35f, -0.2f, 0.18f), Triple(0.1f, 0.25f, 0.12f), Triple(-0.1f, 0.5f, 0.08f), Triple(0.3f, -0.35f, 0.1f))
    craters.forEach { (dx, dy, cr) ->
        drawCircle(Color(0x33223355), r * cr, c + Offset(r * dx, r * dy))
    }
    // Тень «обратной стороны».
    drawCircle(Color(0x99060A1C), r, c + Offset(-r * 0.45f, 0f), blendMode = BlendMode.Multiply)
}

@Composable
private fun PlayOrb(modifier: Modifier, ring: Color, pulse: Float, iconTint: Color) {
    Box(modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.4f
            drawCircle(ring.copy(alpha = (1f - pulse) * 0.6f), base + size.minDimension * 0.12f * pulse, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawCircle(ring.copy(alpha = 0.35f), base + 6.dp.toPx())
        }
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(Brush.linearGradient(listOf(Color.White, Color(0xFFEDE7FF)))) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(LIcons.Play, null, Modifier.size(28.dp).padding(start = 3.dp), tint = iconTint)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color = Color.White) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .drawBehind { drawRect(Color.White.copy(alpha = 0.16f)) }
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = color)
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
}


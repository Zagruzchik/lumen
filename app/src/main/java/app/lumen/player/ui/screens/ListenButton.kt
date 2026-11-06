package app.lumen.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.CircleContour
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.drawBeatContour
import app.lumen.player.ui.components.rememberBeatSignal
import app.lumen.player.ui.theme.CoverColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Большая кнопка «Слушать музыку».
 * Жидкий шар из цветов обложки (медленно переливается), стеклянный блик,
 * вокруг — неоновые волны под бит и «дышащие» кольца. Нажатие пружинит и пускает
 * расходящуюся волну. Играет «Самое любимое» — от любимых к забытым.
 */
@Composable
fun ListenButton(
    vm: MainViewModel,
    colors: CoverColors,
    playing: Boolean,
    hasQueue: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val active = app.lumen.player.ui.components.LocalHomeActive.current
    val beat = rememberBeatSignal(vm, active)
    val a by animateColorAsState(colors.a, tween(900), label = "la")
    val b by animateColorAsState(colors.b, tween(900), label = "lb")
    val c by animateColorAsState(colors.c, tween(900), label = "lc")
    val acc by animateColorAsState(colors.accent, tween(900), label = "lacc")

    // Непрерывные часы (секунды): у всех вращений и мерцаний нет «шва» на перезапуске цикла.
    val clock = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // Все движения кнопки — от одних часов (один источник кадров вместо четырёх).
    // Если главная закрыта плеером — часы стоят и ничего не перерисовывается.
    androidx.compose.runtime.LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val base = clock.floatValue
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { clock.floatValue = base + (it - start) / 1_000_000_000f }
    }
    // Сглаженная сила удара: быстро нарастает, медленно спадает — без рывков.
    val smooth = remember { floatArrayOf(0f, 0f) } // [уровень, прошлое время]
    val rayPaths = remember { List(4) { Path() } }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.45f, stiffness = 420f), label = "press")
    val tapWave = remember { Animatable(1f) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        Box(
            Modifier
                .size(side)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .clickable(interaction, null, role = Role.Button, onClickLabel = "Слушать музыку") {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        tapWave.snapTo(0f)
                        tapWave.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
                    }
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = center
                val now = clock.floatValue
                val spin = (now * 22.5f) % 360f
                val breath = 0.5f - 0.5f * cos(now * 2f * PI.toFloat() / 6.8f)
                val ringPhase = (now / 4.2f) % 1f
                val R = size.minDimension * 0.40f * (1f + 0.02f * breath)
                val dt = (now - smooth[1]).coerceIn(0f, 0.1f)
                smooth[1] = now
                val raw = beat.level().coerceIn(0f, 1f)
                val tau = if (raw > smooth[0]) 0.06f else 0.35f
                smooth[0] += (raw - smooth[0]) * (1f - kotlin.math.exp(-dt / tau))
                val lv = smooth[0]

                // 0. Лучи: лепестки света из-за шара. Плавно вращаются, каждый медленно
                // «дышит» длиной в своём ритме, на ударах мягко вытягиваются.
                val rays = 18
                val rot = now * 7f // градусов в секунду
                val pal = listOf(a, acc, c, b)
                val maxLen = R * 2.35f
                rayPaths.forEach { it.reset() }
                for (i in 0 until rays) {
                    val dir = ((i * 360f / rays) + rot) * PI.toFloat() / 180f
                    val breathe = 0.5f + 0.5f * sin(now * (0.55f + (i % 5) * 0.11f) + i * 2.17f)
                    val len = R * (1.4f + 0.4f * breathe + 0.45f * lv)
                    val half = (PI.toFloat() / rays) * (0.2f + 0.1f * breathe + 0.05f * lv)
                    val mid = R * 0.95f
                    val path = rayPaths[i % 4]
                    // Лепесток: узкий у центра, шире посередине, остриё на конце.
                    path.moveTo(cx.x, cx.y)
                    path.quadraticTo(
                        cx.x + cos(dir - half) * mid * 1.3f, cx.y + sin(dir - half) * mid * 1.3f,
                        cx.x + cos(dir) * len, cx.y + sin(dir) * len,
                    )
                    path.quadraticTo(
                        cx.x + cos(dir + half) * mid * 1.3f, cx.y + sin(dir + half) * mid * 1.3f,
                        cx.x, cx.y,
                    )
                    path.close()
                }
                val al = 0.34f + 0.26f * lv
                for (k in 0 until 4) {
                    val col = pal[k]
                    drawPath(
                        rayPaths[k],
                        Brush.radialGradient(
                            0f to col.copy(alpha = al),
                            0.5f to col.copy(alpha = al * 0.55f),
                            1f to Color.Transparent,
                            center = cx,
                            radius = maxLen,
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }

                // 1. Широкая аура из цвета акцента.
                drawCircle(
                    Brush.radialGradient(listOf(acc.copy(alpha = 0.32f + 0.08f * breath), Color.Transparent), cx, R * 2.1f),
                    R * 2.1f,
                    cx,
                )

                // 2. «Дышащие» кольца, расходящиеся от кнопки.
                for (k in 0 until 3) {
                    val p = (ringPhase + k / 3f) % 1f
                    val rr = R * (1.12f + 0.42f * p)
                    drawCircle(acc.copy(alpha = 0.22f * (1f - p)), rr, cx, style = Stroke(1.2.dp.toPx()))
                }

                // 3. Волна от нажатия.
                if (tapWave.value < 1f) {
                    val p = tapWave.value
                    drawCircle(Color.White.copy(alpha = 0.5f * (1f - p)), R * (1f + 0.7f * p), cx, style = Stroke((3f - 2f * p).dp.toPx()))
                }

                // 4. Неоновые волны под бит по контуру шара.
                drawBeatContour(
                    contour = CircleContour(cx, R * 1.06f),
                    sig = beat,
                    palette = listOf(a, acc, c, b),
                    spinDeg = spin,
                    strokeWidth = 2.6.dp.toPx(),
                    baseAmp = 1.4.dp.toPx(),
                    beatAmp = 7.dp.toPx(),
                    rippleAmp = 10.dp.toPx(),
                    shockGap = 30.dp.toPx(),
                    samples = 180,
                )

                // 5. Жидкий шар: тёмная база + цветные облака, которые кружат и перетекают.
                drawCircle(Color(0xFF0D0915), R, cx)
                listOf(a, b, c, acc).forEachIndexed { i, col ->
                    val ph = now * (0.39f + i * 0.11f) + i * 1.57f
                    val p = cx + Offset(cos(ph) * R * 0.38f, sin(ph) * R * 0.38f)
                    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.95f), col.copy(alpha = 0f)), p, R * 0.95f), R * 0.95f, p)
                }
                // Обрезаем всё, что вылезло за шар: затемняем снаружи круга тёмным кольцом.
                drawCircle(
                    Brush.radialGradient(
                        0.0f to Color.Transparent,
                        0.985f to Color.Transparent,
                        1.0f to Color(0xFF07070D),
                        center = cx,
                        radius = R,
                    ),
                    R,
                    cx,
                )
                // 6. Стеклянный блик сверху-слева и мягкая тень снизу.
                rotate(-30f, cx) {
                    drawOval(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), startY = cx.y - R * 0.95f, endY = cx.y - R * 0.2f),
                        topLeft = Offset(cx.x - R * 0.62f, cx.y - R * 0.92f),
                        size = androidx.compose.ui.geometry.Size(R * 1.24f, R * 0.8f),
                    )
                }
                drawCircle(
                    Brush.radialGradient(listOf(Color.Transparent, Color(0x66000000)), cx + Offset(0f, -R * 0.35f), R * 1.25f),
                    R,
                    cx,
                    blendMode = BlendMode.Multiply,
                )
                // 7. Тонкий стеклянный край.
                drawCircle(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f)), cx - Offset(R, R), cx + Offset(R, R)),
                    R,
                    cx,
                    style = Stroke(1.2.dp.toPx()),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = playing,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.6f)) togetherWith
                            (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.6f))
                    },
                    label = "listenIcon",
                ) { p ->
                    Icon(
                        if (p) LIcons.Pause else LIcons.Play,
                        null,
                        Modifier
                            .size(60.dp)
                            .padding(start = if (p) 0.dp else 5.dp),
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        playing -> "Играет"
                        hasQueue -> "Пауза"
                        else -> "Слушать"
                    },
                    fontSize = 27.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

package app.lumen.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.launch
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.BeatSignal
import app.lumen.player.ui.components.CircleContour
import app.lumen.player.ui.components.RoundRectContour
import app.lumen.player.ui.components.drawBeatContour
import app.lumen.player.ui.components.rememberBeatSignal
import app.lumen.player.ui.theme.CoverColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Акцент «обратной стороны луны» — холодный лунный голубой. */
val MoonAccent = Color(0xFF9CC8FF)

private val MoonColors = CoverColors(
    accent = MoonAccent,
    a = Color(0xFF6F8BFF),
    b = Color(0xFF2E3B9E),
    c = Color(0xFFCFE4FF),
)

/**
 * Главная «волна»: огромная неоновая панель почти на весь экран.
 * Цвета берутся из обложки играющего трека, рамка и шар пульсируют под бит.
 * Тап — сразу играет «Самое любимое» (повторный тап — пауза), плеер не открывается.
 * Смахни вбок — «обратная сторона луны»: от забытых к любимым, акцент приложения резко меняется.
 */
@Composable
fun LovedHero(
    vm: MainViewModel,
    moon: Boolean,
    trackCount: Int,
    colors: CoverColors,
    active: Boolean,
    playing: Boolean,
    onFlip: (Boolean) -> Unit,
    onPlay: (mostFirst: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val beat = rememberBeatSignal(vm)
    val scope = rememberCoroutineScope()
    // Угол поворота ведёт палец; «база» — угол текущей стороны (кратен 180°).
    val rot = remember { Animatable(if (moon) 180f else 0f) }
    var base by remember { mutableFloatStateOf(if (moon) 180f else 0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "press")

    fun side(): Float = if ((((base / 180f).roundToInt() % 2) + 2) % 2 == 1) -1f else 1f

    fun settle(velocity: Float, current: Float = rot.value) {
        val delta = current - base
        val dir = when {
            abs(velocity) > 900f -> if (velocity > 0f) 1f else -1f
            abs(delta) > 60f -> if (delta > 0f) 1f else -1f
            else -> 0f
        }
        val target = base + dir * 180f
        base = target
        // Без перелёта: пружина не проскакивает за край, и чужая сторона не мелькает даже на резком флике.
        val v0 = (velocity / width * 180f).coerceIn(-420f, 420f)
        scope.launch {
            rot.animateTo(target, spring(dampingRatio = 1f, stiffness = 170f), initialVelocity = v0)
        }
        if (dir != 0f) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val back = (((target / 180f).roundToInt() % 2) + 2) % 2 == 1
            onFlip(back)
        }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            // Над волной отключаем системный жест «назад» у краёв — иначе быстрый свайп от края его запускает.
            .systemGestureExclusion { c ->
                val h = c.size.height.toFloat()
                val band = minOf(h, 560f)
                androidx.compose.ui.geometry.Rect(0f, (h - band) / 2f, c.size.width.toFloat(), (h + band) / 2f)
            }
            .graphicsLayer {
                val r = rot.value
                rotationY = r
                cameraDistance = 48f * density.density
                // В середине поворота карточка чуть «уходит вглубь» — так переворот выглядит естественно.
                val depth = abs(sin(r * PI.toFloat() / 180f))
                val sc = press * (1f - 0.08f * depth)
                scaleX = sc
                scaleY = sc
            }
            .pointerInput(Unit) {
                // Свой жест: скорость считается с самого касания (быстрый флик тоже распознаётся точно),
                // поворот включается только при явно горизонтальном движении.
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var totalX = 0f
                    var totalY = 0f
                    var locked = false
                    var angle = rot.value
                    // На обороте мы смотрим на карточку «сзади» — направление зеркалится.
                    val dirSign = side()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        if (!ch.pressed) break
                        val d = ch.positionChange()
                        if (!locked) {
                            totalX += d.x
                            totalY += d.y
                            if (abs(totalY) > slop * 1.5f && abs(totalY) > abs(totalX)) break
                            if (abs(totalX) > slop) {
                                locked = true
                                angle = rot.value
                                scope.launch { rot.stop() }
                            } else continue
                        }
                        ch.consume()
                        angle = (angle + dirSign * d.x / width * 180f).coerceIn(base - 180f, base + 180f)
                        val next = angle
                        scope.launch { rot.snapTo(next) }
                    }
                    if (locked) settle(dirSign * tracker.calculateVelocity().x, angle)
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (moon) "Играть от забытых к любимым" else "Играть самое любимое",
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlay(!moon)
            }
    ) {
        val norm = ((rot.value % 360f) + 360f) % 360f
        val showBack = norm > 90f && norm < 270f
        val face: @Composable () -> Unit = {
            NeonFace(
                colors = if (showBack) MoonColors else colors,
                moonMode = showBack,
                beat = beat,
                title = if (showBack) "Обратная\nсторона" else "Самое\nлюбимое",
                subtitle = if (showBack) "$trackCount треков · от забытых к любимым" else "$trackCount треков · от любимых к забытым",
                hint = if (showBack) "← смахни обратно" else "смахни → обратная сторона",
                showPause = active && playing,
            )
        }
        if (!showBack) face() else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { face() }
    }
}

@Composable
private fun NeonFace(
    colors: CoverColors,
    moonMode: Boolean,
    beat: BeatSignal,
    title: String,
    subtitle: String,
    hint: String,
    showPause: Boolean,
) {
    val a by animateColorAsState(colors.a, tween(900), label = "na")
    val b by animateColorAsState(colors.b, tween(900), label = "nb")
    val c by animateColorAsState(colors.c, tween(900), label = "nc")
    val acc by animateColorAsState(colors.accent, tween(900), label = "nacc")
    val t = rememberInfiniteTransition(label = "neon")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "tw")
    val stars = remember {
        val r = java.util.Random(7)
        List(70) { Triple(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat() * 1.5f) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val orbCenterY = maxHeight * 0.40f
        Canvas(Modifier.fillMaxSize()) {
            val inset = 22.dp.toPx()
            val panelSize = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            val cornerPx = 38.dp.toPx()
            val corner = CornerRadius(cornerPx)
            val palette = listOf(a, acc, c, b)
            val fog = Offset(size.width / 2f, orbCenterY.toPx())

            // Тёмное «стекло» и мягкий цветной туман внутри.
            drawRoundRect(Color(0xE60A0812), topLeft = tl, size = panelSize, cornerRadius = corner)
            drawRoundRect(
                Brush.radialGradient(listOf(acc.copy(alpha = 0.2f + 0.05f * breath), Color.Transparent), fog, size.maxDimension * 0.7f),
                topLeft = tl,
                size = panelSize,
                cornerRadius = corner,
            )
            if (moonMode) {
                stars.forEachIndexed { i, (sx, sy, r) ->
                    val al = 0.25f + 0.75f * ((twinkle + i * 0.137f) % 1f)
                    drawCircle(Color.White.copy(alpha = al), r.dp.toPx(), Offset(tl.x + sx * panelSize.width, tl.y + sy * panelSize.height))
                }
            }

            // Волны по контуру панели под бит.
            drawBeatContour(
                contour = RoundRectContour(tl.x, tl.y, tl.x + panelSize.width, tl.y + panelSize.height, cornerPx),
                sig = beat,
                palette = palette,
                spinDeg = spin,
                strokeWidth = 2.6.dp.toPx(),
                baseAmp = 1.6.dp.toPx(),
                beatAmp = 7.dp.toPx(),
                rippleAmp = 11.dp.toPx(),
                shockGap = 18.dp.toPx(),
            )

            // Шар в центре.
            val R = min(size.width, size.height) * 0.22f * (1f + 0.025f * breath)
            drawCircle(
                Brush.radialGradient(listOf(acc.copy(alpha = 0.3f), Color.Transparent), fog, R * 2.3f),
                R * 2.3f,
                fog,
                blendMode = BlendMode.Plus,
            )
            if (moonMode) {
                drawMoonOrb(fog, R)
            } else {
                drawColorOrb(fog, R, spin * PI.toFloat() / 180f, a, b, c, acc, 0f)
            }
            // Волновое кольцо вокруг шара — как визуализатор.
            drawBeatContour(
                contour = CircleContour(fog, R * 1.3f),
                sig = beat,
                palette = palette,
                spinDeg = -spin * 1.4f,
                strokeWidth = 2.2.dp.toPx(),
                baseAmp = 1.2.dp.toPx(),
                beatAmp = 6.dp.toPx(),
                rippleAmp = 9.dp.toPx(),
                shockGap = 26.dp.toPx(),
                samples = 200,
            )
        }

        // Значок воспроизведения в центре шара.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = orbCenterY - 34.dp)
                .size(68.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = showPause,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.6f))
                },
                label = "heroIcon",
            ) { pause ->
                Icon(
                    if (pause) LIcons.Pause else LIcons.Play,
                    null,
                    Modifier.size(46.dp).padding(start = if (pause) 0.dp else 4.dp),
                    tint = Color.White,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 32.dp, end = 32.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontSize = 38.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(subtitle, fontSize = 13.sp, color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(hint, fontSize = 11.sp, color = Color.White.copy(alpha = 0.42f))
        }
    }
}

/** Переливающийся шар: три цветных облака из палитры обложки кружат вокруг яркого ядра. */
private fun DrawScope.drawColorOrb(c0: Offset, r: Float, ang: Float, a: Color, b: Color, c: Color, acc: Color, l: Float) {
    val cols = listOf(a, b, c)
    cols.forEachIndexed { i, col ->
        val ph = ang * (1f + i * 0.35f) + i * 2.09f
        val p = c0 + Offset(cos(ph) * r * 0.32f, sin(ph) * r * 0.32f)
        drawCircle(Brush.radialGradient(listOf(col, col.copy(alpha = 0f)), p, r * 1.05f), r * 1.05f, p)
    }
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f + 0.2f * l), acc.copy(alpha = 0.6f), Color.Transparent), c0, r * (0.62f + 0.12f * l)),
        r * (0.62f + 0.12f * l),
        c0,
        blendMode = BlendMode.Plus,
    )
}

/** Луна с кратерами для «обратной стороны». */
private fun DrawScope.drawMoonOrb(c0: Offset, r: Float) {
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFF2F7FF), Color(0xFFB3C3E3), Color(0xFF5C6A8F)), c0 - Offset(r * 0.3f, r * 0.3f), r * 1.4f),
        r,
        c0,
    )
    listOf(Triple(-0.35f, -0.2f, 0.18f), Triple(0.15f, 0.3f, 0.13f), Triple(-0.05f, 0.55f, 0.08f), Triple(0.35f, -0.35f, 0.1f), Triple(0.45f, 0.1f, 0.07f))
        .forEach { (dx, dy, cr) -> drawCircle(Color(0x33223355), r * cr, c0 + Offset(r * dx, r * dy)) }
    drawCircle(Color(0x8C060A1C), r, c0 + Offset(-r * 0.5f, 0f), blendMode = BlendMode.Multiply)
}

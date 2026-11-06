package app.lumen.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.playback.Beats
import app.lumen.player.ui.MainViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Живой «пульс» трека для отрисовки: позиция воспроизведения, досчитанная до кадра,
 * часы кадра и найденные удары. Всё читается в фазе рисования — перерисовывается только холст.
 */
@Stable
class BeatSignal {
    var posMs by mutableLongStateOf(0L)
    var timeMs by mutableLongStateOf(0L)
    var beats: Beats? by mutableStateOf(null)
    var intensity by mutableFloatStateOf(0.5f)
    var enabled by mutableStateOf(true)
    var playing by mutableStateOf(false)

    /** Текущая сила удара 0..1 с быстрым затуханием. */
    fun level(): Float {
        val b = beats ?: return 0f
        if (!enabled || !playing) return 0f
        val i = b.lastAtOrBefore(posMs)
        if (i < 0) return 0f
        val dt = (posMs - b.times[i]).toFloat()
        return if (dt < 700f) b.strengths[i] * exp(-dt / 170f) * intensity else 0f
    }

    /** Перебор недавних ударов: возраст в мс и сила. */
    inline fun recent(windowMs: Int, block: (age: Float, strength: Float) -> Unit) {
        val b = beats ?: return
        if (!enabled) return
        var i = b.lastAtOrBefore(posMs)
        while (i >= 0) {
            val age = (posMs - b.times[i]).toFloat()
            if (age > windowMs) break
            block(age, b.strengths[i] * intensity)
            i--
        }
    }
}

@Composable
fun rememberBeatSignal(vm: MainViewModel): BeatSignal {
    val sig = remember { BeatSignal() }
    val beats by vm.beats.collectAsStateWithLifecycle()
    val flashesOn by vm.beatFlashes.collectAsStateWithLifecycle()
    val intensity by vm.beatIntensity.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    SideEffect {
        sig.beats = beats
        sig.enabled = flashesOn
        sig.intensity = intensity
        sig.playing = p.isPlaying
    }
    LaunchedEffect(Unit) {
        var basePos = vm.position()
        var baseT = withFrameMillis { it }
        var lastPoll = baseT
        var wasPlaying = sig.playing
        while (true) {
            withFrameMillis { t ->
                sig.timeMs = t
                if (t - lastPoll > 300 || sig.playing != wasPlaying) {
                    basePos = vm.position()
                    baseT = t
                    lastPoll = t
                    wasPlaying = sig.playing
                }
                sig.posMs = if (sig.playing) basePos + (t - baseT) + 30 else basePos
            }
        }
    }
    return sig
}

// ======================= волны по контуру =======================

/** Замкнутый контур: точка и внешняя нормаль по параметру u ∈ [0, 1). */
interface Contour {
    /** Параметр «верхней точки», откуда стартуют волны удара. */
    val topU: Float
    fun at(u: Float, out: FloatArray)
}

class RoundRectContour(
    private val l: Float,
    private val t: Float,
    private val r: Float,
    private val b: Float,
    radius: Float,
) : Contour {
    private val rad = min(radius, min(r - l, b - t) / 2f)
    private val sw = (r - l) - 2 * rad
    private val sh = (b - t) - 2 * rad
    private val arc = (PI / 2).toFloat() * rad
    private val total = 2 * sw + 2 * sh + 4 * arc
    override val topU: Float = (sw / 2f) / total

    override fun at(u: Float, out: FloatArray) {
        var s = (((u % 1f) + 1f) % 1f) * total
        fun arcAt(cx: Float, cy: Float, start: Float, len: Float) {
            val th = start + len / rad
            val c = cos(th)
            val si = sin(th)
            out[0] = cx + rad * c; out[1] = cy + rad * si; out[2] = c; out[3] = si
        }
        if (s < sw) { out[0] = l + rad + s; out[1] = t; out[2] = 0f; out[3] = -1f; return }
        s -= sw
        if (s < arc) { arcAt(r - rad, t + rad, -(PI / 2).toFloat(), s); return }
        s -= arc
        if (s < sh) { out[0] = r; out[1] = t + rad + s; out[2] = 1f; out[3] = 0f; return }
        s -= sh
        if (s < arc) { arcAt(r - rad, b - rad, 0f, s); return }
        s -= arc
        if (s < sw) { out[0] = r - rad - s; out[1] = b; out[2] = 0f; out[3] = 1f; return }
        s -= sw
        if (s < arc) { arcAt(l + rad, b - rad, (PI / 2).toFloat(), s); return }
        s -= arc
        if (s < sh) { out[0] = l; out[1] = b - rad - s; out[2] = -1f; out[3] = 0f; return }
        s -= sh
        arcAt(l + rad, t + rad, PI.toFloat(), s.coerceAtMost(arc))
    }
}

class CircleContour(private val c: Offset, private val radius: Float) : Contour {
    override val topU: Float = 0f
    override fun at(u: Float, out: FloatArray) {
        val th = -(PI / 2).toFloat() + u * 2f * PI.toFloat()
        val cs = cos(th)
        val sn = sin(th)
        out[0] = c.x + radius * cs; out[1] = c.y + radius * sn; out[2] = cs; out[3] = sn
    }
}

private fun contourPath(c: Contour, n: Int, disp: (u: Float) -> Float): Path {
    val tmp = FloatArray(4)
    val path = Path()
    for (i in 0..n) {
        val u = i.toFloat() / n
        c.at(u, tmp)
        val d = disp(u)
        val x = tmp[0] + tmp[2] * d
        val y = tmp[1] + tmp[3] * d
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun circDist(a: Float, b: Float): Float {
    val d = abs(a - b) % 1f
    return min(d, 1f - d)
}

/**
 * Неоновый волновой контур под бит.
 * — три слоя волн разной частоты бегут по контуру, амплитуда растёт на ударах;
 * — каждый удар запускает две волны от верхней точки в разные стороны по периметру;
 * — на сильных ударах от контура наружу расходится тающее кольцо;
 * — у основной линии многослойный неоновый ореол.
 */
fun DrawScope.drawBeatContour(
    contour: Contour,
    sig: BeatSignal,
    palette: List<Color>,
    spinDeg: Float,
    strokeWidth: Float,
    baseAmp: Float,
    beatAmp: Float,
    rippleAmp: Float,
    shockGap: Float,
    samples: Int = 260,
) {
    val tSec = sig.timeMs / 1000f
    val lvl = sig.level()
    val ang = spinDeg * PI.toFloat() / 180f
    val rr = size.maxDimension * 0.6f
    val brush = Brush.linearGradient(
        palette + palette.first(),
        start = center + Offset(cos(ang) * rr, sin(ang) * rr),
        end = center - Offset(cos(ang) * rr, sin(ang) * rr),
    )

    // Волны удара: собираем недавние удары один раз за кадр.
    val ripples = ArrayList<Pair<Float, Float>>(6)
    sig.recent(1500) { age, str -> ripples += age to str }

    fun disp(u: Float, layer: Int, ampMul: Float): Float {
        val ph = layer * 0.21f
        val w = sin(2f * PI.toFloat() * (u * 6f + tSec * 0.22f + ph)) * 0.55f +
            sin(2f * PI.toFloat() * (u * 13f - tSec * 0.41f + ph * 1.7f)) * 0.3f +
            sin(2f * PI.toFloat() * (u * 27f + tSec * 0.83f + ph * 0.6f)) * 0.15f
        var d = w * (baseAmp + beatAmp * lvl) * ampMul
        for ((age, str) in ripples) {
            val k = age / 1500f
            val travel = k * 0.5f
            val height = str * rippleAmp * (1f - k) * (1f - k) * ampMul
            val sigma = 0.03f + 0.02f * k
            val a = circDist(u, contour.topU + travel) / sigma
            val b = circDist(u, contour.topU - travel) / sigma
            d += height * (exp(-a * a) + exp(-b * b))
        }
        return d
    }

    // Ударная волна наружу.
    sig.recent(800) { age, str ->
        if (str > 0.25f) {
            val k = age / 800f
            val ring = contourPath(contour, 120) { shockGap * k }
            drawPath(ring, brush, alpha = (str * (1f - k) * 0.55f).coerceIn(0f, 1f), style = Stroke(strokeWidth * (1.4f - k)))
        }
    }

    val main = contourPath(contour, samples) { u -> disp(u, 0, 1f) }
    val second = contourPath(contour, samples) { u -> disp(u, 1, 0.7f) }
    val third = contourPath(contour, samples) { u -> disp(u, 2, 1.35f) }

    val glowA = 0.55f + 0.45f * lvl
    drawPath(main, brush, alpha = 0.07f * glowA, style = Stroke(strokeWidth * 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(main, brush, alpha = 0.14f * glowA, style = Stroke(strokeWidth * 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(third, brush, alpha = 0.32f, style = Stroke(strokeWidth * 0.55f, join = StrokeJoin.Round))
    drawPath(second, brush, alpha = 0.55f, style = Stroke(strokeWidth * 0.75f, join = StrokeJoin.Round))
    drawPath(main, brush, alpha = 1f, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

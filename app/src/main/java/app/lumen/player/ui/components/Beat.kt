package app.lumen.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.ui.MainViewModel
import kotlin.math.exp

/**
 * Уровень «вспышки» 0..1, обновляется каждый кадр по заранее найденным ударам трека.
 * Позиция плеера опрашивается раз в 300 мс, между опросами — плавно досчитывается по часам кадра.
 */
@Composable
fun rememberBeatLevel(vm: MainViewModel): MutableFloatState {
    val level = remember { mutableFloatStateOf(0f) }
    val beats by vm.beats.collectAsStateWithLifecycle()
    val flashesOn by vm.beatFlashes.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    LaunchedEffect(beats, p.isPlaying, flashesOn) {
        val b = beats
        if (b == null || !flashesOn || b.size == 0) {
            level.floatValue = 0f
            return@LaunchedEffect
        }
        val playing = p.isPlaying
        var basePos = vm.position()
        var baseT = withFrameMillis { it }
        var lastPoll = baseT
        while (true) {
            withFrameMillis { t ->
                if (t - lastPoll > 300) {
                    basePos = vm.position()
                    baseT = t
                    lastPoll = t
                }
                val pos = if (playing) basePos + (t - baseT) + 30 else basePos
                val i = b.lastAtOrBefore(pos)
                var lvl = 0f
                if (i >= 0) {
                    val dt = (pos - b.times[i]).toFloat()
                    if (dt < 700f) lvl = b.strengths[i] * exp(-dt / 170f)
                }
                level.floatValue = if (playing) lvl else level.floatValue * 0.85f
            }
        }
    }
    return level
}

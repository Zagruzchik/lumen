package app.lumen.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

fun speedLabel(s: Float): String = "%.2f".format(s).trimEnd('0').trimEnd('.', ',')

/** Тон в полутонах из множителя частоты. */
fun semitones(pitch: Float): Int = (12.0 * ln(pitch.toDouble()) / ln(2.0)).roundToInt()

private fun pitchOf(semi: Int): Float = 2.0.pow(semi / 12.0).toFloat()

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = LocalAccent.current,
    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

@Composable
private fun SectionLabel(text: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LocalAccent.current)
    }
}

/**
 * Скорость, тон и повтор фрагмента — для текущего трека, прямо из плеера.
 * Скорость и тон независимы: можно ускорить без «мультяшного» голоса или поменять тональность без ускорения.
 */
@Composable
fun SoundSheet(
    speed: Float,
    pitch: Float,
    ab: Pair<Long, Long>,
    position: () -> Long,
    onPlayback: (Float, Float) -> Unit,
    onAb: (Boolean) -> Unit,
    onClearAb: () -> Unit,
    onClose: () -> Unit,
    sheetModifier: Modifier = Modifier,
) {
    var vinyl by remember { mutableStateOf(kotlin.math.abs(speed - pitch) < 0.001f && speed != 1f) }
    val accent = LocalAccent.current
    BottomGlassSheet(onClose, sheetModifier) {
        Column(
            Modifier
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Text("Скорость и тон", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("Действуют на всё, что играет, пока не вернёшь как было.", fontSize = 13.sp, color = LC.Dim)

            SectionLabel("Скорость", "${speedLabel(speed)}×")
            Slider(
                value = speed,
                onValueChange = { v ->
                    val s = (v * 20).roundToInt() / 20f
                    onPlayback(s, if (vinyl) s else pitch)
                },
                valueRange = 0.5f..2f,
                colors = sliderColors(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f).forEach { v ->
                    LChip("${speedLabel(v)}×", kotlin.math.abs(speed - v) < 0.01f) { onPlayback(v, if (vinyl) v else pitch) }
                }
            }

            val semi = semitones(pitch)
            SectionLabel("Тон", if (semi == 0) "как в записи" else "${if (semi > 0) "+" else "−"}${kotlin.math.abs(semi)} ${pluralRu(kotlin.math.abs(semi), "полутон", "полутона", "полутонов").substringAfter(' ')}")
            Slider(
                value = semi.toFloat(),
                onValueChange = { v ->
                    vinyl = false
                    onPlayback(speed, pitchOf(v.roundToInt()))
                },
                valueRange = -12f..12f,
                steps = 23,
                enabled = !vinyl,
                colors = sliderColors(),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        vinyl = !vinyl
                        onPlayback(speed, if (vinyl) speed else 1f)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Как на пластинке", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Тон меняется вместе со скоростью", fontSize = 12.sp, color = LC.Dim)
                }
                Switch(
                    checked = vinyl,
                    onCheckedChange = { v -> vinyl = v; onPlayback(speed, if (v) speed else 1f) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent,
                        checkedBorderColor = accent,
                        uncheckedThumbColor = LC.Dim,
                        uncheckedTrackColor = Color(0x1FFFFFFF),
                        uncheckedBorderColor = Color(0x33FFFFFF),
                    ),
                )
            }
            if (speed != 1f || pitch != 1f) {
                PillButton("Вернуть как было", LIcons.Refresh, { vinyl = false; onPlayback(1f, 1f) }, Modifier.fillMaxWidth().padding(top = 6.dp), primary = false)
            }

            Spacer(Modifier.height(18.dp))
            Text("Повтор фрагмента A–B", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (ab.first >= 0 && ab.second > ab.first) "Крутится ${formatMs(ab.first)} — ${formatMs(ab.second)}. Сбросится при смене трека."
                else "Отметь начало и конец — кусок будет повторяться, пока не сбросишь. Удобно разучивать партию или слова.",
                fontSize = 13.sp,
                color = LC.Dim,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    if (ab.first >= 0) "A ${formatMs(ab.first)}" else "A — отсюда",
                    null,
                    { onAb(true) },
                    Modifier.weight(1f),
                    primary = ab.first < 0,
                )
                PillButton(
                    if (ab.second > 0) "B ${formatMs(ab.second)}" else "B — досюда",
                    null,
                    { if (position() > 0) onAb(false) },
                    Modifier.weight(1f),
                    primary = ab.first >= 0 && ab.second < 0,
                )
            }
            if (ab.first >= 0 || ab.second >= 0) {
                PillButton("Сбросить повтор", LIcons.Close, onClearAb, Modifier.fillMaxWidth().padding(top = 10.dp), primary = false)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Обрезка начала и конца трека: длинные вступления, тишина и «скрытые треки» в конце. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimSheet(
    t: TrackEntity,
    initial: Pair<Long, Long>?,
    isCurrent: Boolean,
    position: () -> Long,
    onSave: (Long, Long) -> Unit,
    onClose: () -> Unit,
    sheetModifier: Modifier = Modifier,
) {
    val dur = t.durationMs.coerceAtLeast(1000L).toFloat()
    var range by remember(t.uri) {
        mutableStateOf(
            (initial?.first ?: 0L).toFloat()..(initial?.second?.takeIf { it > 0 } ?: t.durationMs).toFloat().coerceAtMost(dur)
        )
    }
    LaunchedEffect(initial) {
        if (initial != null) range = initial.first.toFloat()..(initial.second.takeIf { it > 0 } ?: t.durationMs).toFloat()
    }
    val accent = LocalAccent.current
    BottomGlassSheet(onClose, sheetModifier) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.uri, Modifier.size(56.dp), corner = 14.dp, sizePx = 192)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Обрезать трек", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("${t.title} · ${t.artist}", fontSize = 13.sp, color = LC.Dim, maxLines = 1)
            }
        }
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                "Файл не меняется: Lumen просто начнёт и закончит трек там, где скажешь.",
                fontSize = 13.sp,
                color = LC.Dim,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Начало", fontSize = 12.sp, color = LC.Faint)
                    Text(formatMs(range.start.toLong()), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Звучит", fontSize = 12.sp, color = LC.Faint)
                    Text(formatMs((range.endInclusive - range.start).toLong()), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Конец", fontSize = 12.sp, color = LC.Faint)
                    Text(formatMs(range.endInclusive.toLong()), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            RangeSlider(
                value = range,
                onValueChange = { r ->
                    // Не даём сжать трек меньше чем до 5 секунд.
                    if (r.endInclusive - r.start >= 5000f) range = (r.start / 500).roundToInt() * 500f..(r.endInclusive / 500).roundToInt() * 500f
                },
                valueRange = 0f..dur,
                colors = sliderColors(),
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (isCurrent) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Начало — сейчас", null, {
                        val p = position().toFloat()
                        if (range.endInclusive - p >= 5000f) range = p..range.endInclusive
                    }, Modifier.weight(1f), primary = false)
                    PillButton("Конец — сейчас", null, {
                        val p = position().toFloat()
                        if (p - range.start >= 5000f) range = range.start..p
                    }, Modifier.weight(1f), primary = false)
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Без обрезки", null, { onSave(0L, 0L); onClose() }, Modifier.weight(1f), primary = false)
                PillButton("Сохранить", LIcons.Check, { onSave(range.start.toLong(), range.endInclusive.toLong()); onClose() }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

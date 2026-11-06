package app.lumen.player.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.data.ListenSummary
import app.lumen.player.ui.ListenStats
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.Nav
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «2 ч 15 мин», «45 мин», «38 сек». */
fun humanDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "$h ч $m мин"
        m > 0 -> if (m < 10 && s > 0) "$m мин $s сек" else "$m мин"
        else -> "$s сек"
    }
}

@Composable
fun StatsScreen(vm: MainViewModel, nav: Nav, bottomPad: Dp) {
    var stats by remember { mutableStateOf<ListenStats?>(null) }
    LaunchedEffect(Unit) { stats = vm.loadStats() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val accent = LocalAccent.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 12.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(LIcons.Back, "Назад", nav.back)
                Spacer(Modifier.width(14.dp))
                Text("Статистика", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        val s = stats
        if (s == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            }
            return@LazyColumn
        }

        // --- герой: сколько всего слушал ---
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(26.dp))
                    .padding(22.dp)
            ) {
                Text("ВСЕГО ПРОСЛУШАНО", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = accent)
                Spacer(Modifier.height(6.dp))
                val h = s.all.total / 3_600_000
                val m = (s.all.total % 3_600_000) / 60_000
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$h", fontSize = 56.sp, fontWeight = FontWeight.Black, lineHeight = 56.sp)
                    Text(" ч ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LC.Dim, modifier = Modifier.padding(bottom = 8.dp))
                    Text("$m", fontSize = 56.sp, fontWeight = FontWeight.Black, lineHeight = 56.sp)
                    Text(" мин", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LC.Dim, modifier = Modifier.padding(bottom = 8.dp))
                }
                s.firstListen?.let {
                    Text(
                        "с " + SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(it)) +
                            " · ${pluralRu(s.activeDays, "день", "дня", "дней")} с музыкой",
                        fontSize = 13.sp,
                        color = LC.Dim,
                    )
                }
            }
        }

        // --- плитки ---
        item {
            TileGrid(
                listOf(
                    "Запусков музыки" to s.playStarts.toString(),
                    "Прослушиваний треков" to s.all.count.toString(),
                    "Разных треков" to "${s.all.uniqueTracks} из ${s.libraryTracks}",
                    "Дослушано до конца" to percent(s.all.completedCount, s.all.count),
                    "В любимом" to s.favorites.toString(),
                    "Серия дней подряд" to "${s.currentStreak} · рекорд ${s.longestStreak}",
                )
            )
        }

        // --- время одного прослушивания ---
        item {
            Card("Одно прослушивание") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Минимум", humanDuration(s.all.minMs))
                    Metric("Среднее", humanDuration(s.all.avgMs.toLong()))
                    Metric("Максимум", humanDuration(s.all.maxMs))
                }
            }
        }

        // --- периоды ---
        item {
            Card("По периодам") {
                PeriodRow("Сегодня", s.today)
                PeriodRow("7 дней", s.week)
                PeriodRow("30 дней", s.month)
            }
        }

        // --- по времени суток ---
        item {
            Card("Когда слушаешь") {
                val peak = s.byHour.indices.maxByOrNull { s.byHour[it] } ?: 0
                if (s.all.total > 0) {
                    Text("Пик — около $peak:00", fontSize = 13.sp, color = LC.Dim)
                    Spacer(Modifier.height(10.dp))
                }
                Bars(s.byHour.toList(), accent, highlight = peak, height = 90.dp)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "6", "12", "18", "23").forEach { Text(it, fontSize = 11.sp, color = LC.Faint) }
                }
            }
        }

        item {
            Card("Дни недели") {
                val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                val peak = s.byWeekday.indices.maxByOrNull { s.byWeekday[it] } ?: 0
                Bars(s.byWeekday.toList(), accent, highlight = peak, height = 70.dp)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    names.forEach { Text(it, fontSize = 11.sp, color = LC.Faint) }
                }
            }
        }

        // --- топы ---
        if (s.topTracks.isNotEmpty()) {
            item {
                Card("Топ треков") {
                    s.topTracks.forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (i < 3) accent else LC.Faint, modifier = Modifier.width(26.dp))
                            Cover(t.uri, Modifier.size(40.dp), corner = 10.dp, sizePx = 128)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.artist} · ${pluralRu(t.plays, "раз", "раза", "раз")}", fontSize = 12.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(humanDuration(t.totalMs), fontSize = 12.sp, color = LC.Dim)
                        }
                    }
                }
            }
        }
        if (s.topArtists.isNotEmpty()) {
            item {
                Card("Топ исполнителей") {
                    val max = s.topArtists.maxOf { it.totalMs }.coerceAtLeast(1)
                    s.topArtists.forEach { a ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row {
                                Text(a.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(humanDuration(a.totalMs), fontSize = 12.sp, color = LC.Dim)
                            }
                            Spacer(Modifier.height(4.dp))
                            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                                drawRoundRect(Color(0x1FFFFFFF), cornerRadius = CornerRadius(3.dp.toPx()))
                                drawRoundRect(
                                    accent,
                                    size = Size(size.width * a.totalMs / max, size.height),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (s.all.count == 0) {
            item {
                Text(
                    "Пока пусто — включи музыку, и здесь появится твоя статистика.",
                    fontSize = 14.sp,
                    color = LC.Dim,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

private fun percent(a: Int, b: Int) = if (b == 0) "—" else "${a * 100 / b}%"

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun TileGrid(tiles: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .glass(RoundedCornerShape(20.dp))
                            .padding(16.dp)
                    ) {
                        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(label, fontSize = 12.sp, color = LC.Dim)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, fontSize = 12.sp, color = LC.Dim)
    }
}

@Composable
private fun PeriodRow(label: String, s: ListenSummary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(humanDuration(s.total), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${pluralRu(s.count, "трек", "трека", "треков")} · ${s.uniqueTracks} разных", fontSize = 12.sp, color = LC.Dim)
        }
    }
}

@Composable
private fun Bars(values: List<Long>, accent: Color, highlight: Int, height: Dp) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val n = values.size
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val h = (size.height * v / max * grow.value).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (i == highlight && v > 0) accent else accent.copy(alpha = 0.35f),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

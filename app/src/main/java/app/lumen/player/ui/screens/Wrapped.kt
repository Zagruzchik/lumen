package app.lumen.player.ui.screens

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.Nav
import app.lumen.player.ui.WrappedData
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.pluralRu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val CARD_PALETTES = listOf(
    listOf(Color(0xFF7B2FF7), Color(0xFFFF5F6D), Color(0xFF1B0F2E)),
    listOf(Color(0xFFFF8A00), Color(0xFFE52E71), Color(0xFF2A0E1C)),
    listOf(Color(0xFF00C9A7), Color(0xFF3D5AFE), Color(0xFF07182A)),
    listOf(Color(0xFFFFD166), Color(0xFFEF476F), Color(0xFF2A1310)),
    listOf(Color(0xFF6A82FB), Color(0xFFFC5C7D), Color(0xFF130E2A)),
    listOf(Color(0xFF43E97B), Color(0xFF38F9D7), Color(0xFF07211A)),
    listOf(Color(0xFFB98CFF), Color(0xFFFFB38A), Color(0xFF1A1226)),
)

private const val PAGES = 7
private const val PAGE_MS = 7000

/** «Твой месяц в музыке»: истории-карточки в стиле итогов года, которыми можно поделиться. */
@Composable
fun WrappedScreen(vm: MainViewModel, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var monthsAgo by remember { mutableIntStateOf(0) }
    val data by produceState<WrappedData?>(null, monthsAgo) {
        value = null
        val d = vm.wrapped(monthsAgo)
        // В начале месяца данных мало — показываем прошлый месяц.
        value = if (monthsAgo == 0 && d.plays < 10) vm.wrapped(1).also { monthsAgo = 1 } else d
    }
    val pager = rememberPagerState { PAGES }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val layer = rememberGraphicsLayer()
    var sharing by remember { mutableStateOf(false) }

    // Автоперелистывание, как в историях; удержание — пауза.
    LaunchedEffect(pager.currentPage, paused, data) {
        if (data == null) return@LaunchedEffect
        if (paused) { progress.stop(); return@LaunchedEffect }
        progress.animateTo(1f, tween(((1f - progress.value) * PAGE_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
        if (pager.currentPage < PAGES - 1) {
            progress.snapTo(0f)
            pager.animateScrollToPage(pager.currentPage + 1, animationSpec = tween(450, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(pager.currentPage) { progress.snapTo(0f) }

    fun share() {
        if (sharing) return
        sharing = true
        scope.launch {
            runCatching {
                val bmp = layer.toImageBitmap().asAndroidBitmap()
                val uri = withContext(Dispatchers.IO) {
                    val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
                    val f = java.io.File(dir, "lumen-month.png")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                }
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Поделиться",
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            sharing = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07070D))) {
        val d = data
        if (d == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
        } else {
            HorizontalPager(
                state = pager,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                paused = true
                                tryAwaitRelease()
                                paused = false
                            },
                            onTap = { o ->
                                scope.launch {
                                    val target = if (o.x < size.width * 0.3f) pager.currentPage - 1 else pager.currentPage + 1
                                    if (target in 0 until PAGES) pager.animateScrollToPage(target, animationSpec = tween(400, easing = FastOutSlowInEasing))
                                }
                            },
                        )
                    },
            ) { page ->
                val active = pager.currentPage == page
                StoryCard(page) {
                    when (page) {
                        0 -> IntroCard(d, active)
                        1 -> MinutesCard(d, active)
                        2 -> TopArtistCard(vm, d, active)
                        3 -> TopTracksCard(d, active)
                        4 -> HoursCard(d, active)
                        5 -> DiscoveryCard(d, active)
                        else -> OutroCard(d, active)
                    }
                }
            }
            // Полоски историй и кнопки.
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(PAGES) { i ->
                        val f = when {
                            i < pager.currentPage -> 1f
                            i == pager.currentPage -> progress.value
                            else -> 0f
                        }
                        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Color.White))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(LIcons.Close, "Закрыть", nav.back, tint = Color.White)
                    Spacer(Modifier.weight(1f))
                    PillButton(
                        if (monthsAgo == 0) "Прошлый месяц" else "Этот месяц",
                        null,
                        { monthsAgo = if (monthsAgo == 0) 1 else 0; scope.launch { pager.scrollToPage(0) } },
                        primary = false,
                    )
                }
            }
            PillButton(
                if (sharing) "Готовлю…" else "Поделиться",
                LIcons.ArrowUp,
                { share() },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            )
        }
    }
}

/** Фон карточки: живые цветовые пятна, медленно плывущие по кругу. */
@Composable
private fun StoryCard(index: Int, content: @Composable () -> Unit) {
    val pal = CARD_PALETTES[index % CARD_PALETTES.size]
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - start) / 1_000_000_000f }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(pal[2])
            val r = size.maxDimension * 0.65f
            val a = Offset(size.width * (0.3f + 0.15f * sin(t * 0.35f)), size.height * (0.3f + 0.1f * cos(t * 0.3f)))
            val b = Offset(size.width * (0.75f + 0.12f * cos(t * 0.28f)), size.height * (0.72f + 0.1f * sin(t * 0.33f)))
            drawCircle(Brush.radialGradient(listOf(pal[0].copy(alpha = 0.85f), Color.Transparent), a, r), r, a)
            drawCircle(Brush.radialGradient(listOf(pal[1].copy(alpha = 0.75f), Color.Transparent), b, r), r, b)
            drawRect(Brush.verticalGradient(listOf(Color(0x33000000), Color.Transparent, Color(0x66000000))))
        }
        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 80.dp, bottom = 96.dp, start = 28.dp, end = 28.dp),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    }
}

/** Появление элемента: выезжает снизу и проявляется, с задержкой по порядку. */
@Composable
private fun Modifier.reveal(active: Boolean, order: Int): Modifier {
    val v by animateFloatAsState(if (active) 1f else 0f, tween(700, delayMillis = if (active) 150 + order * 140 else 0, easing = FastOutSlowInEasing), label = "rev")
    return this.graphicsLayer {
        alpha = v
        translationY = (1f - v) * 40.dp.toPx()
    }
}

@Composable
private fun countUp(target: Int, active: Boolean): Int {
    val v by animateFloatAsState(if (active) target.toFloat() else 0f, tween(1600, delayMillis = 300, easing = FastOutSlowInEasing), label = "cnt")
    return v.roundToInt()
}

@Composable
private fun Big(text: String, mod: Modifier = Modifier, size: Int = 44) =
    Text(text, fontSize = size.sp, lineHeight = (size * 1.08f).sp, fontWeight = FontWeight.Black, color = Color.White, modifier = mod)

@Composable
private fun Small(text: String, mod: Modifier = Modifier) =
    Text(text, fontSize = 18.sp, lineHeight = 24.sp, color = Color.White.copy(alpha = 0.85f), modifier = mod)

@Composable
private fun IntroCard(d: WrappedData, active: Boolean) {
    Column {
        Small("Lumen · итоги", Modifier.reveal(active, 0))
        Spacer(Modifier.height(10.dp))
        Big("Твой ${d.monthName}\nв музыке", Modifier.reveal(active, 1), 52)
        Spacer(Modifier.height(18.dp))
        Small(
            if (d.plays == 0) "Пока тихо — послушай что-нибудь, и здесь появится история" else "Смотри, что звучало чаще всего. Нажимай справа — дальше, слева — назад.",
            Modifier.reveal(active, 2),
        )
    }
}

@Composable
private fun MinutesCard(d: WrappedData, active: Boolean) {
    val minutes = (d.totalMs / 60000).toInt()
    Column {
        Small("Ты слушал музыку", Modifier.reveal(active, 0))
        Big(countUp(minutes, active).toString(), Modifier.reveal(active, 1), 96)
        Big(pluralRu(minutes, "минуту", "минуты", "минут").substringAfter(' '), Modifier.reveal(active, 1), 40)
        Spacer(Modifier.height(20.dp))
        Small("Это ${"%.1f".format(minutes / 60f)} ч — примерно ${(minutes / 110).coerceAtLeast(0)} полнометражных фильмов", Modifier.reveal(active, 2))
        Spacer(Modifier.height(8.dp))
        Small("${pluralRu(d.plays, "прослушивание", "прослушивания", "прослушиваний")} · ${pluralRu(d.activeDays, "день", "дня", "дней")} с музыкой", Modifier.reveal(active, 3))
        if (d.streak > 1) Small("Серия: ${pluralRu(d.streak, "день", "дня", "дней")} подряд 🔥", Modifier.reveal(active, 4))
    }
}

@Composable
private fun TopArtistCard(vm: MainViewModel, d: WrappedData, active: Boolean) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val top = d.topArtists.firstOrNull()
    Column {
        Small("Твой главный исполнитель", Modifier.reveal(active, 0))
        Spacer(Modifier.height(16.dp))
        if (top == null) {
            Big("Ещё впереди", Modifier.reveal(active, 1))
            return@Column
        }
        val cover = d.topTracks.firstOrNull { it.artist == top.name }?.uri ?: tracks.firstOrNull { it.artist == top.name }?.uri
        Cover(cover, Modifier.size(170.dp).reveal(active, 1), corner = 999.dp, sizePx = 512, seed = top.name)
        Spacer(Modifier.height(18.dp))
        Big(top.name, Modifier.reveal(active, 2), 40)
        Small("${top.totalMs / 60000} мин · ${pluralRu(top.plays, "прослушивание", "прослушивания", "прослушиваний")}", Modifier.reveal(active, 3))
        Spacer(Modifier.height(14.dp))
        d.topArtists.drop(1).forEachIndexed { i, a ->
            Text("${i + 2}. ${a.name}", fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(active, 4 + i))
        }
    }
}

@Composable
private fun TopTracksCard(d: WrappedData, active: Boolean) {
    Column {
        Small("Песни месяца", Modifier.reveal(active, 0))
        Spacer(Modifier.height(16.dp))
        if (d.topTracks.isEmpty()) Big("Скоро здесь будут хиты", Modifier.reveal(active, 1))
        d.topTracks.forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 7.dp).reveal(active, 1 + i), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.width(34.dp))
                Cover(t.uri, Modifier.size(if (i == 0) 72.dp else 56.dp), corner = 12.dp, sizePx = 256)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, fontSize = if (i == 0) 20.sp else 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.artist} · ${t.plays}×", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HoursCard(d: WrappedData, active: Boolean) {
    val h = d.peakHour
    val who = when (h) {
        in 5..10 -> "Жаворонок 🌅"
        in 11..16 -> "Дневной слушатель ☀️"
        in 17..21 -> "Вечерний меломан 🌆"
        else -> "Ночная сова 🦉"
    }
    val days = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")
    val grow by animateFloatAsState(if (active) 1f else 0f, tween(1200, delayMillis = 400, easing = FastOutSlowInEasing), label = "bars")
    val max = (d.byHour.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column {
        Small("Твоё время", Modifier.reveal(active, 0))
        Big(who, Modifier.reveal(active, 1), 40)
        Spacer(Modifier.height(8.dp))
        Small("Чаще всего музыка звучит около $h:00, а больше всего — в ${days[d.topWeekday]}", Modifier.reveal(active, 2))
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().height(140.dp).reveal(active, 3), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            d.byHour.forEachIndexed { i, v ->
                val f = (v.toFloat() / max).coerceAtLeast(0.03f) * grow
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(f)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (i == h) Color.White else Color.White.copy(alpha = 0.35f))
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, s ->
                Text(s, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f), textAlign = if (i == 4) TextAlign.End else TextAlign.Start)
            }
        }
    }
}

@Composable
private fun DiscoveryCard(d: WrappedData, active: Boolean) {
    Column {
        Small("Открытия", Modifier.reveal(active, 0))
        Big(countUp(d.newTracks, active).toString(), Modifier.reveal(active, 1), 96)
        Big(pluralRu(d.newTracks, "новый трек", "новых трека", "новых треков").substringAfter(' '), Modifier.reveal(active, 1), 36)
        Small("впервые зазвучали у тебя в этом месяце", Modifier.reveal(active, 2))
        d.topAlbum?.let { a ->
            Spacer(Modifier.height(28.dp))
            Small("Альбом месяца", Modifier.reveal(active, 3))
            Big(a.name, Modifier.reveal(active, 4), 32)
            Small("${a.totalMs / 60000} мин", Modifier.reveal(active, 5))
        }
    }
}

@Composable
private fun OutroCard(d: WrappedData, active: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Small("${d.monthName.replaceFirstChar { it.uppercase() }} · Lumen", Modifier.reveal(active, 0))
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .reveal(active, 1)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0x33000000))
                .padding(22.dp)
        ) {
            Column {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Минут", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                        Text("${d.totalMs / 60000}", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Новых треков", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                        Text("${d.newTracks}", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Топ исполнители", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                        d.topArtists.take(5).forEachIndexed { i, a ->
                            Text("${i + 1}. ${a.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Топ песни", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                        d.topTracks.take(5).forEachIndexed { i, t ->
                            Text("${i + 1}. ${t.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Small("Поделись — и сравни с друзьями", Modifier.reveal(active, 2))
    }
}

package app.lumen.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.lumen.player.data.LyricLine
import app.lumen.player.data.LyricsResult
import app.lumen.player.ui.AmbientBackground
import app.lumen.player.ui.AudioQuality
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.Badge
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.TrackActions
import app.lumen.player.ui.components.TrackMenu
import app.lumen.player.ui.components.WavySlider
import app.lumen.player.ui.components.formatMs
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.components.rememberArtwork
import app.lumen.player.ui.theme.CoverColors
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Размытая обложка на весь экран — фон для плеера и текста. */
@Composable
private fun CoverBackdrop(uri: String?, colors: CoverColors) {
    val bg = rememberArtwork(uri, 48)
    Box(Modifier.fillMaxSize().background(Color(0xFF12081A))) {
        if (bg != null) {
            Image(
                bitmap = bg,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(40.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.9f,
                filterQuality = FilterQuality.High,
            )
        } else {
            AmbientBackground(colors, strength = 0.85f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x330A0610), Color(0x8C0A0610), Color(0xE00A0610))
                    )
                )
        )
    }
}

private fun sampleRateLabel(sr: Int): String {
    val k = sr / 1000f
    return if (sr % 1000 == 0) "${sr / 1000} кГц" else "%.1f кГц".format(k).replace('.', ',')
}

@Composable
private fun QualityBadges(q: AudioQuality?, fallbackSampleRate: Int) {
    if (q == null) return
    val sr = if (q.sampleRate > 0) q.sampleRate else fallbackSampleRate
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(q.format)
        when {
            q.lossless && sr > 0 && q.bits > 0 -> Badge("${q.bits} бит · ${sampleRateLabel(sr)}")
            q.lossless && sr > 0 -> Badge(sampleRateLabel(sr))
            !q.lossless && q.bitrate > 0 -> Badge("${q.bitrate / 1000} кбит/с")
        }
        if (q.hiRes) Badge("HI-RES", strong = true)
    }
}

// ======================= ПЛЕЕР =======================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    vm: MainViewModel,
    colors: CoverColors,
    actions: TrackActions,
    onClose: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onTimer: () -> Unit,
) {
    val p by vm.player.collectAsStateWithLifecycle()
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val sleepAt by vm.sleepEndsAt.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val density = LocalDensity.current

    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(p.currentId, p.isPlaying) {
        while (true) {
            position = vm.position()
            delay(if (p.isPlaying) 250 else 800)
        }
    }

    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offsetY by animateFloatAsState(
        targetValue = dragY,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.8f),
        label = "dragY",
    )
    var menu by remember { mutableStateOf(false) }
    val closeThreshold = with(density) { 140.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = offsetY }
            .clip(RoundedCornerShape(if (offsetY > 1f) 28.dp else 0.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        if (dragY > closeThreshold) onClose()
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragY = 0f
                    },
                ) { change, d ->
                    change.consume()
                    dragY = max(0f, dragY + d)
                }
            }
    ) {
        CoverBackdrop(p.currentId, colors)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            // --- верхняя панель ---
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(LIcons.Down, "Свернуть плеер", onClose)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ИЗ АЛЬБОМА", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LC.Dim, letterSpacing = 1.2.sp)
                    Text(
                        p.album.ifBlank { "—" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = track != null) { track?.let { actions.onOpenAlbum(it) } }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Box {
                    IconBtn(LIcons.MoreH, "Меню трека", { menu = true })
                    track?.let { TrackMenu(menu, { menu = false }, it, it.uri in favs, actions) }
                }
            }

            // --- обложка со свечением ---
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                val side = min(maxWidth.value, maxHeight.value).dp
                val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
                    initialValue = 0.32f,
                    targetValue = 0.6f,
                    animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "pulse",
                )
                val glow = if (p.isPlaying) pulse else 0.25f
                var coverDrag by remember { mutableFloatStateOf(0f) }
                var coverDragging by remember { mutableStateOf(false) }
                val coverX by animateFloatAsState(
                    if (coverDragging) coverDrag else 0f,
                    if (coverDragging) snap() else spring(dampingRatio = 0.7f),
                    label = "coverX",
                )
                val swipeThreshold = with(density) { 90.dp.toPx() }
                Box(
                    Modifier
                        .size(side)
                        .drawBehind {
                            val c = center + Offset(0f, 26.dp.toPx())
                            val r = size.minDimension * 0.78f
                            drawCircle(
                                Brush.radialGradient(listOf(accent.copy(alpha = glow), Color.Transparent), c, r),
                                radius = r,
                                center = c,
                            )
                        }
                ) {
                    Cover(
                        p.currentId,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = coverX
                                rotationZ = coverX / 40f
                                alpha = 1f - min(0.4f, abs(coverX) / 1200f)
                            }
                            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(30.dp))
                            .pointerInput(p.currentId) {
                                detectHorizontalDragGestures(
                                    onDragStart = { coverDragging = true },
                                    onDragEnd = {
                                        if (coverDrag < -swipeThreshold) vm.next()
                                        else if (coverDrag > swipeThreshold) vm.previous()
                                        coverDragging = false
                                        coverDrag = 0f
                                    },
                                    onDragCancel = {
                                        coverDragging = false
                                        coverDrag = 0f
                                    },
                                ) { change, d ->
                                    change.consume()
                                    coverDrag += d
                                }
                            },
                        corner = 30.dp,
                        sizePx = 1024,
                        seed = p.currentId,
                    )
                }
            }

            // --- качество, название ---
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QualityBadges(quality, p.sampleRate)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p.title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        p.artist,
                        fontSize = 16.sp,
                        color = LC.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = track != null) { track?.let { actions.onOpenArtist(it) } },
                    )
                }
                val fav = p.currentId != null && p.currentId in favs
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x1FFFFFFF))
                        .clickable(role = Role.Button, onClickLabel = if (fav) "Убрать из любимого" else "В любимое") {
                            p.currentId?.let { vm.toggleFavorite(it) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (fav) LIcons.Heart else LIcons.HeartOutline, null, Modifier.size(24.dp), tint = if (fav) accent else LC.Text)
                }
            }

            // --- прогресс ---
            Spacer(Modifier.height(10.dp))
            val dur = p.durationMs
            WavySlider(
                fraction = if (dur > 0) position.toFloat() / dur else 0f,
                playing = p.isPlaying,
                onSeek = { f ->
                    vm.seek(f)
                    position = (dur * f).toLong()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatMs(position), fontSize = 12.sp, color = LC.Dim)
                Spacer(Modifier.weight(1f))
                Text("−" + formatMs(max(0L, dur - position)), fontSize = 12.sp, color = LC.Dim)
            }

            // --- кнопки управления ---
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(
                    LIcons.Shuffle,
                    if (p.shuffle) "Перемешивание включено" else "Перемешивание выключено",
                    { vm.toggleShuffle() },
                    glass = false,
                    size = 48.dp,
                    iconSize = 22.dp,
                    tint = if (p.shuffle) accent else LC.Dim,
                )
                IconBtn(LIcons.Prev, "Предыдущий трек", { vm.previous() }, glass = false, size = 60.dp, iconSize = 32.dp)
                Box(
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(LC.Text)
                        .clickable(role = Role.Button, onClickLabel = if (p.isPlaying) "Пауза" else "Играть") { vm.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (p.isPlaying) LIcons.Pause else LIcons.Play, null, Modifier.size(34.dp), tint = Color(0xFF140A1C))
                }
                IconBtn(LIcons.Next, "Следующий трек", { vm.next() }, glass = false, size = 60.dp, iconSize = 32.dp)
                IconBtn(
                    if (p.repeat == Player.REPEAT_MODE_ONE) LIcons.RepeatOne else LIcons.Repeat,
                    when (p.repeat) {
                        Player.REPEAT_MODE_ONE -> "Повтор трека"
                        Player.REPEAT_MODE_ALL -> "Повтор очереди"
                        else -> "Повтор выключен"
                    },
                    { vm.cycleRepeat() },
                    glass = false,
                    size = 48.dp,
                    iconSize = 22.dp,
                    tint = if (p.repeat != Player.REPEAT_MODE_OFF) accent else LC.Dim,
                )
            }

            // --- нижняя стеклянная панель ---
            Spacer(Modifier.height(18.dp))
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(sleepAt) {
                while (sleepAt != null) {
                    now = System.currentTimeMillis()
                    delay(15_000)
                }
            }
            val sleepLabel = sleepAt?.let { "${max(1L, (it - now + 59_999) / 60_000)} мин" } ?: "Таймер"
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .glass(RoundedCornerShape(24.dp), Color(0x1AFFFFFF), Color(0x2EFFFFFF)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarItem(LIcons.Lyrics, "Текст", false, onLyrics)
                BarItem(LIcons.Queue, "Очередь", false, onQueue)
                BarItem(LIcons.Moon, sleepLabel, sleepAt != null, onTimer)
                BarItem(LIcons.Disc, "Альбом", false) { track?.let { actions.onOpenAlbum(it) } }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val c = if (active) LocalAccent.current else LC.Text
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = c)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c)
    }
}

// ======================= ТЕКСТ ПЕСНИ =======================

@Composable
fun LyricsScreen(vm: MainViewModel, colors: CoverColors, onClose: () -> Unit) {
    val p by vm.player.collectAsStateWithLifecycle()
    val res by vm.lyrics.collectAsStateWithLifecycle()

    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(p.currentId, p.isPlaying) {
        while (true) {
            position = vm.position()
            delay(if (p.isPlaying) 120 else 600)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        CoverBackdrop(p.currentId, colors)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(68.dp)
                    .glass(RoundedCornerShape(22.dp), Color(0x1AFFFFFF), Color(0x2EFFFFFF))
                    .padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(p.currentId, Modifier.size(48.dp), corner = 13.dp, sizePx = 128)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(LIcons.Close, "Закрыть текст", onClose)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val r = res) {
                    LyricsResult.Idle, LyricsResult.Loading -> CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(36.dp),
                    )
                    is LyricsResult.Found -> LyricsList(r.lines, r.synced, position) { vm.seekTo(it) }
                    LyricsResult.NotFound -> LyricsMessage(
                        "Текст не найден",
                        "Для этого трека нет текста ни рядом с файлом, ни в LRCLIB.",
                        "Искать снова",
                    ) { vm.loadLyrics(force = true) }
                    LyricsResult.Instrumental -> LyricsMessage("Инструментал", "В этом треке нет слов — просто слушай.", null) {}
                    LyricsResult.Disabled -> LyricsMessage(
                        "Онлайн-поиск выключен",
                        "Включи поиск текстов в LRCLIB, чтобы подтягивать их автоматически.",
                        "Включить",
                    ) {
                        vm.setOnlineLyrics(true)
                        vm.loadLyrics(force = true)
                    }
                    LyricsResult.Offline -> LyricsMessage(
                        "Нет соединения",
                        "Не получилось достучаться до LRCLIB. Проверь интернет.",
                        "Повторить",
                    ) { vm.loadLyrics(force = true) }
                }
            }

            val source = (res as? LyricsResult.Found)?.let { if (it.synced) "Синхронно · ${it.source}" else "Текст · ${it.source}" }
            Column(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(24.dp), Color(0x59140A1E), Color(0x29FFFFFF))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val dur = p.durationMs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatMs(position), fontSize = 12.sp, color = LC.Dim)
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x33FFFFFF))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(if (dur > 0) (position.toFloat() / dur).coerceIn(0f, 1f) else 0f)
                                .fillMaxHeight()
                                .background(Color.White)
                        )
                    }
                    Text(formatMs(dur), fontSize = 12.sp, color = LC.Dim)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source ?: "", fontSize = 12.sp, color = LC.Dim, modifier = Modifier.weight(1f))
                    IconBtn(LIcons.Prev, "Предыдущий трек", { vm.previous() }, glass = false, iconSize = 22.dp)
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(LC.Text)
                            .clickable(role = Role.Button) { vm.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (p.isPlaying) LIcons.Pause else LIcons.Play, null, Modifier.size(22.dp), tint = Color(0xFF140A1C))
                    }
                    IconBtn(LIcons.Next, "Следующий трек", { vm.next() }, glass = false, iconSize = 22.dp)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LyricsList(lines: List<LyricLine>, synced: Boolean, position: Long, onSeek: (Long) -> Unit) {
    val current = if (synced) lines.indexOfLast { it.timeMs <= position + 250 } else -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 240.dp),
    ) {
        itemsIndexed(lines) { i, l ->
            val isCur = i == current
            val color by animateColorAsState(
                when {
                    !synced -> LC.Text
                    isCur -> Color.White
                    i < current -> Color(0x52F5F4FA)
                    else -> Color(0x99F5F4FA)
                },
                tween(300),
                label = "lc",
            )
            val scale by animateFloatAsState(if (!synced || isCur) 1f else 0.92f, tween(300), label = "ls")
            Text(
                l.text.ifBlank { "♪" },
                fontSize = if (synced) 27.sp else 20.sp,
                lineHeight = if (synced) 34.sp else 28.sp,
                fontWeight = if (synced) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = synced && l.timeMs >= 0) { onSeek(l.timeMs) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun LyricsMessage(title: String, text: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(LIcons.Lyrics, null, Modifier.size(40.dp), tint = LC.Dim)
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 15.sp, color = LC.Dim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, null, onAction)
        }
    }
}

// ======================= ОЧЕРЕДЬ =======================

@Composable
fun QueueSheet(vm: MainViewModel, onClose: () -> Unit) {
    val p by vm.player.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xF5141019))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x40FFFFFF))
            )
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Очередь", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(pluralRu(p.queue.size, "трек", "трека", "треков"), fontSize = 13.sp, color = LC.Dim)
                }
                IconBtn(LIcons.Close, "Закрыть очередь", onClose)
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (p.index - 2).coerceAtLeast(0))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(p.queue, key = { i, e -> "$i:${e.mediaId}" }) { i, e ->
                    val cur = i == p.index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (cur) Color(0x14FFFFFF) else Color.Transparent)
                            .clickable { vm.skipTo(i) }
                            .padding(start = 20.dp, end = 8.dp)
                            .height(62.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(e.mediaId, Modifier.size(44.dp), corner = 11.dp, sizePx = 128)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.title,
                                fontSize = 15.sp,
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Medium,
                                color = if (cur) accent else LC.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(e.artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (cur) {
                            Icon(if (p.isPlaying) LIcons.Pause else LIcons.Play, null, Modifier.padding(12.dp).size(18.dp), tint = accent)
                        } else {
                            if (i > 0) {
                                IconBtn(LIcons.Back, "Выше", { vm.moveItem(i, i - 1) }, glass = false, size = 40.dp, iconSize = 16.dp, tint = LC.Faint, modifier = Modifier.graphicsLayer { rotationZ = 90f })
                            }
                            IconBtn(LIcons.Close, "Убрать из очереди", { vm.removeAt(i) }, glass = false, size = 40.dp, iconSize = 16.dp, tint = LC.Dim)
                        }
                    }
                }
            }
        }
    }
}

// ======================= ТАЙМЕР СНА =======================

@Composable
fun TimerSheet(vm: MainViewModel, onClose: () -> Unit) {
    val sleepAt by vm.sleepEndsAt.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xF5141019))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Text("Таймер сна", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "За 30 секунд до конца музыка плавно затихнет.",
                fontSize = 14.sp,
                color = LC.Dim,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            listOf(15, 30, 45, 60, 90).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { m ->
                        PillButton("$m мин", null, {
                            vm.setSleep(m)
                            onClose()
                        }, Modifier.weight(1f), primary = false)
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (sleepAt != null) {
                PillButton("Выключить таймер", LIcons.Close, {
                    vm.setSleep(null)
                    onClose()
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

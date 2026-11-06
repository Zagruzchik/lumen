package app.lumen.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.lumen.player.data.LyricLine
import app.lumen.player.data.LyricsResult
import app.lumen.player.playback.PlayerState
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
import app.lumen.player.ui.components.BeatSignal
import app.lumen.player.ui.components.RoundRectContour
import app.lumen.player.ui.components.drawBeatContour
import app.lumen.player.ui.components.rememberBeatSignal
import app.lumen.player.ui.components.formatMs
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.components.rememberArtwork
import app.lumen.player.ui.theme.CoverColors
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.roundToInt
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animate
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Размытая обложка на весь экран — фон для плеера. */
@Composable
private fun CoverBackdrop(uri: String?, colors: CoverColors) {
    // Фон плавно перетекает от обложки к обложке.
    androidx.compose.animation.Crossfade(targetState = uri, animationSpec = tween(800), label = "backdrop") { u ->
        CoverBackdropLayer(u, colors)
    }
}

@Composable
private fun CoverBackdropLayer(uri: String?, colors: CoverColors) {
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
                    Brush.verticalGradient(listOf(Color(0x330A0610), Color(0x8C0A0610), Color(0xE00A0610)))
                )
        )
    }
}

private fun sampleRateLabel(sr: Int): String =
    if (sr % 1000 == 0) "${sr / 1000} кГц" else "%.1f кГц".format(sr / 1000f).replace('.', ',')

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

/** Плавное затухание краёв списка сверху и снизу. */
private fun Modifier.fadeEdges(top: Dp, bottom: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val t = (top.toPx() / h).coerceIn(0f, 0.45f)
        val b = (bottom.toPx() / h).coerceIn(0f, 0.45f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                t to Color.Black,
                (1f - b) to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Нажатие «проседает» и пружинит обратно. */
@Composable
private fun Modifier.pressBounce(interaction: MutableInteractionSource): Modifier {
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

private sealed interface Stage {
    val kind: Int

    data class CoverStage(val note: String?) : Stage {
        override val kind = 0
    }

    data class Inline(val lines: List<LyricLine>, val synced: Boolean) : Stage {
        override val kind = 1
    }

    data class Full(val lines: List<LyricLine>, val synced: Boolean, val source: String) : Stage {
        override val kind = 2
    }
}

private fun lyricsNote(r: LyricsResult): String? = when (r) {
    LyricsResult.NotFound -> "Текст не найден"
    LyricsResult.Instrumental -> "Инструментал"
    LyricsResult.Disabled -> "Онлайн-поиск текстов выключен"
    LyricsResult.Offline -> null
    LyricsResult.Loading -> "Ищу текст…"
    else -> null
}

// ======================= ПЛЕЕР =======================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    vm: MainViewModel,
    colors: CoverColors,
    actions: TrackActions,
    lyricsFull: Boolean,
    onLyricsFull: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    onTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p by vm.player.collectAsStateWithLifecycle()
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val sleepAt by vm.sleepEndsAt.collectAsStateWithLifecycle()
    val showLyrics by vm.showLyrics.collectAsStateWithLifecycle()
    val lyricsRes by vm.lyrics.collectAsStateWithLifecycle()
    val accent = LocalAccent.current

    val beat = rememberBeatSignal(vm)

    val found = lyricsRes as? LyricsResult.Found
    LaunchedEffect(found == null) { if (found == null && lyricsFull) onLyricsFull(false) }
    val stage: Stage = when {
        found != null && lyricsFull -> Stage.Full(found.lines, found.synced, found.source)
        found != null && showLyrics -> Stage.Inline(found.lines, found.synced)
        else -> Stage.CoverStage(if (showLyrics) lyricsNote(lyricsRes) else null)
    }
    val isFull = stage is Stage.Full
    // Пока текст открыт на весь экран — экран не гаснет.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isFull) {
        view.keepScreenOn = isFull
        onDispose { view.keepScreenOn = false }
    }

    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(p.currentId, p.isPlaying) {
        while (true) {
            position = vm.position()
            delay(if (p.isPlaying) 120 else 700)
        }
    }
    val seek: (Float) -> Unit = { f ->
        vm.seek(f)
        position = (p.durationMs * f).toLong()
    }

    Box(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onDrag(it) },
                onDragStopped = { onDragEnd(it) },
            )
    ) {
        CoverBackdrop(p.currentId, colors)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            // --- верх ---
            AnimatedContent(
                targetState = isFull,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                label = "top",
            ) { full ->
                if (full) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(LIcons.Down, "Свернуть текст", { onLyricsFull(false) })
                        Spacer(Modifier.width(12.dp))
                        Text("Текст песни", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconBtn(LIcons.Close, "Скрыть текст", {
                            onLyricsFull(false)
                            vm.toggleShowLyrics()
                        })
                    }
                } else {
                    PlayerTopBar(p, track != null, favs, onClose, actions, vm)
                }
            }

            // --- середина: обложка / текст / текст на весь экран ---
            AnimatedContent(
                targetState = stage,
                contentKey = { it.kind },
                transitionSpec = {
                    (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.93f)) togetherWith
                        (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.96f)) using
                        SizeTransform(clip = false)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 14.dp),
                label = "stage",
            ) { s ->
                when (s) {
                    is Stage.CoverStage -> CoverArea(vm, p, accent, s.note, beat)
                    is Stage.Inline -> LyricsBeatFrame(vm, beat, corner = 30.dp) {
                        InlineLyrics(s.lines, s.synced, position) { onLyricsFull(true) }
                    }
                    is Stage.Full -> LyricsBeatFrame(vm, beat, corner = 34.dp) { LyricsList(
                        lines = s.lines,
                        synced = s.synced,
                        position = position,
                        onSeek = { vm.seekAndPlay(it) },
                        big = true,
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 40.dp, bottom = 160.dp),
                        modifier = Modifier.fillMaxSize().fadeEdges(40.dp, 90.dp),
                    ) }
                }
            }

            // --- низ: полный набор или мини-плеер с кнопками ---
            AnimatedContent(
                targetState = isFull,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }) togetherWith
                        (fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 3 }) using
                        SizeTransform(clip = false)
                },
                label = "bottom",
            ) { full ->
                if (full) {
                    CompactControls(vm, p, position, seek, favs, accent, onCollapse = { onLyricsFull(false) })
                } else {
                    FullControls(
                        vm = vm,
                        p = p,
                        position = position,
                        seek = seek,
                        quality = quality,
                        favs = favs,
                        accent = accent,
                        sleepAt = sleepAt,
                        showLyrics = showLyrics,
                        onArtist = { track?.let { actions.onOpenArtist(it) } },
                        onAlbum = { track?.let { actions.onOpenAlbum(it) } },
                        onQueue = onQueue,
                        onTimer = onTimer,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    p: PlayerState,
    hasTrack: Boolean,
    favs: Set<String>,
    onClose: () -> Unit,
    actions: TrackActions,
    vm: MainViewModel,
) {
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
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
                    .clickable(enabled = hasTrack) { track?.let { actions.onOpenAlbum(it) } }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Box {
            IconBtn(LIcons.MoreH, "Меню трека", { menu = true })
            track?.let { TrackMenu(menu, { menu = false }, it, it.uri in favs, actions) }
        }
    }
}

@Composable
private fun CoverArea(
    vm: MainViewModel,
    p: PlayerState,
    accent: Color,
    note: String?,
    beat: BeatSignal,
) {
    val density = LocalDensity.current
    val cc by vm.coverColors.collectAsStateWithLifecycle()
    val spin by rememberInfiniteTransition(label = "coverSpin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(12000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin",
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Оставляем место по краям под волны контура.
        val side = (min(maxWidth.value, maxHeight.value) - 36f).coerceAtLeast(120f).dp
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
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = glow * 0.8f), Color.Transparent),
                            c,
                            r,
                        ),
                        radius = r,
                        center = c,
                    )
                    // Неоновые волны по контуру обложки — бегут и вздымаются под бит.
                    val pad = 12.dp.toPx()
                    drawBeatContour(
                        contour = RoundRectContour(-pad, -pad, size.width + pad, size.height + pad, 30.dp.toPx() + pad),
                        sig = beat,
                        palette = listOf(cc.a, cc.accent, cc.c, cc.b),
                        spinDeg = spin,
                        strokeWidth = 2.4.dp.toPx(),
                        baseAmp = 1.3.dp.toPx(),
                        beatAmp = 6.dp.toPx(),
                        rippleAmp = 10.dp.toPx(),
                        shockGap = 20.dp.toPx(),
                    )
                }
        ) {
            // Карусель: обложка уезжает за пальцем, новая въезжает с другой стороны с пружиной.
            AnimatedContent(
                targetState = TrackKey(p.currentId, p.index, p.queue.size),
                contentKey = { it.id },
                transitionSpec = {
                    val d = trackDir(initialState, targetState)
                    (
                        slideInHorizontally(spring(dampingRatio = 0.82f, stiffness = 240f)) { w -> d * w } +
                            fadeIn(tween(280)) +
                            scaleIn(spring(dampingRatio = 0.8f, stiffness = 240f), initialScale = 0.84f)
                        ) togetherWith (
                        slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { w -> -d * w } +
                            fadeOut(tween(300)) +
                            scaleOut(tween(340, easing = FastOutSlowInEasing), targetScale = 0.84f)
                        ) using SizeTransform(clip = false)
                },
                modifier = Modifier.fillMaxSize(),
                label = "coverCarousel",
            ) { key ->
                SwipeCover(vm, key.id, swipeThreshold)
            }
            if (note != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(note, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

/**
 * Рамка под бит для режима текста: мягкое свечение снизу и сверху, которое
 * вздрагивает на ударах, и тонкие неоновые волны по контуру области текста.
 * Слабее, чем вокруг обложки, чтобы не мешать читать.
 */
@Composable
private fun LyricsBeatFrame(
    vm: MainViewModel,
    beat: BeatSignal,
    corner: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val cc by vm.coverColors.collectAsStateWithLifecycle()
    val spin by rememberInfiniteTransition(label = "lyrSpin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(16000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin",
    )
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val lv = beat.level().coerceIn(0f, 1f)
                // Свечение, «дышащее» от ударов: снизу — тёплым, сверху — акцентом.
                val r = size.width * 0.75f
                drawCircle(
                    Brush.radialGradient(
                        listOf(cc.accent.copy(alpha = 0.10f + 0.22f * lv), Color.Transparent),
                        Offset(size.width / 2f, size.height + r * 0.15f),
                        r,
                    ),
                    radius = r,
                    center = Offset(size.width / 2f, size.height + r * 0.15f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(cc.a.copy(alpha = 0.06f + 0.14f * lv), Color.Transparent),
                        Offset(size.width / 2f, -r * 0.2f),
                        r * 0.8f,
                    ),
                    radius = r * 0.8f,
                    center = Offset(size.width / 2f, -r * 0.2f),
                )
                val pad = 2.dp.toPx()
                drawBeatContour(
                    contour = RoundRectContour(pad, pad, size.width - pad, size.height - pad, corner.toPx()),
                    sig = beat,
                    palette = listOf(cc.a, cc.accent, cc.c, cc.b).map { it.copy(alpha = 0.75f) },
                    spinDeg = spin,
                    strokeWidth = 1.8.dp.toPx(),
                    baseAmp = 0.8.dp.toPx(),
                    beatAmp = 4.dp.toPx(),
                    rippleAmp = 6.dp.toPx(),
                    shockGap = 18.dp.toPx(),
                )
            }
            .padding(6.dp)
    ) { content() }
}

private data class TrackKey(val id: String?, val index: Int, val size: Int)

/** Направление смены трека по очереди: +1 — вперёд, -1 — назад, 0 — просто сменить. */
private fun trackDir(a: TrackKey, b: TrackKey): Int {
    if (a.id == b.id) return 0
    val n = b.size
    return when {
        b.index == a.index + 1 || (a.index == n - 1 && b.index == 0 && n > 1) -> 1
        b.index == a.index - 1 || (a.index == 0 && b.index == n - 1 && n > 1) -> -1
        b.index > a.index -> 1
        b.index < a.index -> -1
        else -> 1
    }
}

/** Обложка, которую можно смахнуть: тянется за пальцем, на отпускании — переход или мягкий возврат. */
@Composable
private fun SwipeCover(vm: MainViewModel, id: String?, threshold: Float) {
    val scope = rememberCoroutineScope()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    Cover(
        id,
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val x = offset.value
                translationX = x
                rotationZ = x / 45f
                val k = (abs(x) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 1f)
                alpha = 1f - 0.35f * k
                val sc = 1f - 0.06f * k
                scaleX = sc
                scaleY = sc
            }
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(30.dp))
            .pointerInput(id) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        scope.launch { offset.stop() }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val x = offset.value
                        val goNext = x < -threshold || (v < -1200f && x < -24f)
                        val goPrev = x > threshold || (v > 1200f && x > 24f)
                        scope.launch {
                            when {
                                goNext -> vm.next()
                                goPrev -> vm.previous()
                            }
                            if (goNext || goPrev) {
                                // Если трек не сменился (например, начало очереди) — мягко возвращаемся.
                                kotlinx.coroutines.delay(450)
                            }
                            offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 300f), initialVelocity = v)
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 300f)) }
                    },
                ) { change, d ->
                    change.consume()
                    tracker.addPointerInputChange(change)
                    val next = offset.value + d
                    scope.launch { offset.snapTo(next) }
                }
            },
        corner = 30.dp,
        sizePx = 1024,
        seed = id,
    )
}

@Composable
private fun InlineLyrics(lines: List<LyricLine>, synced: Boolean, position: Long, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(Color(0x1AFFFFFF))
            .border(1.dp, Color(0x26FFFFFF), shape)
            .clickable(onClickLabel = "Открыть текст на весь экран", onClick = onOpen)
    ) {
        LyricsList(
            lines = lines,
            synced = synced,
            position = position,
            onSeek = null,
            big = false,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 60.dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize().fadeEdges(56.dp, 70.dp),
        )
        Icon(
            LIcons.Expand,
            null,
            Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(18.dp),
            tint = LC.Dim,
        )
    }
}

@Composable
private fun LyricsList(
    lines: List<LyricLine>,
    synced: Boolean,
    position: Long,
    onSeek: ((Long) -> Unit)?,
    big: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val current = if (synced) lines.indexOfLast { it.timeMs <= position + 250 } else -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current < 0 || listState.isScrollInProgress) return@LaunchedEffect
        // Плавно подводим текущую строку к «точке чтения» (треть высоты), а не прыгаем к ней.
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == current }
        if (item != null) {
            val anchor = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * (if (big) 0.36f else 0.3f)
            val delta = item.offset + item.size / 2f - anchor
            listState.animateScrollBy(delta, tween(750, easing = FastOutSlowInEasing))
        } else {
            listState.animateScrollToItem((current - if (big) 2 else 1).coerceAtLeast(0))
        }
    }
    LazyColumn(state = listState, modifier = modifier, contentPadding = contentPadding) {
        itemsIndexed(lines) { i, l ->
            val isCur = i == current
            val color by animateColorAsState(
                when {
                    !synced -> LC.Text
                    isCur -> Color.White
                    i < current -> Color(0x52F5F4FA)
                    else -> Color(0x99F5F4FA)
                },
                tween(650, easing = FastOutSlowInEasing),
                label = "lc",
            )
            val scale by animateFloatAsState(
                if (!synced || isCur) 1f else 0.94f,
                spring(dampingRatio = 0.9f, stiffness = 120f),
                label = "ls",
            )
            Text(
                l.text.ifBlank { "♪" },
                fontSize = when {
                    !synced -> if (big) 20.sp else 17.sp
                    big -> 28.sp
                    else -> 23.sp
                },
                lineHeight = if (big) 36.sp else 30.sp,
                fontWeight = if (synced) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .then(
                        if (onSeek != null && synced && l.timeMs >= 0) {
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSeek(l.timeMs) }
                        } else Modifier
                    )
                    .padding(vertical = if (big) 10.dp else 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullControls(
    vm: MainViewModel,
    p: PlayerState,
    position: Long,
    seek: (Float) -> Unit,
    quality: AudioQuality?,
    favs: Set<String>,
    accent: Color,
    sleepAt: Long?,
    showLyrics: Boolean,
    onArtist: () -> Unit,
    onAlbum: () -> Unit,
    onQueue: () -> Unit,
    onTimer: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = quality,
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
                label = "quality",
            ) { q -> QualityBadges(q, p.sampleRate) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = TrackKey(p.currentId, p.index, p.queue.size),
                contentKey = { it.id },
                transitionSpec = {
                    val d = trackDir(initialState, targetState)
                    (slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { w -> d * w / 4 } + fadeIn(tween(360))) togetherWith
                        (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { w -> -d * w / 4 } + fadeOut(tween(200)))
                },
                modifier = Modifier.weight(1f),
                label = "titles",
            ) { _ ->
                Column {
                    Text(p.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(
                        p.artist,
                        fontSize = 16.sp,
                        color = LC.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onArtist),
                    )
                }
            }
            FavButton(p, favs, accent, vm, 48.dp)
        }

        Spacer(Modifier.height(10.dp))
        val dur = p.durationMs
        WavySlider(
            fraction = if (dur > 0) position.toFloat() / dur else 0f,
            playing = p.isPlaying,
            onSeek = seek,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(position), fontSize = 12.sp, color = LC.Dim)
            Spacer(Modifier.weight(1f))
            Text("−" + formatMs(max(0L, dur - position)), fontSize = 12.sp, color = LC.Dim)
        }

        Spacer(Modifier.height(8.dp))
        TransportRow(vm, p, accent, playSize = 80.dp, sideSize = 60.dp, withModes = true)

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
            BarItem(LIcons.Lyrics, "Текст", showLyrics) { vm.toggleShowLyrics() }
            BarItem(LIcons.Queue, "Очередь", false, onQueue)
            BarItem(LIcons.Moon, sleepLabel, sleepAt != null, onTimer)
            BarItem(LIcons.Disc, "Альбом", false, onAlbum)
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** Режим «текст на весь экран»: снизу только мини-плеер и кнопки управления. */
@Composable
private fun CompactControls(
    vm: MainViewModel,
    p: PlayerState,
    position: Long,
    seek: (Float) -> Unit,
    favs: Set<String>,
    accent: Color,
    onCollapse: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .glass(RoundedCornerShape(26.dp), Color(0x1AFFFFFF), Color(0x2EFFFFFF))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClickLabel = "Вернуть обложку", onClick = onCollapse),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(p.currentId, Modifier.size(48.dp), corner = 13.dp, sizePx = 128)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FavButton(p, favs, accent, vm, 44.dp)
        }
        Spacer(Modifier.height(4.dp))
        val dur = p.durationMs
        WavySlider(
            fraction = if (dur > 0) position.toFloat() / dur else 0f,
            playing = p.isPlaying,
            onSeek = seek,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(position), fontSize = 11.sp, color = LC.Dim)
            Spacer(Modifier.weight(1f))
            Text("−" + formatMs(max(0L, dur - position)), fontSize = 11.sp, color = LC.Dim)
        }
        TransportRow(vm, p, accent, playSize = 60.dp, sideSize = 52.dp, withModes = true)
    }
}

@Composable
private fun FavButton(p: PlayerState, favs: Set<String>, accent: Color, vm: MainViewModel, size: Dp) {
    val fav = p.currentId != null && p.currentId in favs
    val interaction = remember { MutableInteractionSource() }
    val heartScale by animateFloatAsState(
        if (fav) 1f else 0.9f,
        spring(dampingRatio = 0.35f, stiffness = 500f),
        label = "heart",
    )
    Box(
        Modifier
            .size(size)
            .pressBounce(interaction)
            .clip(CircleShape)
            .background(Color(0x1FFFFFFF))
            .clickable(interaction, null, role = Role.Button, onClickLabel = if (fav) "Убрать из любимого" else "В любимое") {
                p.currentId?.let { vm.toggleFavorite(it) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (fav) LIcons.Heart else LIcons.HeartOutline,
            null,
            Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = heartScale
                    scaleY = heartScale
                },
            tint = if (fav) accent else LC.Text,
        )
    }
}

@Composable
private fun TransportRow(
    vm: MainViewModel,
    p: PlayerState,
    accent: Color,
    playSize: Dp,
    sideSize: Dp,
    withModes: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (withModes) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withModes) {
            IconBtn(
                LIcons.Shuffle,
                if (p.shuffle) "Перемешивание включено" else "Перемешивание выключено",
                { vm.toggleShuffle() },
                glass = false,
                size = 48.dp,
                iconSize = 22.dp,
                tint = if (p.shuffle) accent else LC.Dim,
            )
        }
        BounceIcon(LIcons.Prev, "Предыдущий трек", sideSize, sideSize * 0.53f) { vm.previous() }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(playSize)
                .pressBounce(interaction)
                .clip(CircleShape)
                .background(LC.Text)
                .clickable(interaction, null, role = Role.Button, onClickLabel = if (p.isPlaying) "Пауза" else "Играть") { vm.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = p.isPlaying,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.6f))
                },
                label = "playIcon",
            ) { playing ->
                Icon(
                    if (playing) LIcons.Pause else LIcons.Play,
                    null,
                    Modifier.size(playSize * 0.42f),
                    tint = Color(0xFF140A1C),
                )
            }
        }
        BounceIcon(LIcons.Next, "Следующий трек", sideSize, sideSize * 0.53f) { vm.next() }
        if (withModes) {
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
    }
}

@Composable
private fun BounceIcon(icon: ImageVector, desc: String, size: Dp, iconSize: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .pressBounce(interaction)
            .clip(CircleShape)
            .clickable(interaction, null, role = Role.Button, onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = LC.Text)
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val c by animateColorAsState(if (active) accent else LC.Text, tween(250), label = "barC")
    val bg by animateColorAsState(if (active) accent.copy(alpha = 0.18f) else Color.Transparent, tween(250), label = "barBg")
    Column(
        Modifier
            .pressBounce(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = c)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c)
    }
}

// ======================= ОЧЕРЕДЬ =======================

@Composable
fun QueueSheet(vm: MainViewModel, onClose: () -> Unit, sheetModifier: Modifier = Modifier) {
    val p by vm.player.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val closeAt = with(density) { 130.dp.toPx() }
    val close by rememberUpdatedState(onClose)

    // Смахивание вниз: тянем за шапку или за список, когда он долистан до верха.
    var offset by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableFloatStateOf(1f) }
    fun settle(velocity: Float) {
        scope.launch {
            if (offset > closeAt || velocity > 1800f) {
                animate(offset, sheetHeight, animationSpec = tween(200)) { v, _ -> offset = v }
                close()
            } else {
                animate(offset, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> offset = v }
            }
        }
    }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && offset > 0f) {
                    val used = max(available.y, -offset)
                    offset += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    offset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset > 0f) {
                    settle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    val dragState = rememberDraggableState { d -> offset = max(0f, offset + d) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - (offset / (sheetHeight.coerceAtLeast(1f))).coerceIn(0f, 0.8f) }
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        Column(
            sheetModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                .graphicsLayer { translationY = offset }
                .clip(shape)
                .background(Color(0xF5141019))
                .border(1.dp, Color(0x1FFFFFFF), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
        ) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (p.index - 2).coerceAtLeast(0))
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = dragState,
                        onDragStopped = { v -> settle(v) },
                    )
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
                        Text(
                            pluralRu(p.queue.size, "трек", "трека", "треков") + if (p.shuffle) " · перемешано" else "",
                            fontSize = 13.sp,
                            color = LC.Dim,
                        )
                    }
                    IconBtn(
                        LIcons.Shuffle,
                        if (p.shuffle) "Вернуть исходный порядок" else "Перемешать очередь",
                        { vm.toggleShuffle() },
                        tint = if (p.shuffle) accent else LC.Text,
                    )
                    Spacer(Modifier.width(6.dp))
                    IconBtn(LIcons.ArrowUp, "В начало очереди", {
                        scope.launch {
                            if (listState.firstVisibleItemIndex > 25) listState.scrollToItem(8)
                            listState.animateScrollToItem(0)
                        }
                    })
                    Spacer(Modifier.width(6.dp))
                    IconBtn(LIcons.ArrowDown, "В конец очереди", {
                        scope.launch {
                            val last = (p.queue.size - 1).coerceAtLeast(0)
                            if (last - listState.firstVisibleItemIndex > 25) listState.scrollToItem((last - 8).coerceAtLeast(0))
                            listState.animateScrollToItem(last)
                        }
                    })
                    Spacer(Modifier.width(6.dp))
                    IconBtn(LIcons.Close, "Закрыть очередь", onClose)
                }
            }
            LaunchedEffect(p.shuffle) {
                // После перемешивания текущий трек наверху — показываем начало списка.
                listState.scrollToItem((p.index - 2).coerceAtLeast(0))
            }
            QueueList(vm, p, listState, Modifier.fillMaxSize().nestedScroll(nested))
        }
    }
}

private data class QItem(val key: String, val index: Int, val entry: app.lumen.player.playback.QueueEntry)

private fun keyed(queue: List<app.lumen.player.playback.QueueEntry>): List<QItem> {
    val counts = HashMap<String, Int>()
    return queue.mapIndexed { i, e ->
        val n = (counts[e.mediaId] ?: 0) + 1
        counts[e.mediaId] = n
        QItem("${e.mediaId}#$n", i, e)
    }
}

/**
 * Очередь: тап — перейти к треку, смахнуть вправо — убрать,
 * удержать и потянуть — переставить (соседи плавно расступаются, у краёв список сам прокручивается).
 */
@Composable
private fun QueueList(
    vm: MainViewModel,
    p: PlayerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
) {
    val accent = LocalAccent.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<QItem>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(p.queue) {
        if (draggingKey == null) {
            items.clear()
            items.addAll(keyed(p.queue))
        }
    }
    val currentKey = remember(p.queue, p.index) { keyed(p.queue).getOrNull(p.index)?.key }
    val currentPos = items.indexOfFirst { it.key == currentKey }

    fun checkSwap() {
        val key = draggingKey ?: return
        val info = listState.layoutInfo.visibleItemsInfo
        val me = info.firstOrNull { it.key == key } ?: return
        var idx = items.indexOfFirst { it.key == key }
        while (dragOffset > me.size / 2f && idx < items.lastIndex) {
            items.add(idx + 1, items.removeAt(idx))
            idx++
            dragOffset -= me.size
        }
        while (dragOffset < -me.size / 2f && idx > 0) {
            items.add(idx - 1, items.removeAt(idx))
            idx--
            dragOffset += me.size
        }
    }

    // Автопрокрутка, когда тащим трек к верхнему или нижнему краю.
    LaunchedEffect(draggingKey) {
        while (draggingKey != null) {
            val info = listState.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.key == draggingKey }
            if (me != null) {
                val top = me.offset + dragOffset
                val bottom = top + me.size
                val edge = me.size * 0.8f
                val delta = when {
                    top < info.viewportStartOffset + edge -> -14f
                    bottom > info.viewportEndOffset - edge -> 14f
                    else -> 0f
                }
                if (delta != 0f) {
                    val used = listState.scrollBy(delta)
                    dragOffset += used
                    checkSwap()
                }
            }
            delay(16)
        }
    }

    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(items, key = { _, q -> q.key }) { i, q ->
            val dragging = q.key == draggingKey
            val cur = q.key == currentKey
            val past = currentPos >= 0 && i < currentPos
            var swipe by remember(q.key) { mutableFloatStateOf(0f) }
            var swiping by remember(q.key) { mutableStateOf(false) }
            var rowWidth by remember { mutableFloatStateOf(1f) }
            val shownSwipe by animateFloatAsState(
                swipe,
                if (swiping) snap() else spring(dampingRatio = 0.8f, stiffness = 500f),
                label = "swipe",
            )
            val lift by animateFloatAsState(if (dragging) 1f else 0f, tween(150), label = "lift")

            Box(
                (if (dragging) Modifier else Modifier.animateItem())
                    .fillMaxWidth()
                    .height(62.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .onSizeChanged { rowWidth = it.width.toFloat() }
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                        val sc = 1f + 0.03f * lift
                        scaleX = sc
                        scaleY = sc
                        shadowElevation = 14.dp.toPx() * lift
                        shape = RoundedCornerShape(14.dp * lift)
                        clip = lift > 0.01f
                    }
            ) {
                // Красная подложка: открывается со стороны, куда смахиваешь.
                val removeAt = rowWidth * 0.4f
                Row(
                    Modifier
                        .matchParentSize()
                        .background(Color(0xFFC0392B).copy(alpha = (abs(shownSwipe) / removeAt).coerceIn(0f, 1f)))
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (shownSwipe >= 0f) Arrangement.Start else Arrangement.End,
                ) {
                    if (abs(shownSwipe) > 1f) {
                        Icon(LIcons.Trash, null, Modifier.size(20.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Убрать", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                Row(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = shownSwipe }
                        .background(if (dragging) Color(0xFF241D2E) else Color(0xFF141019))
                        .background(if (cur) Color(0x14FFFFFF) else Color.Transparent)
                        .pointerInput(q.key) {
                            // Свайп включается только при явно горизонтальном движении.
                            // Если палец сначала пошёл вертикально — жест отдаётся списку, и он спокойно листается.
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (draggingKey != null) return@awaitEachGesture
                                var totalX = 0f
                                var totalY = 0f
                                var locked = false
                                var passed = false
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    if (!locked && ch.isConsumed) break
                                    val d = ch.positionChange()
                                    if (!locked) {
                                        totalX += d.x
                                        totalY += d.y
                                        if (abs(totalY) > slop) break
                                        if (abs(totalX) > slop * 2f && abs(totalX) > abs(totalY) * 2.5f) {
                                            locked = true
                                            swiping = true
                                        } else {
                                            continue
                                        }
                                    }
                                    ch.consume()
                                    tracker.addPosition(ch.uptimeMillis, ch.position)
                                    swipe += d.x
                                    val over = abs(swipe) > removeAt
                                    if (over != passed) {
                                        passed = over
                                        if (over) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                if (!locked) return@awaitEachGesture
                                swiping = false
                                val v = tracker.calculateVelocity().x
                                val fling = abs(v) > 2200f && abs(swipe) > rowWidth * 0.2f && (v > 0f) == (swipe > 0f)
                                if (abs(swipe) > removeAt || fling) {
                                    val dir = if (swipe > 0f) 1f else -1f
                                    swipe = dir * rowWidth
                                    scope.launch {
                                        delay(180)
                                        val idx = keyed(vm.player.value.queue).indexOfFirst { it.key == q.key }
                                        if (idx >= 0) vm.removeAt(idx) else swipe = 0f
                                        delay(600)
                                        swipe = 0f
                                    }
                                } else {
                                    swipe = 0f
                                }
                            }
                        }
                        .pointerInput(q.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = q.key
                                    dragFrom = items.indexOfFirst { it.key == q.key }
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    val to = items.indexOfFirst { it.key == q.key }
                                    if (dragFrom >= 0 && to >= 0 && dragFrom != to) vm.moveItem(dragFrom, to)
                                    draggingKey = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    dragOffset = 0f
                                    items.clear()
                                    items.addAll(keyed(vm.player.value.queue))
                                },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                checkSwap()
                            }
                        }
                        .clickable { items.indexOfFirst { it.key == q.key }.takeIf { it >= 0 }?.let { vm.skipTo(it) } }
                        .padding(start = 20.dp, end = 8.dp)
                        .graphicsLayer { alpha = if (past && !dragging) 0.5f else 1f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(q.entry.mediaId, Modifier.size(44.dp), corner = 11.dp, sizePx = 128)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            q.entry.title,
                            fontSize = 15.sp,
                            fontWeight = if (cur) FontWeight.Bold else FontWeight.Medium,
                            color = if (cur) accent else LC.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(q.entry.artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (cur) {
                        Icon(if (p.isPlaying) LIcons.Pause else LIcons.Play, null, Modifier.padding(end = 6.dp).size(18.dp), tint = accent)
                    }
                    Icon(
                        LIcons.DragHandle,
                        "Удерживай, чтобы переставить",
                        Modifier.padding(12.dp).size(20.dp),
                        tint = if (dragging) accent else LC.Faint,
                    )
                }
            }
        }
    }
}

// ======================= ТАЙМЕР СНА =======================

@Composable
fun TimerSheet(vm: MainViewModel, onClose: () -> Unit, sheetModifier: Modifier = Modifier) {
    val sleepAt by vm.sleepEndsAt.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    var minutes by remember {
        mutableIntStateOf(
            sleepAt?.let { ((it - System.currentTimeMillis()) / 60_000L).toInt().coerceIn(1, MAX_SLEEP_MIN) } ?: 30
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        Column(
            sheetModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xF5141019))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Таймер сна", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        sleepAt?.let {
                            "Сработает в " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
                        } ?: "Крути по кругу — каждый оборот это час",
                        fontSize = 13.sp,
                        color = if (sleepAt != null) accent else LC.Dim,
                    )
                }
                IconBtn(LIcons.Close, "Закрыть", onClose)
            }
            Spacer(Modifier.height(14.dp))
            SleepDial(minutes, accent) { minutes = it }
            Spacer(Modifier.height(16.dp))
            LazyRowChips(
                listOf(
                    "15 мин" to 15, "30 мин" to 30, "45 мин" to 45, "1 ч" to 60, "1,5 ч" to 90, "2 ч" to 120,
                ),
                selected = minutes,
            ) { minutes = it }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.durationMs > 0 && p.currentId != null) {
                    PillButton("До конца трека", null, {
                        val left = (p.durationMs - vm.position()).coerceAtLeast(5_000L)
                        vm.setSleepMillis(left + 1_000L)
                        onClose()
                    }, Modifier.weight(1f), primary = false)
                }
                PillButton(
                    if (minutes > 0) "Запустить" else "Выбери время",
                    LIcons.Moon,
                    {
                        if (minutes > 0) {
                            vm.setSleep(minutes)
                            onClose()
                        }
                    },
                    Modifier.weight(1f),
                )
            }
            if (sleepAt != null) {
                Spacer(Modifier.height(10.dp))
                PillButton("Выключить таймер", LIcons.Close, {
                    vm.setSleep(null)
                    onClose()
                }, Modifier.fillMaxWidth(), primary = false)
            }
        }
    }
}

private const val MAX_SLEEP_MIN = 12 * 60

@Composable
private fun LazyRowChips(options: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val (label, value) = options[i]
            app.lumen.player.ui.components.LChip(label, selected == value) { onPick(value) }
        }
    }
}

/**
 * Круговой таймер: тяни точку по кругу, один оборот = 60 минут, можно крутить
 * несколько оборотов (до 12 часов). В центре — поля «часы : минуты», их можно ввести вручную.
 */
@Composable
private fun SleepDial(minutes: Int, accent: Color, onMinutes: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(minutes)
    val change by rememberUpdatedState(onMinutes)
    var degrees by remember { mutableFloatStateOf(minutes * 6f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(minutes) {
        if (!dragging && kotlin.math.abs(degrees / 6f - minutes) >= 1f) degrees = minutes * 6f
    }
    val shownDeg by animateFloatAsState(degrees, if (dragging) snap() else spring(dampingRatio = 0.8f, stiffness = 300f), label = "dial")

    Box(Modifier.size(270.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var last = 0f
                    fun angleAt(o: Offset): Float {
                        val dx = o.x - size.width / 2f
                        val dy = o.y - size.height / 2f
                        return (Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f + 360f) % 360f
                    }
                    detectDragGestures(
                        onDragStart = { o ->
                            dragging = true
                            last = angleAt(o)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { ch, _ ->
                        ch.consume()
                        val a = angleAt(ch.position)
                        var d = a - last
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        last = a
                        degrees = (degrees + d).coerceIn(0f, MAX_SLEEP_MIN * 6f)
                        val m = (degrees / 6f).roundToInt()
                        if (m != current) {
                            if (m / 5 != current / 5) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            change(m)
                        }
                    }
                }
        ) {
            val stroke = 22.dp.toPx()
            val r = size.minDimension / 2f - stroke
            val c = center
            val arcSize = Size(r * 2, r * 2)
            val tl = Offset(c.x - r, c.y - r)
            // Деления каждые 5 минут.
            for (i in 0 until 12) {
                val ang = Math.toRadians(i * 30.0 - 90.0)
                val outer = r + stroke * 0.9f
                val inner = outer - (if (i % 3 == 0) 10.dp.toPx() else 5.dp.toPx())
                drawLine(
                    Color(0x40FFFFFF),
                    Offset(c.x + inner * kotlin.math.cos(ang).toFloat(), c.y + inner * kotlin.math.sin(ang).toFloat()),
                    Offset(c.x + outer * kotlin.math.cos(ang).toFloat(), c.y + outer * kotlin.math.sin(ang).toFloat()),
                    2.dp.toPx(),
                    StrokeCap.Round,
                )
            }
            drawArc(Color(0x1FFFFFFF), 0f, 360f, false, tl, arcSize, style = Stroke(stroke))
            val total = shownDeg
            val laps = (total / 360f).toInt()
            // Полные обороты — кольцо всё ярче с каждым часом.
            if (laps > 0) {
                drawArc(
                    accent.copy(alpha = (0.28f + 0.14f * laps).coerceAtMost(0.75f)),
                    0f, 360f, false, tl, arcSize, style = Stroke(stroke),
                )
            }
            val part = total - laps * 360f
            if (part > 0.5f) {
                drawArc(
                    Brush.sweepGradient(listOf(accent.copy(alpha = 0.6f), accent, accent), c),
                    -90f, part, false, tl, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            val ka = Math.toRadians((total % 360f) - 90.0)
            val kp = Offset(c.x + r * kotlin.math.cos(ka).toFloat(), c.y + r * kotlin.math.sin(ka).toFloat())
            drawCircle(accent.copy(alpha = 0.35f), stroke * 0.95f, kp)
            drawCircle(Color.White, stroke * 0.62f, kp)
        }
        TimeFields(minutes, accent, onMinutes)
    }
}

@Composable
private fun TimeFields(minutes: Int, accent: Color, onMinutes: (Int) -> Unit) {
    var hText by remember { mutableStateOf("%02d".format(minutes / 60)) }
    var mText by remember { mutableStateOf("%02d".format(minutes % 60)) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(minutes) {
        if (!editing) {
            hText = "%02d".format(minutes / 60)
            mText = "%02d".format(minutes % 60)
        }
    }
    fun commit(h: String, m: String) {
        val hh = (h.toIntOrNull() ?: 0).coerceIn(0, 12)
        val mm = (m.toIntOrNull() ?: 0).coerceIn(0, 59)
        onMinutes((hh * 60 + mm).coerceAtMost(MAX_SLEEP_MIN))
    }
    val style = androidx.compose.ui.text.TextStyle(
        color = LC.Text,
        fontSize = 46.sp,
        fontWeight = FontWeight.Black,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.BasicTextField(
                value = hText,
                onValueChange = { v ->
                    val d = v.filter { it.isDigit() }.takeLast(2)
                    hText = d
                    commit(d, mText)
                },
                singleLine = true,
                textStyle = style,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier
                    .width(70.dp)
                    .onFocusChanged {
                        editing = it.isFocused
                        if (!it.isFocused) hText = "%02d".format(hText.toIntOrNull() ?: 0)
                    },
            )
            Text(":", fontSize = 40.sp, fontWeight = FontWeight.Black, color = LC.Dim, modifier = Modifier.padding(horizontal = 2.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = mText,
                onValueChange = { v ->
                    val d = v.filter { it.isDigit() }.takeLast(2)
                    mText = d
                    commit(hText, d)
                },
                singleLine = true,
                textStyle = style,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier
                    .width(70.dp)
                    .onFocusChanged {
                        editing = it.isFocused
                        if (!it.isFocused) mText = "%02d".format((mText.toIntOrNull() ?: 0).coerceIn(0, 59))
                    },
            )
        }
        Row {
            Text("часы", fontSize = 12.sp, color = LC.Dim, modifier = Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.width(18.dp))
            Text("минуты", fontSize = 12.sp, color = LC.Dim, modifier = Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

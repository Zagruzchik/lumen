package app.lumen.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.playback.PlayerState
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.TrackActions
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.glassBlur
import app.lumen.player.ui.components.glassSource
import app.lumen.player.ui.screens.AlbumScreen
import app.lumen.player.ui.screens.ArtistScreen
import app.lumen.player.ui.screens.FavoritesScreen
import app.lumen.player.ui.screens.FolderScreen
import app.lumen.player.ui.screens.HomeScreen
import app.lumen.player.ui.screens.LibraryScreen
import app.lumen.player.ui.screens.PlayerScreen
import app.lumen.player.ui.screens.QueueSheet
import app.lumen.player.ui.screens.SettingsScreen
import app.lumen.player.ui.screens.TimerSheet
import app.lumen.player.ui.theme.CoverColors
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import app.lumen.player.ui.theme.LumenTheme
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface Dest {
    data object Home : Dest
    data object Library : Dest
    data object Favorites : Dest
    data object Settings : Dest
    data class Album(val key: String) : Dest
    data class Artist(val name: String) : Dest
    data class Folder(val path: String, val name: String) : Dest
    data object Stats : Dest
    data object Recs : Dest
}

class Nav(
    val open: (Dest) -> Unit,
    val back: () -> Unit,
    val tab: (Dest, Int?) -> Unit,
    val canGoBack: () -> Boolean,
)

@Composable
fun LumenRoot(vm: MainViewModel) {
    val colors by vm.coverColors.collectAsStateWithLifecycle()
    val moon by vm.moonSide.collectAsStateWithLifecycle()
    val coverAccent by animateColorAsState(colors.accent, tween(700), label = "accent")
    // «Обратная сторона луны» меняет акцент всего приложения резко, без плавного перехода.
    val accent = if (moon) app.lumen.player.ui.screens.MoonAccent else coverAccent
    LumenTheme(accent) {
        PermissionGate(vm) { MainScaffold(vm, colors) }
    }
}

// ---------- разрешения ----------

@Composable
private fun PermissionGate(vm: MainViewModel, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val perms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(audioPerm, Manifest.permission.POST_NOTIFICATIONS)
    } else arrayOf(audioPerm)

    fun granted() = ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED

    var denials by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = granted()
        if (!ok) denials++
        vm.onPermission(ok)
    }
    LaunchedEffect(Unit) {
        if (granted()) vm.onPermission(true) else launcher.launch(perms)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (granted()) vm.onPermission(true)
    }
    val has by vm.hasPermission.collectAsStateWithLifecycle()
    if (has) {
        content()
    } else {
        PermissionScreen(onGrant = {
            if (denials >= 2) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                launcher.launch(perms)
            }
        }, toSettings = denials >= 2)
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, toSettings: Boolean) {
    Box(Modifier.fillMaxSize().background(LC.Bg)) {
        AmbientBackground(app.lumen.player.ui.theme.DefaultCoverColors)
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .glass(RoundedCornerShape(28.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(LocalAccent.current),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LIcons.Note, null, Modifier.size(34.dp), tint = LC.OnAccent)
            }
            Spacer(Modifier.height(20.dp))
            Text("Нужен доступ к музыке", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "Lumen играет музыку прямо с телефона и ничего не отправляет в сеть. " +
                    "Разреши доступ к аудиофайлам, иначе библиотека будет пустой.",
                fontSize = 15.sp,
                color = LC.Dim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PillButton(
                if (toSettings) "Открыть настройки" else "Разрешить доступ",
                null,
                onGrant,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------- основной каркас ----------

private const val BACK_NONE = 0
private const val BACK_SHEET = 1
private const val BACK_LYRICS = 2
private const val BACK_PLAYER = 3
private const val BACK_NAV = 4

private val TAB_ORDER = listOf(Dest.Home, Dest.Library, Dest.Recs, Dest.Favorites, Dest.Settings)

@Composable
private fun MainScaffold(vm: MainViewModel, colors: CoverColors) {
    val stack = remember { mutableStateListOf<Dest>(Dest.Home) }
    var libTab by rememberSaveable { mutableIntStateOf(0) }
    var navDir by remember { mutableIntStateOf(1) }
    var lyricsFull by rememberSaveable { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    // 0 — свёрнут в мини-плеер, 1 — полноэкранный плеер. Значение ведёт палец, потом пружина.
    val expand = remember { Animatable(0f) }
    val playerVisible by remember { derivedStateOf { expand.value > 0.001f } }
    val playerExpanded by remember { derivedStateOf { expand.targetValue > 0.5f } }
    var screenH by remember { mutableFloatStateOf(2000f) }

    val openSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 380f)
    fun openPlayer() {
        scope.launch { expand.animateTo(1f, openSpring) }
    }
    fun closePlayer() {
        lyricsFull = false
        scope.launch { expand.animateTo(0f, spring(dampingRatio = 1f, stiffness = 420f)) }
    }
    fun dragPlayer(dy: Float) {
        scope.launch { expand.snapTo((expand.value - dy / screenH).coerceIn(0f, 1f)) }
    }
    fun settlePlayer(velocity: Float) {
        scope.launch {
            val v = expand.value
            val target = when {
                velocity < -900f -> 1f
                velocity > 900f -> 0f
                v > 0.5f -> 1f
                else -> 0f
            }
            if (target == 0f) lyricsFull = false
            expand.animateTo(target, openSpring, initialVelocity = -velocity / screenH)
        }
    }

    val openReq by vm.openPlayerRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openReq) { if (openReq > 0) openPlayer() }
    val showLyrics by vm.showLyrics.collectAsStateWithLifecycle()
    LaunchedEffect(showLyrics) { vm.setLyricsVisible(showLyrics) }

    val nav = remember {
        Nav(
            open = { d ->
                if (stack.lastOrNull() != d) {
                    navDir = 1
                    stack.add(d)
                }
            },
            back = {
                if (stack.size > 1) {
                    navDir = -1
                    stack.removeAt(stack.lastIndex)
                }
            },
            tab = { d, t ->
                if (t != null) libTab = t
                val from = TAB_ORDER.indexOf(stack.first())
                val to = TAB_ORDER.indexOf(d)
                navDir = if (to >= from) 1 else -1
                stack.clear()
                stack.add(d)
            },
            canGoBack = { stack.size > 1 },
        )
    }

    // Предиктивный жест «назад» (Android 14+): пока палец тянет от края, видно, куда вернёшься.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backTarget by remember { mutableIntStateOf(BACK_NONE) }
    var backFromLeft by remember { mutableStateOf(true) }
    var backKind by remember { mutableIntStateOf(BACK_NONE) }
    val backShown by animateFloatAsState(
        backProgress,
        if (backTarget != BACK_NONE) snap() else spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "back",
    )
    PredictiveBackHandler(enabled = timerOpen || queueOpen || playerExpanded || stack.size > 1 || stack.first() != Dest.Home) { events ->
        val target = when {
            timerOpen || queueOpen -> BACK_SHEET
            lyricsFull -> BACK_LYRICS
            playerExpanded -> BACK_PLAYER
            else -> BACK_NAV
        }
        backTarget = target
        backKind = target
        try {
            events.collect { e ->
                backFromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                backProgress = e.progress
                if (target == BACK_PLAYER) expand.snapTo(1f - 0.35f * e.progress)
            }
            backTarget = BACK_NONE
            backProgress = 0f
            when (target) {
                BACK_SHEET -> if (timerOpen) timerOpen = false else queueOpen = false
                BACK_LYRICS -> lyricsFull = false
                BACK_PLAYER -> closePlayer()
                // С любой вкладки «назад» сначала ведёт на главную, и только оттуда приложение сворачивается.
                else -> if (stack.size > 1) nav.back() else nav.tab(Dest.Home, null)
            }
        } catch (e: CancellationException) {
            backTarget = BACK_NONE
            backProgress = 0f
            if (target == BACK_PLAYER) expand.animateTo(1f, openSpring)
        }
    }

    val actions = remember {
        TrackActions(
            onPlayNext = { vm.playNext(it) },
            onEnqueue = { vm.enqueue(it) },
            onToggleFav = { vm.toggleFavorite(it.uri) },
            onOpenAlbum = {
                closePlayer()
                nav.open(Dest.Album(it.albumKey))
            },
            onOpenArtist = {
                closePlayer()
                nav.open(Dest.Artist(it.albumArtist))
            },
        )
    }

    val haze = remember { HazeState() }
    val p by vm.player.collectAsStateWithLifecycle()
    val hasCurrent = p.currentId != null
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPad = navBottom + 68.dp + (if (hasCurrent) 80.dp else 0.dp) + 16.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { screenH = it.height.toFloat().coerceAtLeast(1f) }
    ) {
        // Основной экран слегка «уходит вглубь», пока выезжает плеер.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val e = expand.value.coerceIn(0f, 1f)
                    val s = 1f - 0.06f * e
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.45f * e
                    shape = RoundedCornerShape((e * 28f).dp)
                    clip = e > 0.001f
                }
                .background(LC.Bg)
        ) {
            Box(Modifier.fillMaxSize().glassSource(haze)) {
                AmbientBackground(colors)
                AnimatedContent(
                    modifier = Modifier.graphicsLayer {
                        val pr = if (backKind == BACK_NAV) backShown else 0f
                        val s = 1f - 0.1f * pr
                        scaleX = s
                        scaleY = s
                        translationX = (if (backFromLeft) 1f else -1f) * pr * size.width * 0.08f
                        shape = RoundedCornerShape((pr * 32f).coerceAtLeast(0f).dp)
                        clip = pr > 0.001f
                    },
                    targetState = stack.last(),
                    transitionSpec = {
                        val dir = navDir
                        (slideInHorizontally(tween(320)) { w -> dir * w / 5 } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(260)) { w -> -dir * w / 5 } + fadeOut(tween(200)))
                    },
                    label = "nav",
                ) { d ->
                    when (d) {
                        Dest.Home -> HomeScreen(vm, nav, actions, bottomPad, onOpenPlayer = { openPlayer() })
                        Dest.Library -> LibraryScreen(vm, nav, actions, libTab, { libTab = it }, bottomPad)
                        Dest.Favorites -> FavoritesScreen(vm, nav, actions, bottomPad)
                        Dest.Settings -> SettingsScreen(vm, bottomPad, onOpenStats = { nav.open(Dest.Stats) })
                        Dest.Stats -> app.lumen.player.ui.screens.StatsScreen(vm, nav, bottomPad)
                        Dest.Recs -> app.lumen.player.ui.screens.RecsScreen(vm, bottomPad)
                        is Dest.Album -> AlbumScreen(vm, d.key, nav, actions, bottomPad)
                        is Dest.Artist -> ArtistScreen(vm, d.name, nav, actions, bottomPad)
                        is Dest.Folder -> FolderScreen(vm, d.path, d.name, nav, actions, bottomPad)
                    }
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                AnimatedVisibility(hasCurrent, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                    MiniPlayer(
                        vm = vm,
                        p = p,
                        haze = haze,
                        onOpen = { openPlayer() },
                        onDrag = { dragPlayer(it) },
                        onDragEnd = { settlePlayer(it) },
                        modifier = Modifier.graphicsLayer {
                            val e = expand.value.coerceIn(0f, 1f)
                            alpha = 1f - (e * 2.5f).coerceAtMost(1f)
                            translationY = -e * 160.dp.toPx()
                        },
                    )
                }
                BottomNav(
                    root = stack.first(),
                    haze = haze,
                    onSelect = { nav.tab(it, null) },
                    modifier = Modifier.graphicsLayer { translationY = expand.value.coerceIn(0f, 1f) * size.height },
                )
            }
        }

        if (playerVisible) {
            PlayerScreen(
                vm = vm,
                colors = colors,
                actions = actions,
                lyricsFull = lyricsFull,
                onLyricsFull = { lyricsFull = it },
                onDrag = { dragPlayer(it) },
                onDragEnd = { settlePlayer(it) },
                onClose = { closePlayer() },
                onQueue = { queueOpen = true },
                onTimer = { timerOpen = true },
                modifier = Modifier.graphicsLayer {
                    val e = expand.value.coerceIn(0f, 1f)
                    translationY = (1f - e) * size.height
                    alpha = (e * 3f).coerceAtMost(1f)
                    shape = RoundedCornerShape(((1f - e) * 32f).dp)
                    clip = true
                },
            )
        }

        AnimatedVisibility(queueOpen, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
            QueueSheet(
                vm,
                onClose = { queueOpen = false },
                sheetModifier = Modifier
                    .animateEnterExit(
                        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it },
                        exit = slideOutVertically(tween(220)) { it },
                    )
                    .graphicsLayer {
                        val pr = if (backKind == BACK_SHEET) backShown else 0f
                        translationY = pr * size.height * 0.35f
                        val sc = 1f - 0.05f * pr
                        scaleX = sc
                        scaleY = sc
                    },
            )
        }
        AnimatedVisibility(timerOpen, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
            TimerSheet(
                vm,
                onClose = { timerOpen = false },
                sheetModifier = Modifier
                    .animateEnterExit(
                        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it },
                        exit = slideOutVertically(tween(220)) { it },
                    )
                    .graphicsLayer {
                        val pr = if (backKind == BACK_SHEET) backShown else 0f
                        translationY = pr * size.height * 0.35f
                        val sc = 1f - 0.05f * pr
                        scaleX = sc
                        scaleY = sc
                    },
            )
        }
    }
}

/** Мягкие цветные пятна из цветов обложки — то, что «просвечивает» сквозь стекло. */
@Composable
fun AmbientBackground(colors: CoverColors, modifier: Modifier = Modifier.fillMaxSize(), strength: Float = 0.55f) {
    val a by animateColorAsState(colors.a, tween(900), label = "a")
    val b by animateColorAsState(colors.b, tween(900), label = "b")
    val c by animateColorAsState(colors.c, tween(900), label = "c")
    Canvas(modifier) {
        fun blob(color: Color, center: Offset, r: Float, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center, r),
                radius = r,
                center = center,
            )
        }
        val w = size.width
        val h = size.height
        blob(a, Offset(w * 0.05f, h * 0.02f), w * 0.95f, strength)
        blob(b, Offset(w * 1.0f, h * 0.38f), w * 0.85f, strength)
        blob(c, Offset(w * 0.12f, h * 1.0f), w * 0.8f, strength * 0.6f)
    }
}

@Composable
private fun MiniPlayer(
    vm: MainViewModel,
    p: PlayerState,
    haze: HazeState,
    onOpen: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(22.dp)
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(p.isPlaying, p.currentId, p.durationMs) {
        while (true) {
            progress = if (p.durationMs > 0) (vm.position().toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
            if (!p.isPlaying) break
            delay(500)
        }
    }
    var dragX by remember { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(dragX, spring(dampingRatio = 0.75f), label = "miniX")
    val id = p.currentId ?: return
    val fav = id in favs

    Box(
        modifier
            .padding(horizontal = 10.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .height(66.dp)
            .clip(shape)
            .glassBlur(haze, Color(0x8C281630))
            .border(1.dp, Color(0x2EFFFFFF), shape)
            .clickable(role = Role.Button, onClickLabel = "Открыть плеер", onClick = onOpen)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onDrag(it) },
                onDragStopped = { onDragEnd(it) },
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX < -120f) vm.next() else if (dragX > 120f) vm.previous()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { change, d ->
                    change.consume()
                    dragX += d
                }
            }
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = shownX * 0.5f
                    alpha = 1f - (kotlin.math.abs(shownX) / 600f).coerceAtMost(0.5f)
                }
                .padding(start = 9.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = Triple(id, p.index, p.queue.size),
                contentKey = { it.first },
                transitionSpec = {
                    val a = initialState
                    val b = targetState
                    val d = when {
                        b.second == a.second + 1 || (a.second == b.third - 1 && b.second == 0) -> 1
                        b.second == a.second - 1 || (a.second == 0 && b.second == b.third - 1) -> -1
                        b.second > a.second -> 1
                        else -> -1
                    }
                    (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 300f)) { w -> d * w / 2 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(260)) { w -> -d * w / 2 } + fadeOut(tween(200)))
                },
                modifier = Modifier.weight(1f),
                label = "miniTrack",
            ) { _ ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(id, Modifier.size(48.dp), corner = 13.dp, sizePx = 128)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            IconBtn(
                if (fav) LIcons.Heart else LIcons.HeartOutline,
                if (fav) "Убрать из любимого" else "В любимое",
                { vm.toggleFavorite(id) },
                glass = false,
                tint = if (fav) accent else LC.Dim,
                iconSize = 22.dp,
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(LC.Text)
                    .clickable(role = Role.Button, onClickLabel = if (p.isPlaying) "Пауза" else "Играть") { vm.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (p.isPlaying) LIcons.Pause else LIcons.Play, null, Modifier.size(22.dp), tint = Color(0xFF0B0B12))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0x1FFFFFFF))
        ) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent))
        }
    }
}

private class NavEntry(val dest: Dest, val icon: ImageVector, val label: String)

private val NAV_ENTRIES = listOf(
    NavEntry(Dest.Home, LIcons.Home, "Главная"),
    NavEntry(Dest.Library, LIcons.Library, "Библиотека"),
    NavEntry(Dest.Recs, LIcons.Sparkle, "Для тебя"),
    NavEntry(Dest.Favorites, LIcons.HeartOutline, "Любимое"),
    NavEntry(Dest.Settings, LIcons.Sliders, "Настройки"),
)

@Composable
private fun BottomNav(root: Dest, haze: HazeState, onSelect: (Dest) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val idx = NAV_ENTRIES.indexOfFirst { it.dest == root }.coerceAtLeast(0)
    Box(
        modifier
            .fillMaxWidth()
            .glassBlur(haze, Color(0x990A0A12))
            .drawBehind {
                drawLine(Color(0x1AFFFFFF), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .padding(bottom = navBottom)
            .height(68.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cell = maxWidth / NAV_ENTRIES.size
            val x by animateDpAsState(
                cell * idx + (cell - 64.dp) / 2,
                spring(dampingRatio = 0.7f, stiffness = 480f),
                label = "indicator",
            )
            // «Пилюля», которая переезжает под выбранную вкладку.
            Box(
                Modifier
                    .offset(x = x, y = 8.dp)
                    .size(width = 64.dp, height = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.18f))
            )
            Row(Modifier.fillMaxSize()) {
                NAV_ENTRIES.forEachIndexed { i, e ->
                    NavItem(e.icon, e.label, i == idx, Modifier.weight(1f)) { onSelect(e.dest) }
                }
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val color by animateColorAsState(if (selected) accent else LC.Dim, tween(250), label = "navC")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.85f
            selected -> 1.1f
            else -> 1f
        },
        spring(dampingRatio = 0.4f, stiffness = 600f),
        label = "navS",
    )
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interaction, null, role = Role.Tab, onClick = onClick)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            null,
            Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            tint = color,
        )
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

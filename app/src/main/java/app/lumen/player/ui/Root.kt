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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
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
    data class Genre(val name: String) : Dest
    data object History : Dest
    data object Recent : Dest
    data object Stats : Dest
    data object Recs : Dest
    data class Playlist(val id: Long) : Dest
    data object Wrapped : Dest
    data class ThemeEditor(val baseId: String) : Dest
    data object Disliked : Dest
    data object Duplicates : Dest
    data class SmartPlaylist(val id: Long) : Dest
    data class SmartEditor(val id: Long) : Dest
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
    val theme = app.lumen.player.ui.theme.ThemeStore.current
    // Акцент: из обложки или постоянный — как задано в теме.
    val fixedAccent by animateColorAsState(theme.accent, tween(500), label = "fixedAccent")
    val sysCtx = androidx.compose.ui.platform.LocalContext.current
    val sysAccent = remember(sysCtx) { app.lumen.player.ui.theme.systemAccent(sysCtx) }
    val accent = when {
        moon -> app.lumen.player.ui.screens.MoonAccent
        theme.accentMode == app.lumen.player.ui.theme.AccentMode.FIXED -> fixedAccent
        theme.accentMode == app.lumen.player.ui.theme.AccentMode.SYSTEM -> sysAccent ?: coverAccent
        else -> coverAccent
    }
    val showFormat by vm.showFormat.collectAsStateWithLifecycle()
    val disliked by vm.dislikedUris.collectAsStateWithLifecycle()
    val karaokeOn by vm.karaoke.collectAsStateWithLifecycle()
    LumenTheme(accent) {
        androidx.compose.runtime.CompositionLocalProvider(
            app.lumen.player.ui.components.LocalShowFormat provides showFormat,
            app.lumen.player.ui.components.LocalDisliked provides disliked,
            app.lumen.player.ui.components.LocalKaraoke provides karaokeOn,
        ) {
            PermissionGate(vm) { MainScaffold(vm, colors) }
        }
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
        vm.onResumeForUpdate()
        vm.onAppResume()
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
    // Когда плеер раскрыт целиком, главная под ним не анимируется.
    val homeActive by remember { derivedStateOf { expand.value < 0.98f } }
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
                // У онлайн-трека нет альбома в библиотеке — ищем ещё треки исполнителя в сети.
                if (app.lumen.player.data.Online.isOnline(it.uri)) {
                    nav.tab(Dest.Home, null)
                    // Альбом найден в каталоге — ищем его целиком, иначе — исполнителя.
                    vm.searchRequest.value = if (it.album != app.lumen.player.data.Online.labelOf(it.uri)) "${it.artist} ${it.album}" else it.artist
                } else nav.open(Dest.Album(it.albumKey))
            },
            onOpenArtist = {
                closePlayer()
                if (app.lumen.player.data.Online.isOnline(it.uri)) {
                    nav.tab(Dest.Home, null)
                    vm.searchRequest.value = it.artist
                } else nav.open(Dest.Artist(app.lumen.player.data.Artists.main(it)))
            },
            onAddToPlaylist = { vm.sheetTrack.value = app.lumen.player.ui.SheetTrack(it, edit = false) },
            onPlaySimilar = { vm.playSimilar(it) },
            onEdit = { vm.sheetTrack.value = app.lumen.player.ui.SheetTrack(it, edit = true) },
            onInfo = { vm.infoTrack.value = it },
            onDislike = { vm.toggleDislike(it) },
            onDownload = { vm.downloadOnline(it) },
            onDelete = { vm.deleteAsk.value = it },
            onMenu = { t, fav, a -> vm.trackMenu.value = app.lumen.player.ui.components.TrackMenuRequest(t, fav, a) },
        )
    }

    val haze = remember { HazeState() }
    val p by vm.player.collectAsStateWithLifecycle()
    val hasCurrent = p.currentId != null
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // На главной мини-плеер крупнее и с кнопками «назад/вперёд»; в остальных разделах — обычный.
    val homeTop by vm.homeAtTop.collectAsStateWithLifecycle()
    // Большой — только на первом экране главной; при прокрутке вниз плавно сжимается.
    val onHome = stack.size == 1 && stack.first() == Dest.Home && homeTop
    val miniBig by animateFloatAsState(
        if (onHome) 1f else 0f,
        tween(480, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "miniBig",
    )
    val bottomPad = navBottom + 68.dp + (if (hasCurrent) 80.dp + (MINI_EXTRA * miniBig).dp else 0.dp) + 16.dp

    // Ночные часы: зарядка + минута без касаний.
    val nightOn by vm.nightClock.collectAsStateWithLifecycle()
    val charging = app.lumen.player.ui.screens.rememberCharging()
    // Время последнего касания хранится не в состоянии Compose — касания не вызывают перерисовку.
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    var nightShown by remember { mutableStateOf(false) }
    LaunchedEffect(nightOn, charging) {
        nightShown = false
        lastTouch[0] = System.currentTimeMillis()
        while (nightOn && charging) {
            kotlinx.coroutines.delay(3_000L)
            if (!nightShown && System.currentTimeMillis() - lastTouch[0] > 60_000L) nightShown = true
        }
    }
    if (nightOn && charging) app.lumen.player.ui.screens.KeepScreenOn()
    val carOn by vm.carMode.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { screenH = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                // Только наблюдаем за касаниями (ничего не перехватываем) — для ночных часов.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        lastTouch[0] = System.currentTimeMillis()
                    }
                }
            }
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
                        Dest.Home -> androidx.compose.runtime.CompositionLocalProvider(
                            app.lumen.player.ui.components.LocalHomeActive provides homeActive,
                        ) { HomeScreen(vm, nav, actions, bottomPad, onOpenPlayer = { openPlayer() }) }
                        Dest.Library -> LibraryScreen(vm, nav, actions, libTab, { libTab = it }, bottomPad)
                        Dest.Favorites -> FavoritesScreen(vm, nav, actions, bottomPad)
                        Dest.Settings -> SettingsScreen(vm, bottomPad, onOpenStats = { nav.open(Dest.Stats) }, onEditTheme = { nav.open(Dest.ThemeEditor(it)) }, onOpen = { nav.open(it) })
                        Dest.Stats -> app.lumen.player.ui.screens.StatsScreen(vm, nav, bottomPad)
                        Dest.Recs -> app.lumen.player.ui.screens.RecsScreen(vm, bottomPad)
                        is Dest.Album -> AlbumScreen(vm, d.key, nav, actions, bottomPad)
                        is Dest.Artist -> ArtistScreen(vm, d.name, nav, actions, bottomPad)
                        is Dest.Folder -> FolderScreen(vm, d.path, d.name, nav, actions, bottomPad)
                        is Dest.Genre -> app.lumen.player.ui.screens.GenreScreen(vm, d.name, nav, actions, bottomPad)
                        Dest.History -> app.lumen.player.ui.screens.ListScreen(vm, nav, actions, bottomPad, "История", "Недавно слушал", "history", remember { vm.historyTracks() })
                        Dest.Recent -> app.lumen.player.ui.screens.ListScreen(vm, nav, actions, bottomPad, "Недавно добавленные", "Новое в библиотеке", "recent", remember { vm.recentlyAdded() })
                        is Dest.Playlist -> app.lumen.player.ui.screens.PlaylistScreen(vm, d.id, nav, actions, bottomPad)
                        Dest.Wrapped -> app.lumen.player.ui.screens.WrappedScreen(vm, nav)
                        is Dest.ThemeEditor -> app.lumen.player.ui.screens.ThemeEditorScreen(d.baseId, nav)
                        Dest.Disliked -> app.lumen.player.ui.screens.DislikedScreen(vm, nav, actions, bottomPad)
                        Dest.Duplicates -> app.lumen.player.ui.screens.DuplicatesScreen(vm, nav, bottomPad)
                        is Dest.SmartPlaylist -> app.lumen.player.ui.screens.SmartPlaylistScreen(vm, d.id, nav, actions, bottomPad)
                        is Dest.SmartEditor -> app.lumen.player.ui.screens.SmartEditorScreen(vm, d.id, nav)
                    }
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                AnimatedVisibility(hasCurrent, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                    MiniPlayer(
                        vm = vm,
                        p = p,
                        haze = haze,
                        big = miniBig,
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
        // Нижние шторки: меню трека и информация о треке.
        val menuReq by vm.trackMenu.collectAsStateWithLifecycle()
        var lastMenu by remember { mutableStateOf<app.lumen.player.ui.components.TrackMenuRequest?>(null) }
        if (menuReq != null) lastMenu = menuReq
        AnimatedVisibility(menuReq != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            lastMenu?.let { r ->
                app.lumen.player.ui.components.TrackMenuSheet(
                    r,
                    onClose = { vm.trackMenu.value = null },
                    onSound = { vm.soundSheet.value = true },
                    onCar = { vm.carMode.value = true },
                    onLyrics = { vm.openLyricsEditor(it) },
                    onTrim = { vm.trimTrack.value = it },
                    sheetModifier = Modifier.animateEnterExit(
                        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
                        exit = slideOutVertically(tween(220)) { it },
                    ),
                )
            }
        }
        // Удаление файла: своё подтверждение, на Android 11+ потом ещё системное.
        val delT by vm.deleteAsk.collectAsStateWithLifecycle()
        var pendingDelete by remember { mutableStateOf<app.lumen.player.data.TrackEntity?>(null) }
        val delLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
        ) { res ->
            val t = pendingDelete
            pendingDelete = null
            if (res.resultCode == android.app.Activity.RESULT_OK && t != null) vm.onFileDeleted(t)
        }
        if (delT != null) {
            val t = delT!!
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.deleteAsk.value = null },
                title = { Text("Удалить файл?") },
                text = { Text("«${t.title}» исчезнет с телефона насовсем.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        vm.deleteAsk.value = null
                        val sender = vm.deleteRequest(listOf(t.uri))
                        if (sender != null) {
                            pendingDelete = t
                            delLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                        } else vm.deleteNow(t)
                    }) { Text("Удалить", color = Color(0xFFFF8A8A)) }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.deleteAsk.value = null }) { Text("Отмена") } },
                containerColor = app.lumen.player.ui.theme.LT.sheetTop,
            )
        }

        // Редактор текста песни — поверх всего.
        val lyrEdit by vm.lyricsEditor.collectAsStateWithLifecycle()
        var lastLyrEdit by remember { mutableStateOf<app.lumen.player.data.TrackEntity?>(null) }
        if (lyrEdit != null) lastLyrEdit = lyrEdit
        AnimatedVisibility(
            lyrEdit != null,
            enter = fadeIn(tween(250)) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 6 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { it / 6 },
        ) {
            lastLyrEdit?.let { t ->
                androidx.compose.runtime.key(t.uri) {
                    app.lumen.player.ui.screens.LyricsEditorScreen(vm, t) { vm.lyricsEditor.value = null }
                }
            }
        }
        // Автомобильный режим и ночные часы — поверх всего.
        AnimatedVisibility(carOn, enter = fadeIn(tween(300)), exit = fadeOut(tween(250))) {
            app.lumen.player.ui.screens.CarModeScreen(vm) { vm.carMode.value = false }
        }
        AnimatedVisibility(nightShown && !carOn, enter = fadeIn(tween(1500)), exit = fadeOut(tween(400))) {
            app.lumen.player.ui.screens.NightClock(vm) {
                nightShown = false
                lastTouch[0] = System.currentTimeMillis()
            }
        }
        // Карточка-цитата из текста песни.
        val qReq by vm.quoteReq.collectAsStateWithLifecycle()
        var lastQ by remember { mutableStateOf<app.lumen.player.ui.components.QuoteRequest?>(null) }
        if (qReq != null) lastQ = qReq
        AnimatedVisibility(qReq != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            lastQ?.let { r ->
                app.lumen.player.ui.components.QuoteSheet(
                    r,
                    onClose = { vm.quoteReq.value = null },
                    onToast = { vm.toast(it) },
                    sheetModifier = Modifier.animateEnterExit(
                        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
                        exit = slideOutVertically(tween(220)) { it },
                    ),
                )
            }
        }
        // Скорость, тон, A–B и обрезка.
        val soundOpen by vm.soundSheet.collectAsStateWithLifecycle()
        AnimatedVisibility(soundOpen, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            val sp by vm.speed.collectAsStateWithLifecycle()
            val pi by vm.pitch.collectAsStateWithLifecycle()
            val abSt by vm.ab.collectAsStateWithLifecycle()
            app.lumen.player.ui.components.SoundSheet(
                speed = sp,
                pitch = pi,
                ab = abSt.a to abSt.b,
                position = { vm.position() },
                onPlayback = { s1, p1 -> vm.setPlayback(s1, p1) },
                onAb = { vm.setAbPoint(it) },
                onClearAb = { vm.clearAb() },
                onClose = { vm.soundSheet.value = false },
                sheetModifier = Modifier.animateEnterExit(
                    enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
                    exit = slideOutVertically(tween(220)) { it },
                ),
            )
        }
        val trimT by vm.trimTrack.collectAsStateWithLifecycle()
        var lastTrim by remember { mutableStateOf<app.lumen.player.data.TrackEntity?>(null) }
        if (trimT != null) lastTrim = trimT
        AnimatedVisibility(trimT != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            lastTrim?.let { t ->
                val trimMap by vm.trims.collectAsStateWithLifecycle()
                val cur by vm.player.collectAsStateWithLifecycle()
                app.lumen.player.ui.components.TrimSheet(
                    t,
                    initial = trimMap[t.uri]?.let { it.startMs to it.endMs },
                    isCurrent = cur.currentId == t.uri,
                    position = { vm.position() },
                    onSave = { a, b -> vm.saveTrim(t, a, b) },
                    onClose = { vm.trimTrack.value = null },
                    sheetModifier = Modifier.animateEnterExit(
                        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
                        exit = slideOutVertically(tween(220)) { it },
                    ),
                )
            }
        }
        val infoT by vm.infoTrack.collectAsStateWithLifecycle()
        var lastInfo by remember { mutableStateOf<app.lumen.player.data.TrackEntity?>(null) }
        if (infoT != null) lastInfo = infoT
        AnimatedVisibility(infoT != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            lastInfo?.let { t ->
                app.lumen.player.ui.components.TrackInfoSheet(
                    t,
                    load = { vm.trackInfo(it) },
                    onClose = { vm.infoTrack.value = null },
                    sheetModifier = Modifier.animateEnterExit(
                        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
                        exit = slideOutVertically(tween(220)) { it },
                    ),
                )
            }
        }
        val sheet by vm.sheetTrack.collectAsStateWithLifecycle()
        var lastSheet by remember { mutableStateOf<app.lumen.player.ui.SheetTrack?>(null) }
        if (sheet != null) lastSheet = sheet
        AnimatedVisibility(sheet != null, enter = fadeIn(tween(240)), exit = fadeOut(tween(200))) {
            val st = lastSheet
            if (st != null) {
                val mod = Modifier.animateEnterExit(
                    enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 360f)) { it },
                    exit = slideOutVertically(tween(220)) { it },
                )
                if (st.edit) {
                    app.lumen.player.ui.screens.EditTagsSheet(vm, st.track, onClose = { vm.sheetTrack.value = null }, sheetModifier = mod)
                } else {
                    app.lumen.player.ui.screens.AddToPlaylistSheet(vm, st.track, onClose = { vm.sheetTrack.value = null }, sheetModifier = mod)
                }
            }
        }
        val newPl by vm.newPlaylistPrompt.collectAsStateWithLifecycle()
        AnimatedVisibility(newPl, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            app.lumen.player.ui.screens.NameDialog("Новый плейлист", "", "Создать", { name ->
                val pending = vm.pendingPlaylistUri
                vm.pendingPlaylistUri = null
                if (pending != null) {
                    vm.createPlaylist(name, listOf(pending)) { }
                    vm.quickToast.value = "Плейлист «${name.trim()}» создан"
                } else {
                    vm.createPlaylist(name) { id -> nav.open(Dest.Playlist(id)) }
                }
                vm.newPlaylistPrompt.value = false
            }, { vm.newPlaylistPrompt.value = false })
        }
        // Меню плейлистов на удержании лайка и короткое подтверждение «Добавлено».
        val qLists by vm.playlists.collectAsStateWithLifecycle()
        app.lumen.player.ui.components.QuickPlaylistMenu(
            vm.quickMenu,
            qLists.take(6).map { it.name to it.trackCount },
            onPick = { vm.quickPick(it) },
        )
        val qToast by vm.quickToast.collectAsStateWithLifecycle()
        var lastToast by remember { mutableStateOf("") }
        qToast?.let { lastToast = it }
        AnimatedVisibility(
            qToast != null,
            enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.8f)) { -it },
            exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { -it },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(app.lumen.player.ui.theme.LT.sheetBottom)
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Check, null, Modifier.size(18.dp), tint = LocalAccent.current)
                Spacer(Modifier.width(10.dp))
                Text(lastToast, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val whatsNew by vm.whatsNew.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { vm.checkWhatsNew() }
        AnimatedVisibility(whatsNew != null, enter = fadeIn(tween(260)), exit = fadeOut(tween(220))) {
            var kept by remember { mutableStateOf("") }
            whatsNew?.let { kept = it }
            app.lumen.player.ui.screens.WhatsNewSheet(
                kept,
                vm.versionName,
                onClose = { vm.hideWhatsNew() },
                sheetModifier = Modifier.animateEnterExit(
                    enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 360f)) { it },
                    exit = slideOutVertically(tween(240)) { it },
                ),
            )
        }
        val updSheet by vm.updateSheet.collectAsStateWithLifecycle()
        AnimatedVisibility(updSheet, enter = fadeIn(tween(260)), exit = fadeOut(tween(220))) {
            app.lumen.player.ui.screens.UpdateSheet(
                vm,
                onClose = { vm.hideUpdateSheet() },
                sheetModifier = Modifier.animateEnterExit(
                    enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 360f)) { it },
                    exit = slideOutVertically(tween(240)) { it },
                ),
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

/**
 * Фон приложения по теме: мягкие пятна из цветов обложки (cover), живой градиент из цветов
 * темы (gradient) или сплошной цвет (solid — ничего не рисуется, самый экономный).
 */
@Composable
fun AmbientBackground(colors: CoverColors, modifier: Modifier = Modifier.fillMaxSize(), strength: Float = -1f) {
    val t = app.lumen.player.ui.theme.ThemeStore.current
    if (t.backdrop == app.lumen.player.ui.theme.BackdropMode.SOLID) return
    val k = if (strength < 0f) t.backdropStrength else strength * (t.backdropStrength / 0.55f).coerceIn(0f, 1.8f)
    val src = if (t.backdrop == app.lumen.player.ui.theme.BackdropMode.GRADIENT) {
        val g = t.gradient
        CoverColors(g.first(), g.getOrElse(0) { g.first() }, g.getOrElse(1) { g.first() }, g.getOrElse(2) { g.last() })
    } else colors
    val a by animateColorAsState(src.a, tween(900), label = "a")
    val b by animateColorAsState(src.b, tween(900), label = "b")
    val c by animateColorAsState(src.c, tween(900), label = "c")
    val strength = k.coerceIn(0f, 1f)
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

private const val MINI_EXTRA = 44f

/** Кнопка «назад/вперёд» мини-плеера: плавно раскрывается по ширине на главной. */
@Composable
private fun MiniSkip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, big: Float, onClick: () -> Unit) {
    if (big <= 0.01f) return
    Box(
        Modifier
            .width((40f * big).dp)
            .height(40.dp)
            .graphicsLayer { alpha = big; scaleX = 0.6f + 0.4f * big; scaleY = 0.6f + 0.4f * big }
            .clip(CircleShape)
            .clickable(enabled = big > 0.5f, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(24.dp), tint = LC.Text)
    }
}

@Composable
private fun MiniInfo(id: String, title: String, artist: String, big: Float, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = (9f + 3f * big).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(id, Modifier.size((48f + 24f * big).dp), corner = (13f + 4f * big).dp, sizePx = 192)
        Spacer(Modifier.width((12f + 2f * big).dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = (15f + 1f * big).sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                // На главной плеер выше — название целиком, в две строки.
                maxLines = if (big > 0.5f) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MiniPlayer(
    vm: MainViewModel,
    p: PlayerState,
    haze: HazeState,
    big: Float,
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
    val id = p.currentId ?: return
    val fav = id in favs

    // ---- карусель треков: текущий едет за пальцем, соседний выезжает следом ----
    val scope = rememberCoroutineScope()
    val off = remember { androidx.compose.animation.core.Animatable(0f) }
    var areaW by remember { mutableFloatStateOf(1f) }
    var committed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(id to p.index) }
    val qs = p.queue.size
    val loop = p.repeat == androidx.media3.common.Player.REPEAT_MODE_ALL
    fun neighbor(d: Int): app.lumen.player.playback.QueueEntry? {
        if (qs == 0) return null
        var i = p.index + d
        if (i < 0 || i >= qs) {
            if (!loop || qs < 2) return null
            i = (i + qs) % qs
        }
        return p.queue.getOrNull(i)
    }
    val prevE = neighbor(-1)
    val nextE = neighbor(1)

    fun dirFrom(oldIdx: Int): Int = when {
        p.index == oldIdx + 1 || (oldIdx == qs - 1 && p.index == 0) -> 1
        p.index == oldIdx - 1 || (oldIdx == 0 && p.index == qs - 1) -> -1
        p.index > oldIdx -> 1
        else -> -1
    }

    // Смещение, которое реально рисуем. В первый кадр после смены трека Animatable ещё
    // относится к старому треку — поэтому сразу подставляем правильное значение,
    // иначе на мгновение мелькает трек «через один».
    val idNow = id
    fun shownOff(): Float {
        val (sid, sidx) = shown
        if (sid == idNow) return off.value
        return if (committed) 0f else dirFrom(sidx) * areaW
    }

    LaunchedEffect(id) {
        val (oldId, oldIdx) = shown
        if (oldId == id) return@LaunchedEffect
        if (committed) {
            off.snapTo(0f)
            shown = id to p.index
            committed = false
        } else {
            // Трек сменился кнопкой/из уведомления — показываем ту же карусель.
            off.snapTo(dirFrom(oldIdx) * areaW)
            shown = id to p.index
            off.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 260f))
        }
    }

    Box(
        modifier
            .padding(horizontal = 10.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .height((66f + MINI_EXTRA * big).dp)
            .clip(shape)
            .glassBlur(haze, Color(0x8C281630))
            .border(1.dp, Color(0x2EFFFFFF), shape)
            .clickable(role = Role.Button, onClickLabel = "Открыть плеер", onClick = onOpen)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onDrag(it) },
                onDragStopped = { onDragEnd(it) },
            )
            .pointerInput(prevE?.mediaId, nextE?.mediaId) {
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        scope.launch { off.stop() }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val x = off.value
                        val w = areaW
                        scope.launch {
                            val toNext = nextE != null && (x < -w * 0.32f || (v < -900f && x < 0f))
                            val toPrev = prevE != null && (x > w * 0.32f || (v > 900f && x > 0f))
                            when {
                                toNext -> {
                                    off.animateTo(-w, spring(dampingRatio = 1f, stiffness = 380f), initialVelocity = v.coerceIn(-6000f, 0f))
                                    committed = true
                                    vm.nextItem()
                                    delay(1500)
                                    if (committed) {
                                        committed = false
                                        off.animateTo(0f, spring(dampingRatio = 0.86f))
                                    }
                                }
                                toPrev -> {
                                    off.animateTo(w, spring(dampingRatio = 1f, stiffness = 380f), initialVelocity = v.coerceIn(0f, 6000f))
                                    committed = true
                                    vm.previousItem()
                                    delay(1500)
                                    if (committed) {
                                        committed = false
                                        off.animateTo(0f, spring(dampingRatio = 0.86f))
                                    }
                                }
                                else -> off.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 320f), initialVelocity = v)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { off.animateTo(0f, spring(dampingRatio = 0.78f)) } },
                ) { change, d ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val cur = off.value
                    // Если соседа нет — тянется туго, как резинка.
                    val resist = if ((cur + d < 0f && nextE == null) || (cur + d > 0f && prevE == null)) 0.25f else 1f
                    scope.launch { off.snapTo((cur + d * resist).coerceIn(-areaW, areaW)) }
                }
            }
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(end = (8f + 2f * big).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { areaW = it.width.toFloat().coerceAtLeast(1f) }
                    // Слева область упирается в скруглённый край карточки, справа — постоянная
                    // мягкая растушёвка перед кнопками. Она не включается и не выключается, поэтому не «мигает».
                    .graphicsLayer {
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        clip = true
                    }
                    .drawWithContent {
                        drawContent()
                        val fr = 22.dp.toPx() / size.width
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Black,
                                1f - fr to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                val curEntry = p.queue.getOrNull(p.index)?.takeIf { it.mediaId == id }
                    ?.let { if (it.title.isBlank()) it.copy(title = p.title, artist = p.artist) else it }
                    ?: app.lumen.player.playback.QueueEntry(id, p.title, p.artist)
                // Три карточки — предыдущая, текущая, следующая — каждая со своим ключом:
                // после переключения «следующая» просто становится текущей, ничего не перерисовывается.
                // Положение читается только при отрисовке — во время свайпа нет перекомпоновки.
                val dup = prevE != null && prevE.mediaId == nextE?.mediaId
                val slots = buildList {
                    if (prevE != null && prevE.mediaId != id) add(-1 to prevE)
                    add(0 to curEntry)
                    if (nextE != null && nextE.mediaId != id) add(1 to nextE)
                }
                slots.forEach { (slot, e) ->
                    androidx.compose.runtime.key(if (dup && slot != 0) "${e.mediaId}#$slot" else e.mediaId) {
                        MiniInfo(e.mediaId, e.title, e.artist, big, Modifier.graphicsLayer {
                            val x = shownOff() + slot * areaW
                            val d = (kotlin.math.abs(x) / areaW).coerceIn(0f, 1f)
                            translationX = x
                            val sc = 1f - 0.07f * d
                            scaleX = sc
                            scaleY = sc
                            alpha = 1f - 0.75f * d
                        })
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
                    .size((48f + 6f * big).dp)
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
            .glassBlur(haze, app.lumen.player.ui.components.DEFAULT_PANEL)
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

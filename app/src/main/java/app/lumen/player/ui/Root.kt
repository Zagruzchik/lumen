package app.lumen.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import app.lumen.player.ui.screens.LyricsScreen
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

sealed interface Dest {
    data object Home : Dest
    data object Library : Dest
    data object Favorites : Dest
    data object Settings : Dest
    data class Album(val key: String) : Dest
    data class Artist(val name: String) : Dest
    data class Folder(val path: String, val name: String) : Dest
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
    val accent by animateColorAsState(colors.accent, tween(700), label = "accent")
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

@Composable
private fun MainScaffold(vm: MainViewModel, colors: CoverColors) {
    val stack = remember { mutableStateListOf<Dest>(Dest.Home) }
    var libTab by rememberSaveable { mutableIntStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }

    val openReq by vm.openPlayerRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openReq) { if (openReq > 0) playerOpen = true }
    LaunchedEffect(lyricsOpen) { vm.setLyricsVisible(lyricsOpen) }

    val nav = remember {
        Nav(
            open = { d -> if (stack.lastOrNull() != d) stack.add(d) },
            back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
            tab = { d, t ->
                if (t != null) libTab = t
                stack.clear()
                stack.add(d)
            },
            canGoBack = { stack.size > 1 },
        )
    }

    BackHandler(enabled = timerOpen || queueOpen || lyricsOpen || playerOpen || stack.size > 1) {
        when {
            timerOpen -> timerOpen = false
            queueOpen -> queueOpen = false
            lyricsOpen -> lyricsOpen = false
            playerOpen -> playerOpen = false
            else -> nav.back()
        }
    }

    val actions = remember {
        TrackActions(
            onPlayNext = { vm.playNext(it) },
            onEnqueue = { vm.enqueue(it) },
            onToggleFav = { vm.toggleFavorite(it.uri) },
            onOpenAlbum = {
                playerOpen = false
                nav.open(Dest.Album(it.albumKey))
            },
            onOpenArtist = {
                playerOpen = false
                nav.open(Dest.Artist(it.albumArtist))
            },
        )
    }

    val haze = remember { HazeState() }
    val p by vm.player.collectAsStateWithLifecycle()
    val hasCurrent = p.currentId != null
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPad = navBottom + 68.dp + (if (hasCurrent) 80.dp else 0.dp) + 16.dp

    Box(Modifier.fillMaxSize().background(LC.Bg)) {
        Box(Modifier.fillMaxSize().glassSource(haze)) {
            AmbientBackground(colors)
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "nav",
            ) { d ->
                when (d) {
                    Dest.Home -> HomeScreen(vm, nav, actions, bottomPad, onOpenPlayer = { playerOpen = true })
                    Dest.Library -> LibraryScreen(vm, nav, actions, libTab, { libTab = it }, bottomPad)
                    Dest.Favorites -> FavoritesScreen(vm, nav, actions, bottomPad)
                    Dest.Settings -> SettingsScreen(vm, bottomPad)
                    is Dest.Album -> AlbumScreen(vm, d.key, nav, actions, bottomPad)
                    is Dest.Artist -> ArtistScreen(vm, d.name, nav, actions, bottomPad)
                    is Dest.Folder -> FolderScreen(vm, d.path, d.name, nav, actions, bottomPad)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(hasCurrent, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                MiniPlayer(vm, p, haze, onOpen = { playerOpen = true })
            }
            BottomNav(stack.first(), haze, onSelect = { nav.tab(it, null) })
        }

        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(260)),
        ) {
            PlayerScreen(
                vm = vm,
                colors = colors,
                actions = actions,
                onClose = { playerOpen = false },
                onLyrics = { lyricsOpen = true },
                onQueue = { queueOpen = true },
                onTimer = { timerOpen = true },
            )
        }

        AnimatedVisibility(
            visible = lyricsOpen,
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(240)),
        ) {
            LyricsScreen(vm, colors, onClose = { lyricsOpen = false })
        }

        AnimatedVisibility(queueOpen, enter = fadeIn(), exit = fadeOut()) {
            QueueSheet(vm, onClose = { queueOpen = false })
        }
        AnimatedVisibility(timerOpen, enter = fadeIn(), exit = fadeOut()) {
            TimerSheet(vm, onClose = { timerOpen = false })
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
private fun MiniPlayer(vm: MainViewModel, p: PlayerState, haze: HazeState, onOpen: () -> Unit) {
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
    val id = p.currentId ?: return
    val fav = id in favs

    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .height(66.dp)
            .clip(shape)
            .glassBlur(haze, Color(0x8C281630))
            .border(1.dp, Color(0x2EFFFFFF), shape)
            .clickable(role = Role.Button, onClickLabel = "Открыть плеер", onClick = onOpen)
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
            Modifier.fillMaxSize().padding(start = 9.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(id, Modifier.size(48.dp), corner = 13.dp, sizePx = 128)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun BottomNav(root: Dest, haze: HazeState, onSelect: (Dest) -> Unit) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier
            .fillMaxWidth()
            .glassBlur(haze, Color(0x990A0A12))
            .drawBehind {
                drawLine(Color(0x1AFFFFFF), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .padding(bottom = navBottom)
            .height(68.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(LIcons.Home, "Главная", root == Dest.Home) { onSelect(Dest.Home) }
        NavItem(LIcons.Library, "Библиотека", root == Dest.Library) { onSelect(Dest.Library) }
        NavItem(LIcons.HeartOutline, "Любимое", root == Dest.Favorites) { onSelect(Dest.Favorites) }
        NavItem(LIcons.Sliders, "Настройки", root == Dest.Settings) { onSelect(Dest.Settings) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) LocalAccent.current else LC.Dim
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = color)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

package app.lumen.player.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.Artwork
import app.lumen.player.data.LyricsResult
import app.lumen.player.graph
import app.lumen.player.playback.PlayerConnection
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.screens.LyricsList
import app.lumen.player.ui.theme.LumenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран блокировки Lumen: крупные часы, обложка и синхронный текст песни, чтобы подпевать,
 * не разблокируя телефон. Смахни вверх — откроется обычная разблокировка.
 */
class LockActivity : ComponentActivity() {
    private val conn by lazy { PlayerConnection(applicationContext) }
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Телефон разблокировали обычным способом — экран больше не нужен.
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = finish()
        }
        val f = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
        unlockReceiver = r
        setContent { LockScreen(conn, onUnlock = { unlock() }) }
    }

    private fun unlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            })
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        conn.controller?.release()
        super.onDestroy()
    }
}

@Composable
private fun LockScreen(conn: PlayerConnection, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { conn.connect() } }
    val p by conn.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = conn.position
            now = System.currentTimeMillis()
            delay(150)
        }
    }
    var lyrics by remember { mutableStateOf<LyricsResult>(LyricsResult.Idle) }
    var accentInt by remember { mutableStateOf(0xFFB98CFF.toInt()) }
    val id = p.currentId
    LaunchedEffect(id) {
        lyrics = LyricsResult.Idle
        if (id == null) return@LaunchedEffect
        val t = withContext(Dispatchers.IO) { g.dao.byUri(id) } ?: return@LaunchedEffect
        lyrics = runCatching { g.lyrics.load(t) }.getOrDefault(LyricsResult.NotFound)
        val bmp = Artwork.load(ctx, id, 256)
        if (bmp != null) {
            val pal = withContext(Dispatchers.Default) { androidx.palette.graphics.Palette.from(bmp).generate() }
            (pal.vibrantSwatch ?: pal.lightVibrantSwatch ?: pal.dominantSwatch)?.rgb?.let { accentInt = it }
        }
    }
    val accent by animateColorAsState(Color(accentInt).copy(alpha = 1f), tween(700), label = "lockAcc")
    val favs by remember { g.dao.favoriteUris() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val fav = id != null && id in favs
    val backdrop = id?.let { Artwork.peek(it, 256) }

    // Жест «вверх — разблокировать»: экран тянется за пальцем и мягко уезжает.
    val drag = remember { Animatable(0f) }

    LumenTheme(accent) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF07070D))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (drag.value < -size.height * 0.18f) {
                                    drag.animateTo(-size.height.toFloat(), tween(260))
                                    onUnlock()
                                    drag.snapTo(0f)
                                } else {
                                    drag.animateTo(0f, spring(dampingRatio = 0.8f))
                                }
                            }
                        },
                    ) { change, dy ->
                        change.consume()
                        scope.launch { drag.snapTo((drag.value + dy).coerceAtMost(0f)) }
                    }
                }
        ) {
            if (backdrop != null) {
                Image(
                    backdrop.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = 0.55f },
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0x8807070D), Color(0x5507070D), Color(0xEE07070D))))
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = drag.value * 0.6f
                        alpha = 1f + drag.value / (size.height * 0.6f)
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(28.dp))
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), fontSize = 76.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = (-2).sp)
                Text(
                    SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date(now)).replaceFirstChar { it.uppercase() },
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Cover(id, Modifier.size(56.dp), corner = 14.dp, sizePx = 256)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.artist, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    // Длительность справа от названия.
                    Text(
                        app.lumen.player.ui.components.formatMs(p.durationMs),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = lyrics,
                        transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
                        label = "lockLyrics",
                    ) { l ->
                        if (l is LyricsResult.Found) {
                            LyricsList(
                                lines = l.lines,
                                synced = l.synced,
                                position = position,
                                onSeek = null,
                                big = true,
                                contentPadding = PaddingValues(vertical = 60.dp),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Cover(id, Modifier.size(260.dp), corner = 28.dp, sizePx = 640)
                            }
                        }
                    }
                }
                // Перемотка: полоса-комета, едет вслед за движением пальца.
                app.lumen.player.ui.components.CometSlider(
                    fraction = if (p.durationMs > 0) position.toFloat() / p.durationMs else 0f,
                    onSeek = { f -> conn.seekTo((f * p.durationMs).toLong()) },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    app.lumen.player.ui.components.RollingText(app.lumen.player.ui.components.formatMs(position), 12.sp, Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    app.lumen.player.ui.components.RollingText("−" + app.lumen.player.ui.components.formatMs((p.durationMs - position).coerceAtLeast(0L)), 12.sp, Color.White.copy(alpha = 0.6f))
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LockBtn(LIcons.Shuffle, 24, "Перемешать", tint = if (p.shuffle) accent else Color.White.copy(alpha = 0.75f)) { conn.toggleShuffle() }
                    LockBtn(LIcons.Prev, 30, "Предыдущий") { conn.previous() }
                    Box(
                        Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { conn.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (p.isPlaying) LIcons.Pause else LIcons.Play, null, Modifier.size(32.dp), tint = Color(0xFF0B0B12))
                    }
                    LockBtn(LIcons.Next, 30, "Следующий") { conn.next() }
                    LockBtn(if (fav) LIcons.Heart else LIcons.HeartOutline, 24, "Любимое", tint = if (fav) accent else Color.White.copy(alpha = 0.75f)) {
                        val u = id ?: return@LockBtn
                        scope.launch(Dispatchers.IO) {
                            if (g.dao.favoriteCount(u) > 0) g.dao.removeFavorite(u)
                            else g.dao.addFavorite(app.lumen.player.data.FavoriteEntity(u, System.currentTimeMillis()))
                        }
                    }
                }
                val bob by rememberInfiniteTransition(label = "unlock").animateFloat(
                    0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
                )
                Column(
                    Modifier.padding(bottom = 18.dp).graphicsLayer { translationY = -bob * 6.dp.toPx() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(LIcons.ArrowUp, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.6f))
                    Text("Смахни вверх, чтобы разблокировать", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LockBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    sizeDp: Int,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(sizeDp.dp), tint = tint)
    }
}

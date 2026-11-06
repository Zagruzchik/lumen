package app.lumen.player.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.lumen.player.R
import app.lumen.player.data.Artwork
import app.lumen.player.graph
import app.lumen.player.playback.ACTION_FAVORITE
import app.lumen.player.playback.PlaybackService
import app.lumen.player.playback.toMediaItem
import app.lumen.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ======================= состояние =======================

/** Что показывает виджет. Хранится в SharedPreferences, картинки — файлами. */
data class WidgetState(
    val hasTrack: Boolean,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val fav: Boolean,
    val stamp: Long,
)

object WidgetStore {
    private fun sp(c: Context) = c.getSharedPreferences("lumen_widget", Context.MODE_PRIVATE)
    fun coverFile(c: Context) = File(c.filesDir, "widget_cover.png")
    fun bgFile(c: Context) = File(c.filesDir, "widget_bg.png")

    fun read(c: Context): WidgetState {
        val s = sp(c)
        return WidgetState(
            hasTrack = s.getBoolean("has", false),
            title = s.getString("title", "").orEmpty(),
            artist = s.getString("artist", "").orEmpty(),
            playing = s.getBoolean("playing", false),
            fav = s.getBoolean("fav", false),
            stamp = s.getLong("stamp", 0L),
        )
    }

    fun lastId(c: Context): String? = sp(c).getString("id", null)

    fun write(c: Context, id: String?, title: String, artist: String, playing: Boolean, fav: Boolean, stamp: Long) {
        sp(c).edit()
            .putBoolean("has", id != null)
            .putString("id", id)
            .putString("title", title)
            .putString("artist", artist)
            .putBoolean("playing", playing)
            .putBoolean("fav", fav)
            .putLong("stamp", stamp)
            .apply()
    }
}

/** Обновление виджета из сервиса воспроизведения. Обложка и фон перерисовываются только при смене трека. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun push(context: Context, player: Player, favorites: Set<String>) {
        val app = context.applicationContext
        val id = player.currentMediaItem?.mediaId
        val md = player.mediaMetadata
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        val playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        val fav = id != null && id in favorites
        job?.cancel()
        job = scope.launch {
            delay(120) // склеиваем пачку событий в одно обновление
            var stamp = WidgetStore.read(app).stamp
            if (id != WidgetStore.lastId(app) || !WidgetStore.coverFile(app).exists()) {
                renderArt(app, id)
                stamp = System.currentTimeMillis()
            }
            WidgetStore.write(app, id, title, artist, playing, fav, stamp)
            runCatching { LumenWidget().updateAll(app) }
        }
    }

    fun stopped(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val s = WidgetStore.read(app)
            WidgetStore.write(app, WidgetStore.lastId(app), s.title, s.artist, false, s.fav, s.stamp)
            runCatching { LumenWidget().updateAll(app) }
        }
    }

    /** Обложка 360px и «стеклянный» фон: обложка, размытая до цветовых пятен, под тёмной вуалью. */
    private fun renderArt(c: Context, id: String?) {
        val cover = id?.let { Artwork.loadBlocking(c, it, 360) }
        val coverFile = WidgetStore.coverFile(c)
        val bgFile = WidgetStore.bgFile(c)
        if (cover == null) {
            coverFile.delete()
            bgFile.delete()
            return
        }
        val sq = squareCrop(cover, 360)
        coverFile.outputStream().use { sq.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val tiny = Bitmap.createScaledBitmap(sq, 10, 10, true)
        val big = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, 40, 40, true), 320, 320, true)
        val bg = big.copy(Bitmap.Config.ARGB_8888, true)
        val cv = android.graphics.Canvas(bg)
        cv.drawColor(0xB30C0A12.toInt())
        val shade = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 320f, 320f,
                intArrayOf(0x22FFFFFF, 0x00000000, 0x55000000),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        cv.drawRect(0f, 0f, 320f, 320f, shade)
        bgFile.outputStream().use { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun squareCrop(b: Bitmap, size: Int): Bitmap {
        val s = minOf(b.width, b.height)
        val x = (b.width - s) / 2
        val y = (b.height - s) / 2
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(b, x, y, s, s), size, size, true)
    }
}

// ======================= виджет =======================

class LumenWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LumenWidget()
}

private val S_TINY = DpSize(110.dp, 48.dp)
private val S_ROW = DpSize(220.dp, 56.dp)
private val S_SQUARE = DpSize(140.dp, 140.dp)
private val S_MEDIUM = DpSize(250.dp, 110.dp)
private val S_LARGE = DpSize(250.dp, 230.dp)

private val White = ColorProvider(Color.White)
private val Dim = ColorProvider(Color(0xB3FFFFFF))
private val Ink = ColorProvider(Color(0xFF0B0B12))

class LumenWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(S_TINY, S_ROW, S_SQUARE, S_MEDIUM, S_LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = WidgetStore.read(context)
        val cover = withContext(Dispatchers.IO) { WidgetStore.coverFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
        val bg = withContext(Dispatchers.IO) { WidgetStore.bgFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
        provideContent { Content(st, cover, bg) }
    }
}

@Composable
private fun Content(st: WidgetState, cover: Bitmap?, bg: Bitmap?) {
    val size = LocalSize.current
    val ctx = LocalContext.current
    val open = actionStartActivity(
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
    Box(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(26.dp)
            .background(Color(0xFF16121E))
            .clickable(open),
    ) {
        if (bg != null) {
            Image(ImageProvider(bg), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(ImageProvider(R.drawable.widget_preview_bg), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        val w = size.width.value
        val h = size.height.value
        when {
            h < 100f && w < 200f -> Tiny(st, cover)
            h < 100f -> RowLayout(st, cover)
            w < 200f -> Square(st, cover)
            h < 200f -> Medium(st, cover)
            else -> Large(st, cover)
        }
    }
}

// ---------- кусочки ----------

@Composable
private fun CoverImg(cover: Bitmap?, side: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp) {
    if (cover != null) {
        Image(
            ImageProvider(cover),
            "Обложка",
            GlanceModifier.size(side).cornerRadius(radius),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(GlanceModifier.size(side).cornerRadius(radius)) {
            Image(ImageProvider(R.drawable.widget_preview_cover), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

@Composable
private fun PlayBtn(playing: Boolean, side: androidx.compose.ui.unit.Dp) {
    Box(
        GlanceModifier
            .size(side)
            .cornerRadius(side / 2)
            .background(Color.White)
            .clickable(actionRunCallback<WidgetPlayPause>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play),
            if (playing) "Пауза" else "Играть",
            GlanceModifier.size(side * 0.46f),
            colorFilter = ColorFilter.tint(Ink),
        )
    }
}

@Composable
private fun IconAct(res: Int, label: String, side: androidx.compose.ui.unit.Dp, tint: ColorProvider = White, action: androidx.glance.action.Action) {
    Box(
        GlanceModifier.size(side + 12.dp).cornerRadius((side + 12.dp) / 2).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(res), label, GlanceModifier.size(side), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
private fun Titles(st: WidgetState, titleSp: Int, artistSp: Int, center: Boolean = false) {
    Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            if (st.hasTrack) st.title.ifBlank { "Без названия" } else "Lumen",
            maxLines = 1,
            style = TextStyle(color = White, fontSize = titleSp.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            if (st.hasTrack) st.artist.ifBlank { "Неизвестный исполнитель" } else "Нажми ▶, чтобы начать",
            maxLines = 1,
            style = TextStyle(color = Dim, fontSize = artistSp.sp),
        )
    }
}

@Composable
private fun Controls(st: WidgetState, play: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.unit.Dp, withFav: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (withFav) {
            IconAct(
                if (st.fav) R.drawable.ic_n_heart else R.drawable.ic_n_heart_outline,
                if (st.fav) "Убрать из любимого" else "В любимое",
                icon,
                if (st.fav) ColorProvider(Color(0xFFFF6B8B)) else White,
                actionRunCallback<WidgetFav>(),
            )
            Spacer(GlanceModifier.width(2.dp))
        }
        IconAct(R.drawable.ic_w_prev, "Предыдущий", icon, action = actionRunCallback<WidgetPrev>())
        Spacer(GlanceModifier.width(6.dp))
        PlayBtn(st.playing, play)
        Spacer(GlanceModifier.width(6.dp))
        IconAct(R.drawable.ic_w_next, "Следующий", icon, action = actionRunCallback<WidgetNext>())
    }
}

// ---------- раскладки под размер ----------

/** 2×1: обложка и кнопка. */
@Composable
private fun Tiny(st: WidgetState, cover: Bitmap?) {
    val h = LocalSize.current.height
    val side = (h - 16.dp).coerceAtLeast(32.dp)
    Row(
        GlanceModifier.fillMaxSize().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverImg(cover, side, 12.dp)
        Spacer(GlanceModifier.defaultWeight())
        PlayBtn(st.playing, side.coerceAtMost(44.dp))
    }
}

/** 4×1: строка «обложка — название — кнопки». */
@Composable
private fun RowLayout(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    val side = (size.height - 16.dp).coerceIn(32.dp, 64.dp)
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImg(cover, side, 12.dp)
        Spacer(GlanceModifier.width(10.dp))
        Box(GlanceModifier.defaultWeight()) { Titles(st, 14, 12) }
        Spacer(GlanceModifier.width(4.dp))
        Controls(st, play = side.coerceAtMost(42.dp), icon = 20.dp, withFav = size.width >= 300.dp)
    }
}

/** 2×2: большая обложка, снизу название и кнопка. */
@Composable
private fun Square(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            val side = minOf(size.width - 24.dp, size.height - 82.dp).coerceAtLeast(40.dp)
            CoverImg(cover, side, 16.dp)
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.defaultWeight()) { Titles(st, 13, 11) }
            Spacer(GlanceModifier.width(6.dp))
            PlayBtn(st.playing, 40.dp)
        }
    }
}

/** 4×2: обложка слева, справа название и полный набор кнопок. */
@Composable
private fun Medium(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    val side = (size.height - 28.dp).coerceIn(60.dp, 150.dp)
    Row(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImg(cover, side, 18.dp)
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Text("LUMEN", style = TextStyle(color = Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(2.dp))
            Titles(st, 16, 13)
            Spacer(GlanceModifier.height(10.dp))
            Controls(st, play = 44.dp, icon = 22.dp, withFav = size.width >= 290.dp)
        }
    }
}

/** 4×3 и больше: крупная обложка сверху, по центру название и кнопки. */
@Composable
private fun Large(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    val side = minOf(size.width - 40.dp, size.height - 150.dp).coerceAtLeast(80.dp)
    Column(
        GlanceModifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImg(cover, side, 22.dp)
        Spacer(GlanceModifier.height(12.dp))
        Titles(st, 18, 14, center = true)
        Spacer(GlanceModifier.height(10.dp))
        Controls(st, play = 52.dp, icon = 24.dp, withFav = true)
    }
}

// ======================= действия =======================

/** Подключается к сессии плеера, выполняет команду и отпускает соединение. */
private suspend fun withController(context: Context, block: suspend (MediaController) -> Unit) {
    withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val c = MediaController.Builder(context, token).buildAsync().await()
        try {
            block(c)
            delay(400)
        } finally {
            c.release()
        }
    }
}

/** Если плеер пуст (приложение было закрыто) — восстанавливаем последнюю очередь. */
private suspend fun ensureQueue(context: Context, c: MediaController): Boolean {
    if (c.mediaItemCount > 0) return true
    val g = context.graph
    val uris = g.prefs.queue
    val tracks = withContext(Dispatchers.IO) {
        if (uris.isNotEmpty()) {
            val found = uris.chunked(500).flatMap { g.dao.byUris(it) }.associateBy { it.uri }
            uris.mapNotNull { found[it] }
        } else {
            g.dao.mostLoved()
        }
    }
    if (tracks.isEmpty()) return false
    val saved = uris.getOrNull(g.prefs.queueIndex)
    val idx = tracks.indexOfFirst { it.uri == saved }.coerceAtLeast(0)
    c.setMediaItems(tracks.map { it.toMediaItem() }, idx, if (saved != null) g.prefs.queuePosition else 0L)
    c.prepare()
    return true
}

class WidgetPlayPause : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { c ->
            if (!ensureQueue(context, c)) return@withController
            if (c.isPlaying) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
                c.play()
            }
        }
    }
}

class WidgetNext : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { c -> if (ensureQueue(context, c)) c.seekToNextMediaItem() }
    }
}

class WidgetPrev : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { c -> if (ensureQueue(context, c)) c.seekToPrevious() }
    }
}

class WidgetFav : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { c ->
            if (c.mediaItemCount > 0) c.sendCustomCommand(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
        }
    }
}

package app.lumen.player.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
    val shuffle: Boolean,
    val accent: Int,
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
            shuffle = s.getBoolean("shuffle", false),
            accent = s.getInt("accent", 0xFFB98CFF.toInt()),
            stamp = s.getLong("stamp", 0L),
        )
    }

    fun lastId(c: Context): String? = sp(c).getString("id", null)

    fun setAccent(c: Context, color: Int) = sp(c).edit().putInt("accent", color).apply()

    fun write(c: Context, id: String?, title: String, artist: String, playing: Boolean, fav: Boolean, shuffle: Boolean, stamp: Long) {
        sp(c).edit()
            .putBoolean("shuffle", shuffle)
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
        val shuffle = player.shuffleModeEnabled
        job?.cancel()
        job = scope.launch {
            delay(120) // склеиваем пачку событий в одно обновление
            var stamp = WidgetStore.read(app).stamp
            if (id != WidgetStore.lastId(app) || !WidgetStore.coverFile(app).exists()) {
                renderArt(app, id)
                stamp = System.currentTimeMillis()
            }
            WidgetStore.write(app, id, title, artist, playing, fav, shuffle, stamp)
            runCatching { LumenWidget().updateAll(app) }
        }
    }

    fun stopped(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val s = WidgetStore.read(app)
            WidgetStore.write(app, WidgetStore.lastId(app), s.title, s.artist, false, s.fav, s.shuffle, s.stamp)
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
        // Акцент из обложки — для эквалайзера и подсветки кнопок.
        runCatching {
            val pal = androidx.palette.graphics.Palette.from(sq).maximumColorCount(16).generate()
            val sw = pal.vibrantSwatch ?: pal.lightVibrantSwatch ?: pal.dominantSwatch
            if (sw != null) {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(sw.rgb, hsv)
                hsv[1] = hsv[1].coerceIn(0.35f, 0.8f)
                hsv[2] = hsv[2].coerceAtLeast(0.85f)
                WidgetStore.setAccent(c, android.graphics.Color.HSVToColor(hsv))
            }
        }
        coverFile.outputStream().use { sq.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val tiny = Bitmap.createScaledBitmap(sq, 10, 10, true)
        val big = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, 40, 40, true), 320, 320, true)
        val bg = big.copy(Bitmap.Config.ARGB_8888, true)
        val cv = android.graphics.Canvas(bg)
        cv.drawColor(0xC70C0A12.toInt())
        val shade = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 320f, 320f,
                intArrayOf(0x22FFFFFF, 0x00000000, 0x55000000),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        cv.drawRect(0f, 0f, 320f, 320f, shade)
        val acc = WidgetStore.read(c).accent
        val glow = android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(
                40f, 300f, 260f,
                intArrayOf((acc and 0x00FFFFFF) or 0x40000000, 0x00000000),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        cv.drawRect(0f, 0f, 320f, 320f, glow)
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

private val White = ColorProvider(Color.White)
private val Dim = ColorProvider(Color(0xB3FFFFFF))
private val Faint = ColorProvider(Color(0x80FFFFFF))
private val Ink = ColorProvider(Color(0xFF0B0B12))

class LumenWidget : GlanceAppWidget() {
    // Точный размер: раскладка считается под реальные габариты виджета, а не под «корзины».
    override val sizeMode: SizeMode = SizeMode.Exact

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
    // Без общего нажатия на весь виджет: никакой ряби по всей площади.
    Box(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(28.dp)
            .background(Color(0xFF16121E)),
    ) {
        if (bg != null) {
            Image(ImageProvider(bg), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(ImageProvider(R.drawable.widget_preview_bg), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        val w = size.width
        val h = size.height
        when {
            h < 100.dp -> Strip(st, cover)
            w < 200.dp -> Narrow(st, cover)
            h < 290.dp -> Card(st, cover)
            else -> Poster(st, cover)
        }
    }
}

// ---------- детали ----------

@Composable
private fun openApp(): androidx.glance.action.Action {
    val ctx = LocalContext.current
    return actionStartActivity(
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
}

/**
 * Стандартная «медиа-кнопка» Android (как на наушниках): система сама поднимет плеер,
 * даже если приложение закрыто, и продолжит последнюю очередь.
 */
@Composable
private fun mediaKey(code: Int): androidx.glance.action.Action {
    val ctx = LocalContext.current
    val i = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(ctx, androidx.media3.session.MediaButtonReceiver::class.java))
        .setData(android.net.Uri.parse("lumen-widget://key/$code"))
        .putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
    return androidx.glance.appwidget.action.actionSendBroadcast(i)
}

@Composable
private fun widgetAction(action: String): androidx.glance.action.Action {
    val ctx = LocalContext.current
    return androidx.glance.appwidget.action.actionSendBroadcast(
        Intent(ctx, WidgetActionReceiver::class.java).setAction(action)
    )
}

@Composable
private fun CoverImg(cover: Bitmap?, side: Dp, radius: Dp) {
    if (cover != null) {
        Image(ImageProvider(cover), "Обложка", GlanceModifier.size(side).cornerRadius(radius).clickable(openApp()), contentScale = ContentScale.Crop)
    } else {
        Box(GlanceModifier.size(side).cornerRadius(radius).clickable(openApp())) {
            Image(ImageProvider(R.drawable.widget_preview_cover), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

/** Эквалайзер: пока играет — живой (анимация крутится на самом рабочем столе), на паузе — застывший. */
@Composable
private fun Visualizer(st: WidgetState, w: Dp, h: Dp) {
    val ctx = LocalContext.current
    if (st.playing) {
        val rv = android.widget.RemoteViews(ctx.packageName, R.layout.widget_eq)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            rv.setColorStateList(R.id.eq, "setIndeterminateTintList", android.content.res.ColorStateList.valueOf(st.accent))
        }
        androidx.glance.appwidget.AndroidRemoteViews(rv, GlanceModifier.size(w, h))
    } else {
        Image(
            ImageProvider(R.drawable.ic_w_eq),
            null,
            GlanceModifier.size(w, h),
            colorFilter = ColorFilter.tint(Faint),
        )
    }
}

@Composable
private fun PlayBtn(playing: Boolean, side: Dp) {
    Box(
        GlanceModifier
            .size(side)
            .cornerRadius(side / 2)
            .background(Color.White)
            .clickable(mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
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
private fun IconAct(res: Int, label: String, icon: Dp, tint: ColorProvider, action: androidx.glance.action.Action) {
    val touch = icon + 18.dp
    Box(
        GlanceModifier.size(touch).cornerRadius(touch / 2).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(res), label, GlanceModifier.size(icon), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
private fun LikeBtn(st: WidgetState, icon: Dp) = IconAct(
    if (st.fav) R.drawable.ic_n_heart else R.drawable.ic_n_heart_outline,
    if (st.fav) "Убрать из любимого" else "В любимое",
    icon,
    if (st.fav) ColorProvider(Color(st.accent)) else White,
    widgetAction(PlaybackService.WIDGET_FAV),
)

@Composable
private fun ShuffleBtn(st: WidgetState, icon: Dp) = IconAct(
    if (st.shuffle) R.drawable.ic_n_shuffle_on else R.drawable.ic_n_shuffle_off,
    if (st.shuffle) "Перемешивание включено" else "Перемешать",
    icon,
    if (st.shuffle) ColorProvider(Color(st.accent)) else White,
    widgetAction(PlaybackService.WIDGET_SHUFFLE),
)

@Composable
private fun Titles(st: WidgetState, titleSp: Int, artistSp: Int, titleLines: Int, center: Boolean = false) {
    Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            if (st.hasTrack) st.title.ifBlank { "Без названия" } else "Lumen",
            maxLines = titleLines,
            style = TextStyle(
                color = White,
                fontSize = titleSp.sp,
                fontWeight = FontWeight.Bold,
                textAlign = if (center) androidx.glance.text.TextAlign.Center else androidx.glance.text.TextAlign.Start,
            ),
        )
        Text(
            if (st.hasTrack) st.artist.ifBlank { "Неизвестный исполнитель" } else "Нажми ▶, чтобы начать",
            maxLines = 1,
            style = TextStyle(
                color = Dim,
                fontSize = artistSp.sp,
                textAlign = if (center) androidx.glance.text.TextAlign.Center else androidx.glance.text.TextAlign.Start,
            ),
        )
    }
}

/** Ряд кнопок на всю ширину, равномерно: лайк · назад · ПЛЕЙ · вперёд · перемешать. */
@Composable
private fun ControlsRow(st: WidgetState, play: Dp, icon: Dp, full: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (full) {
            LikeBtn(st, icon)
            Spacer(GlanceModifier.defaultWeight())
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        IconAct(R.drawable.ic_w_prev, "Предыдущий", icon, White, mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        Spacer(GlanceModifier.defaultWeight())
        PlayBtn(st.playing, play)
        Spacer(GlanceModifier.defaultWeight())
        IconAct(R.drawable.ic_w_next, "Следующий", icon, White, mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
        Spacer(GlanceModifier.defaultWeight())
        if (full) ShuffleBtn(st, icon)
    }
}

// ---------- раскладки ----------

/** Одна строка (высота до 100dp): обложка, название, кнопки по ширине. */
@Composable
private fun Strip(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    val side = (size.height - 20.dp).coerceIn(32.dp, 64.dp)
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImg(cover, side, 14.dp)
        Spacer(GlanceModifier.width(10.dp))
        Box(GlanceModifier.defaultWeight().clickable(openApp())) { Titles(st, 16, 13, 1) }
        if (size.width >= 330.dp) {
            Spacer(GlanceModifier.width(4.dp))
            LikeBtn(st, 22.dp)
        }
        if (size.width >= 250.dp) {
            IconAct(R.drawable.ic_w_prev, "Предыдущий", 22.dp, White, mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        }
        Spacer(GlanceModifier.width(4.dp))
        PlayBtn(st.playing, side.coerceAtMost(48.dp))
        if (size.width >= 250.dp) {
            Spacer(GlanceModifier.width(4.dp))
            IconAct(R.drawable.ic_w_next, "Следующий", 22.dp, White, mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
        }
    }
}

/** Узкий (до 200dp шириной): обложка сверху, название, кнопка. */
@Composable
private fun Narrow(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    val side = minOf(size.width - 28.dp, size.height - 104.dp).coerceAtLeast(44.dp)
    Column(GlanceModifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
            CoverImg(cover, side, 18.dp)
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.defaultWeight().clickable(openApp())) { Titles(st, 15, 12, 1) }
            Spacer(GlanceModifier.width(6.dp))
            PlayBtn(st.playing, 42.dp)
        }
    }
}

/**
 * Карточка (2 ряда, широкая): обложка во всю высоту слева плавно растворяется в фон,
 * справа крупно название, исполнитель и живой эквалайзер, снизу — кнопки.
 */
@Composable
private fun Card(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    val h = size.height
    val panel = h
    val textX = panel * 0.8f
    val contentW = size.width - textX - 16.dp
    val full = contentW >= 205.dp
    val play = if (h >= 150.dp) 54.dp else 48.dp
    val icon = if (h >= 150.dp) 24.dp else 22.dp
    Box(GlanceModifier.fillMaxSize()) {
        // Обложка-панель.
        Box(GlanceModifier.size(panel, h).clickable(openApp())) {
            if (cover != null) {
                Image(ImageProvider(cover), "Обложка", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(ImageProvider(R.drawable.widget_preview_cover), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
        }
        // Растворение обложки в фон.
        Row(GlanceModifier.fillMaxSize()) {
            Spacer(GlanceModifier.width(panel * 0.35f))
            Image(ImageProvider(R.drawable.widget_fade), null, GlanceModifier.width(panel * 0.66f).fillMaxHeight(), contentScale = ContentScale.FillBounds)
        }
        // Содержимое.
        Row(GlanceModifier.fillMaxSize()) {
            Spacer(GlanceModifier.width(textX))
            Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(top = 14.dp, end = 16.dp, bottom = 10.dp)) {
                Row(GlanceModifier.fillMaxWidth().clickable(openApp()), verticalAlignment = Alignment.Top) {
                    Box(GlanceModifier.defaultWeight()) {
                        Titles(st, if (h >= 150.dp) 19 else 17, if (h >= 150.dp) 15 else 14, if (h >= 160.dp) 2 else 1)
                    }
                    Spacer(GlanceModifier.width(10.dp))
                    Visualizer(st, 26.dp, 20.dp)
                }
                Spacer(GlanceModifier.defaultWeight())
                if (full) {
                    ControlsRow(st, play = play, icon = icon, full = true)
                } else {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconAct(R.drawable.ic_w_prev, "Предыдущий", icon, White, mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                        Spacer(GlanceModifier.defaultWeight())
                        PlayBtn(st.playing, play)
                        Spacer(GlanceModifier.defaultWeight())
                        IconAct(R.drawable.ic_w_next, "Следующий", icon, White, mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                    }
                }
            }
        }
    }
}

/** Большой (3+ ряда): крупная обложка, по центру название и эквалайзер, кнопки внизу. */
@Composable
private fun Poster(st: WidgetState, cover: Bitmap?) {
    val size = LocalSize.current
    val side = minOf(size.width - 48.dp, size.height - 190.dp).coerceAtLeast(80.dp)
    Column(
        GlanceModifier.fillMaxSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
            CoverImg(cover, side, 24.dp)
        }
        Spacer(GlanceModifier.height(12.dp))
        Visualizer(st, 30.dp, 22.dp)
        Spacer(GlanceModifier.height(6.dp))
        Titles(st, 20, 15, 2, center = true)
        Spacer(GlanceModifier.height(10.dp))
        ControlsRow(st, play = 60.dp, icon = 26.dp, full = size.width >= 220.dp)
    }
}

// ======================= действия =======================

/** Лайк и перемешивание с виджета: передаём живому сервису плеера. */
class WidgetActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            PlaybackService.instance?.handleWidgetAction(action)
        }
    }
}

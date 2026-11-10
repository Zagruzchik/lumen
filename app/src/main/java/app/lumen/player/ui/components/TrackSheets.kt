package app.lumen.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.formatLabel
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.launch

/** Показывать ли бейджи формата (FLAC/MP3) по всему приложению. */
val LocalShowFormat = compositionLocalOf { true }

/** Подсвечивать ли пропетую часть строки (караоке). */
val LocalKaraoke = compositionLocalOf { true }

/** Треки с отметкой «Не нравится» (приглушены в списках). */
val LocalDisliked = compositionLocalOf<Set<String>> { emptySet() }

/** Запрос на нижнее меню трека: сам трек и действия того экрана, откуда его открыли. */
data class TrackMenuRequest(val track: TrackEntity, val fav: Boolean, val actions: TrackActions, val fromPlayer: Boolean = false)

/**
 * Стеклянная шторка снизу: выезжает пружинкой, тянется пальцем вниз и закрывается,
 * если отпустить ниже трети высоты или смахнуть.
 */
@Composable
fun BottomGlassSheet(onClose: () -> Unit, sheetModifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
        )
        Column(
            sheetModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = drag.value }
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .clip(RoundedCornerShape(30.dp))
                .background(app.lumen.player.ui.theme.LT.sheetBrush())
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(30.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                    onDragStopped = { v ->
                        if (drag.value > 220f || v > 1600f) onClose()
                        else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.8f)) }
                    },
                )
                .padding(bottom = 10.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            }
            content()
        }
    }
}

@Composable
private fun SheetHeader(t: TrackEntity) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(t.uri, Modifier.size(56.dp), corner = 14.dp, sizePx = 192)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 13.sp,
                color = LC.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, title: String, tint: Color = LC.Text, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(19.dp), tint = tint) }
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

/** Меню трека снизу: все действия одним списком. */
@Composable
fun TrackMenuSheet(
    req: TrackMenuRequest,
    onClose: () -> Unit,
    sheetModifier: Modifier = Modifier,
    onSound: (() -> Unit)? = null,
    onTrim: ((TrackEntity) -> Unit)? = null,
    onCar: (() -> Unit)? = null,
    onLyrics: ((TrackEntity) -> Unit)? = null,
) {
    val t = req.track
    val a = req.actions
    val accent = LocalAccent.current
    fun run(f: () -> Unit) {
        onClose()
        f()
    }
    BottomGlassSheet(onClose, sheetModifier) {
        SheetHeader(t)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            if (req.fromPlayer && onSound != null) SheetItem(LIcons.Wave, "Скорость, тон и повтор A–B") { run(onSound) }
            if (req.fromPlayer && onCar != null) SheetItem(LIcons.Car, "Автомобильный режим") { run(onCar) }
            SheetItem(LIcons.PlayNext, "Играть следующим") { run { a.onPlayNext(t) } }
            SheetItem(LIcons.Queue, "В конец очереди") { run { a.onEnqueue(t) } }
            a.onAddToPlaylist?.let { f -> SheetItem(LIcons.Playlist, "В плейлист…") { run { f(t) } } }
            a.onPlaySimilar?.let { f -> SheetItem(LIcons.Sparkle, "Играть похожее") { run { f(t) } } }
            SheetItem(
                if (req.fav) LIcons.Heart else LIcons.HeartOutline,
                if (req.fav) "Убрать из любимого" else "В любимое",
                tint = if (req.fav) accent else LC.Text,
            ) { run { a.onToggleFav(t) } }
            a.onDislike?.let { f ->
                val dis = t.uri in LocalDisliked.current
                SheetItem(
                    if (dis) LIcons.HeartOutline else LIcons.Dislike,
                    if (dis) "Убрать из «Не нравится»" else "Не нравится",
                    tint = if (dis) LC.Text else LC.Dim,
                ) { run { f(t) } }
            }
            SheetItem(LIcons.Disc, "К альбому") { run { a.onOpenAlbum(t) } }
            SheetItem(LIcons.Person, "К исполнителю") { run { a.onOpenArtist(t) } }
            a.onEdit?.let { f -> SheetItem(LIcons.Edit, "Изменить теги и обложку") { run { f(t) } } }
            onLyrics?.let { f -> SheetItem(LIcons.Lyrics, "Текст песни и тайминги") { run { f(t) } } }
            onTrim?.let { f -> SheetItem(LIcons.Trim, "Обрезать начало и конец") { run { f(t) } } }
            if (app.lumen.player.data.Online.isOnline(t.uri)) {
                a.onDownload?.let { f -> SheetItem(LIcons.Download, "Скачать на телефон") { run { f(t) } } }
                app.lumen.player.data.Online.pageOf(t)?.let { url ->
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    SheetItem(LIcons.Globe, "Открыть на ${app.lumen.player.data.Online.labelOf(t.uri)}") {
                        run {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                }
            }
            a.onInfo?.let { f -> SheetItem(LIcons.Info, "Информация о треке") { run { f(t) } } }
            if (!app.lumen.player.data.Online.isOnline(t.uri)) {
                a.onDelete?.let { f -> SheetItem(LIcons.Trash, "Удалить файл", tint = LC.Dim) { run { f(t) } } }
            }
            a.onRemoveFromPlaylist?.let { f -> SheetItem(LIcons.Trash, "Убрать из плейлиста", tint = Color(0xFFFF8A8A)) { run { f(t) } } }
        }
    }
}

/** Всё о треке: файл, качество, теги, статистика. */
data class TrackInfo(
    val path: String,
    val sizeBytes: Long,
    val format: String,
    val bitrate: Int,
    val sampleRate: Int,
    val bits: Int,
    val channels: Int,
    val plays: Int,
    val listenedMs: Long,
    val firstPlayed: Long?,
    val lastPlayed: Long?,
    val dateAdded: Long,
)

@Composable
fun TrackInfoSheet(t: TrackEntity, load: suspend (TrackEntity) -> TrackInfo, onClose: () -> Unit, sheetModifier: Modifier = Modifier) {
    val info by produceState<TrackInfo?>(null, t.uri) { value = runCatching { load(t) }.getOrNull() }
    val df = remember { java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", java.util.Locale("ru")) }
    BottomGlassSheet(onClose, sheetModifier) {
        SheetHeader(t)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            val i = info
            Group("Звук") {
                InfoRow("Формат", i?.format ?: formatLabel(t.mime, t.uri))
                if ((i?.sampleRate ?: 0) > 0) InfoRow("Частота", "${"%.1f".format(i!!.sampleRate / 1000f).removeSuffix(",0").removeSuffix(".0")} кГц")
                if ((i?.bits ?: 0) > 0) InfoRow("Разрядность", "${i!!.bits} бит")
                if ((i?.channels ?: 0) > 0) InfoRow("Каналы", if (i!!.channels == 1) "моно" else if (i.channels == 2) "стерео" else "${i.channels}")
                if (t.bitrate > 0) InfoRow("Битрейт", "${t.bitrate / 1000} кбит/с")
                InfoRow("Длительность", formatMs(t.durationMs))
            }
            Group("Теги") {
                InfoRow("Название", t.title)
                InfoRow("Исполнитель", t.artist)
                if (t.album.isNotBlank()) InfoRow("Альбом", t.album)
                if (t.albumArtist.isNotBlank() && t.albumArtist != t.artist) InfoRow("Исп. альбома", t.albumArtist)
                if (t.year > 0) InfoRow("Год", t.year.toString())
                if (t.trackNo > 0) InfoRow("Номер", if (t.discNo > 1) "${t.discNo}-${t.trackNo}" else t.trackNo.toString())
            }
            Group("Файл") {
                InfoRow("Путь", i?.path ?: t.path, wrap = true)
                if ((i?.sizeBytes ?: t.size) > 0) InfoRow("Размер", "%.1f МБ".format((i?.sizeBytes ?: t.size) / 1024f / 1024f))
                if (t.dateAdded > 0) InfoRow("Добавлен", df.format(java.util.Date(t.dateAdded * if (t.dateAdded < 10_000_000_000L) 1000 else 1)))
            }
            Group("Статистика") {
                InfoRow("Прослушиваний", "${i?.plays ?: 0}")
                if ((i?.listenedMs ?: 0) > 0) InfoRow("Всего слушал", "${i!!.listenedMs / 60000} мин")
                i?.firstPlayed?.let { InfoRow("Впервые", df.format(java.util.Date(it))) }
                i?.lastPlayed?.let { InfoRow("Последний раз", df.format(java.util.Date(it))) }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 13.sp, color = LocalAccent.current, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        content = content,
    )
}

@Composable
private fun InfoRow(label: String, value: String, wrap: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, fontSize = 14.sp, color = LC.Dim, modifier = Modifier.width(120.dp))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = if (wrap) 6 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

package app.lumen.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.data.AlbumRow
import app.lumen.player.data.Artwork
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.formatLabel
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent

fun Modifier.glass(
    shape: Shape,
    fill: Color = LC.Glass,
    stroke: Color = LC.Border,
): Modifier = this.clip(shape).background(fill, shape).border(1.dp, stroke, shape)

@Composable
fun IconBtn(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    tint: Color = LC.Text,
    glass: Boolean = true,
) {
    val shape = if (glass) RoundedCornerShape(14.dp) else CircleShape
    Box(
        modifier
            .size(size)
            .then(if (glass) Modifier.glass(shape) else Modifier.clip(shape))
            .clickable(role = Role.Button, onClickLabel = desc, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = tint)
    }
}

@Composable
fun LChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(accent)
                else Modifier.background(LC.Glass).border(1.dp, LC.Border, shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) LC.OnAccent else LC.Text,
            maxLines = 1,
        )
    }
}

@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(25.dp)
    Row(
        modifier
            .height(50.dp)
            .then(
                if (primary) Modifier.clip(shape).background(accent)
                // Плотное «матовое стекло»: ничего из фона под кнопкой не просвечивает полосами.
                else Modifier
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2B2534), Color(0xFF1C1823))))
                    .background(accent.copy(alpha = 0.07f))
                    .border(1.dp, Color(0x2BFFFFFF), shape)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (primary) LC.OnAccent else LC.Text
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = c)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Badge(text: String, strong: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .then(if (strong) Modifier.clip(shape).background(Color(0xFFFFC371)) else Modifier.glass(shape, LC.GlassStrong))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (strong) LC.OnAccent else LC.Text,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                fontSize = 14.sp,
                color = LocalAccent.current,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(8.dp),
            )
        }
    }
}

// ---------- обложки ----------

@Composable
fun rememberArtwork(uri: String?, sizePx: Int): ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    // Новый адрес — сразу берём из кэша (или пусто), а не показываем прошлую картинку, пока грузится новая.
    val rev by Artwork.revision.collectAsState()
    val state = androidx.compose.runtime.remember(uri, sizePx, rev) {
        androidx.compose.runtime.mutableStateOf(Artwork.peek(uri, sizePx)?.asImageBitmap())
    }
    androidx.compose.runtime.LaunchedEffect(uri, sizePx, rev) {
        if (state.value == null && uri != null) state.value = Artwork.load(ctx, uri, sizePx)?.asImageBitmap()
    }
    return state.value
}

fun placeholderBrush(seed: String): Brush {
    val h = (seed.hashCode() and 0x7fffffff) % 360
    val c1 = Color.hsv(h.toFloat(), 0.62f, 0.86f)
    val c2 = Color.hsv(((h + 70) % 360).toFloat(), 0.7f, 0.42f)
    return Brush.linearGradient(listOf(c1, c2))
}

@Composable
fun Cover(
    uri: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    sizePx: Int = 256,
    seed: String? = uri,
) {
    val bmp = rememberArtwork(uri, sizePx)
    val shape = if (corner >= 999.dp) CircleShape else RoundedCornerShape(corner)
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(placeholderBrush(seed ?: "")), contentAlignment = Alignment.Center) {
                Icon(LIcons.Note, null, Modifier.fillMaxSize(0.36f), tint = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

// ---------- строки списка ----------

class TrackActions(
    val onPlayNext: (TrackEntity) -> Unit,
    val onEnqueue: (TrackEntity) -> Unit,
    val onToggleFav: (TrackEntity) -> Unit,
    val onOpenAlbum: (TrackEntity) -> Unit,
    val onOpenArtist: (TrackEntity) -> Unit,
    val onAddToPlaylist: ((TrackEntity) -> Unit)? = null,
    val onPlaySimilar: ((TrackEntity) -> Unit)? = null,
    val onEdit: ((TrackEntity) -> Unit)? = null,
    /** Только внутри плейлиста. */
    val onRemoveFromPlaylist: ((TrackEntity) -> Unit)? = null,
    val onInfo: ((TrackEntity) -> Unit)? = null,
    /** Открыть нижнее меню трека (с действиями того экрана, откуда открыли). */
    val onMenu: ((TrackEntity, Boolean, TrackActions) -> Unit)? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    t: TrackEntity,
    current: Boolean,
    fav: Boolean,
    actions: TrackActions,
    onClick: () -> Unit,
    number: Int? = null,
) {
    val accent = LocalAccent.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { actions.onMenu?.invoke(t, fav, actions) ?: run { menu = true } })
            .padding(start = 20.dp, end = 8.dp)
            .height(62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                if (number > 0) number.toString() else "–",
                modifier = Modifier.width(28.dp),
                fontSize = 14.sp,
                color = if (current) accent else LC.Faint,
            )
        } else {
            Cover(t.uri, Modifier.size(48.dp), corner = 12.dp, sizePx = 128)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                fontSize = 15.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                color = if (current) accent else LC.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (LocalShowFormat.current) "${t.artist} · ${formatLabel(t.mime, t.uri)}" else t.artist,
                fontSize = 13.sp,
                color = LC.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (fav) {
            Icon(LIcons.Heart, "В любимом", Modifier.size(16.dp), tint = accent)
            Spacer(Modifier.width(4.dp))
        }
        if (number != null) {
            Text(formatMs(t.durationMs), fontSize = 13.sp, color = LC.Faint)
        }
        Box {
            IconBtn(LIcons.More, "Действия", { actions.onMenu?.invoke(t, fav, actions) ?: run { menu = true } }, glass = false, tint = LC.Dim)
            TrackMenu(menu, { menu = false }, t, fav, actions)
        }
    }
}

@Composable
fun TrackMenu(expanded: Boolean, onDismiss: () -> Unit, t: TrackEntity, fav: Boolean, actions: TrackActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Играть следующим") },
            leadingIcon = { Icon(LIcons.PlayNext, null, Modifier.size(20.dp)) },
            onClick = { onDismiss(); actions.onPlayNext(t) },
        )
        DropdownMenuItem(
            text = { Text("В конец очереди") },
            leadingIcon = { Icon(LIcons.Queue, null, Modifier.size(20.dp)) },
            onClick = { onDismiss(); actions.onEnqueue(t) },
        )
        actions.onAddToPlaylist?.let { f ->
            DropdownMenuItem(
                text = { Text("В плейлист…") },
                leadingIcon = { Icon(LIcons.Playlist, null, Modifier.size(20.dp)) },
                onClick = { onDismiss(); f(t) },
            )
        }
        actions.onPlaySimilar?.let { f ->
            DropdownMenuItem(
                text = { Text("Играть похожее") },
                leadingIcon = { Icon(LIcons.Sparkle, null, Modifier.size(20.dp)) },
                onClick = { onDismiss(); f(t) },
            )
        }
        actions.onRemoveFromPlaylist?.let { f ->
            DropdownMenuItem(
                text = { Text("Убрать из плейлиста") },
                leadingIcon = { Icon(LIcons.Trash, null, Modifier.size(20.dp)) },
                onClick = { onDismiss(); f(t) },
            )
        }
        DropdownMenuItem(
            text = { Text(if (fav) "Убрать из любимого" else "В любимое") },
            leadingIcon = { Icon(if (fav) LIcons.Heart else LIcons.HeartOutline, null, Modifier.size(20.dp)) },
            onClick = { onDismiss(); actions.onToggleFav(t) },
        )
        DropdownMenuItem(
            text = { Text("К альбому") },
            leadingIcon = { Icon(LIcons.Disc, null, Modifier.size(20.dp)) },
            onClick = { onDismiss(); actions.onOpenAlbum(t) },
        )
        DropdownMenuItem(
            text = { Text("К исполнителю") },
            leadingIcon = { Icon(LIcons.Person, null, Modifier.size(20.dp)) },
            onClick = { onDismiss(); actions.onOpenArtist(t) },
        )
        actions.onEdit?.let { f ->
            DropdownMenuItem(
                text = { Text("Изменить теги и обложку") },
                leadingIcon = { Icon(LIcons.Edit, null, Modifier.size(20.dp)) },
                onClick = { onDismiss(); f(t) },
            )
        }
    }
}

@Composable
fun AlbumTile(a: AlbumRow, onClick: () -> Unit, modifier: Modifier = Modifier, coverSize: Dp? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 6.dp)
    ) {
        Box {
            Cover(
                a.coverUri,
                (if (coverSize != null) Modifier.size(coverSize) else Modifier.fillMaxWidth().aspectSquare()),
                corner = 18.dp,
                sizePx = 384,
                seed = a.albumKey,
            )
            if (LocalShowFormat.current) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x73000000))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(formatLabel(a.mime), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(a.album, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.albumArtist, fontSize = 12.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun Modifier.aspectSquare(): Modifier = this.aspectRatio(1f)

fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val n10 = n % 10
    val n100 = n % 100
    val w = when {
        n10 == 1 && n100 != 11 -> one
        n10 in 2..4 && n100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}

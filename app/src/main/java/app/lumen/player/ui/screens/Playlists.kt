package app.lumen.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.PlaylistRow
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.Dest
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.Nav
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.TrackActions
import app.lumen.player.ui.components.TrackRow
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import app.lumen.player.ui.components.pluralRu

/** Обложка плейлиста: коллаж 2×2 из первых треков (или одна обложка, или градиент). */
@Composable
fun PlaylistCollage(vm: MainViewModel, id: Long, size: Dp, corner: Dp) {
    val uris by remember(id) { vm.playlistCovers(id) }.collectAsState(initial = emptyList())
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), Color(0xFF221A2E)))),
        contentAlignment = Alignment.Center,
    ) {
        when {
            uris.size >= 4 -> Column {
                Row {
                    Cover(uris[0], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                    Cover(uris[1], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                }
                Row {
                    Cover(uris[2], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                    Cover(uris[3], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                }
            }
            uris.isNotEmpty() -> Cover(uris[0], Modifier.size(size), corner = 0.dp, sizePx = 512)
            else -> Icon(LIcons.Playlist, null, Modifier.size(size * 0.4f), tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Вкладка «Плейлисты» в медиатеке. */
fun LazyListScope.playlistsTab(
    vm: MainViewModel,
    nav: Nav,
    list: List<PlaylistRow>,
    smart: List<app.lumen.player.ui.SmartRow>,
    onNew: () -> Unit,
) {
    item(key = "newPl") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onNew)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, LocalAccent.current.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(LIcons.Plus, null, Modifier.size(26.dp), tint = LocalAccent.current) }
            Spacer(Modifier.width(14.dp))
            Text("Новый плейлист", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current)
        }
    }
    item(key = "importM3u") {
        val m3uIn = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) vm.importM3u(uri) }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { m3uIn.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "*/*")) }
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { Icon(LIcons.Download, null, Modifier.size(24.dp), tint = LC.Dim) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Импорт из M3U", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Плейлист из другого плеера или с компьютера", fontSize = 12.sp, color = LC.Dim)
            }
        }
    }
    item(key = "quickLists") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PillButton("История", LIcons.Clock, { nav.open(app.lumen.player.ui.Dest.History) }, Modifier.weight(1f), primary = false)
            PillButton("Новое", LIcons.Sparkle, { nav.open(app.lumen.player.ui.Dest.Recent) }, Modifier.weight(1f), primary = false)
        }
    }
    smartPlaylistRows(nav, smart)
    if (list.isEmpty()) {
        item(key = "plEmpty") {
            Text(
                "Собирай свои подборки: долгий тап по любому треку → «В плейлист…»",
                fontSize = 14.sp,
                color = LC.Dim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
    items(list, key = { "pl:${it.id}" }) { pl ->
        Row(
            Modifier
                .fillMaxWidth()
                .animateItem()
                .clickable { nav.open(Dest.Playlist(pl.id)) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCollage(vm, pl.id, 60.dp, 16.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(pl.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${pluralRu(pl.trackCount, "трек", "трека", "треков")} · ${pl.durationMs / 60000} мин",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
            }
        }
    }
}

/** Экран плейлиста: слушать, перемешать, переименовать, удалить; порядок — перетаскиванием. */
@Composable
fun PlaylistScreen(vm: MainViewModel, id: Long, nav: Nav, actions: TrackActions, bottomPad: Dp) {
    val pl by remember(id) { vm.playlist(id) }.collectAsState(initial = null)
    val tracks by remember(id) { vm.playlistTracks(id) }.collectAsState(initial = emptyList())
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val m3uOut = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri -> if (uri != null) vm.exportM3u(id, uri) }

    // Локальная копия порядка: во время перетаскивания двигаем её, в базу пишем при отпускании.
    val order = remember(id) { mutableStateListOf<TrackEntity>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(tracks) { if (dragging < 0) { order.clear(); order.addAll(tracks) } }
    val rowPx = with(LocalDensity.current) { 62.dp.toPx() }

    val plActions = remember(actions, id) {
        TrackActions(
            onPlayNext = actions.onPlayNext,
            onEnqueue = actions.onEnqueue,
            onToggleFav = actions.onToggleFav,
            onOpenAlbum = actions.onOpenAlbum,
            onOpenArtist = actions.onOpenArtist,
            onAddToPlaylist = actions.onAddToPlaylist,
            onPlaySimilar = actions.onPlaySimilar,
            onEdit = actions.onEdit,
            onRemoveFromPlaylist = { t -> vm.removeFromPlaylist(id, t.uri) },
            onInfo = actions.onInfo,
            onDislike = actions.onDislike,
            onDownload = actions.onDownload,
            onDelete = actions.onDelete,
            onMenu = actions.onMenu,
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topInset() + 12.dp, bottom = bottomPad)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    if (nav.canGoBack()) IconBtn(LIcons.Back, "Назад", nav.back)
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconBtn(LIcons.More, "Действия", { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Переименовать") },
                                leadingIcon = { Icon(LIcons.Edit, null, Modifier.size(20.dp)) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Экспорт в M3U") },
                                leadingIcon = { Icon(LIcons.Download, null, Modifier.size(20.dp)) },
                                onClick = { menu = false; m3uOut.launch((pl?.name ?: "playlist") + ".m3u") },
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить плейлист") },
                                leadingIcon = { Icon(LIcons.Trash, null, Modifier.size(20.dp)) },
                                onClick = { menu = false; vm.deletePlaylist(id); nav.back() },
                            )
                        }
                    }
                }
                PlaylistCollage(vm, id, 200.dp, 28.dp)
                Spacer(Modifier.height(18.dp))
                Text(
                    pl?.name.orEmpty(),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp).clickable { renaming = true },
                )
                Text(
                    if (tracks.isEmpty()) "Пока пусто — добавляй треки через меню «⋮» → «В плейлист…»"
                    else "${pluralRu(tracks.size, "трек", "трека", "треков")} · ${tracks.sumOf { it.durationMs } / 60000} мин · порядок — удержи и тяни",
                    fontSize = 13.sp,
                    color = LC.Dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                if (tracks.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Слушать", LIcons.Play, { vm.play(order.toList(), 0) }, Modifier.weight(1f))
                        PillButton("Перемешать", LIcons.Shuffle, { vm.shuffle(order.toList()) }, Modifier.weight(1f), primary = false)
                    }
                }
            }
        }
        itemsIndexed(order, key = { _, t -> t.uri }) { i, t ->
            val isDragged = dragging == i
            val lift by animateFloatAsState(if (isDragged) 1f else 0f, spring(dampingRatio = 0.7f), label = "lift")
            Box(
                Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .then(if (isDragged) Modifier else Modifier.animateItem())
                    .graphicsLayer {
                        translationY = if (isDragged) dragDy else 0f
                        scaleX = 1f + 0.03f * lift
                        scaleY = 1f + 0.03f * lift
                        shadowElevation = 18f * lift
                    }
                    .background(Color(0xFF1A1622).copy(alpha = lift), RoundedCornerShape(14.dp))
                    .pointerInput(t.uri) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = order.indexOfFirst { it.uri == t.uri }
                                dragDy = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                dragging = -1
                                dragDy = 0f
                                vm.reorderPlaylist(id, order.map { it.uri })
                            },
                            onDragCancel = {
                                dragging = -1
                                dragDy = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            dragDy += amount.y
                            val from = dragging
                            if (from < 0) return@detectDragGesturesAfterLongPress
                            val to = (from + (dragDy / rowPx).toInt()).coerceIn(0, order.lastIndex)
                            if (to != from) {
                                val item = order.removeAt(from)
                                order.add(to, item)
                                dragDy -= (to - from) * rowPx
                                dragging = to
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
            ) {
                TrackRow(t, t.uri == p.currentId, t.uri in favs, plActions, onClick = { vm.play(order.toList(), i) })
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Название плейлиста",
            initial = pl?.name.orEmpty(),
            confirm = "Сохранить",
            onDone = { name -> vm.renamePlaylist(id, name); renaming = false },
            onDismiss = { renaming = false },
        )
    }
}

/** Карточка ввода названия (новый плейлист / переименование). */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(app.lumen.player.ui.theme.LT.sheetBrush())
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(22.dp),
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                textStyle = TextStyle(color = LC.Text, fontSize = 17.sp),
                cursorBrush = SolidColor(LocalAccent.current),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onDone(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x1AFFFFFF))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Отмена", null, onDismiss, Modifier.weight(1f), primary = false)
                PillButton(confirm, LIcons.Check, { if (text.isNotBlank()) onDone(text) }, Modifier.weight(1f))
            }
        }
    }
}

/** Выбор плейлиста для трека: отметки там, где он уже есть; тап — добавить/убрать. */
@Composable
fun AddToPlaylistSheet(vm: MainViewModel, t: TrackEntity, onClose: () -> Unit, sheetModifier: Modifier = Modifier) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    var inLists by remember(t.uri) { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(t.uri, lists) { inLists = vm.playlistsWith(t.uri) }
    var creating by remember { mutableStateOf(false) }
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
                .padding(10.dp)
                .navigationBarsPadding()
                .clip(RoundedCornerShape(30.dp))
                .background(app.lumen.player.ui.theme.LT.sheetBrush())
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(30.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(vertical = 18.dp),
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(t.uri, Modifier.size(48.dp), corner = 12.dp, sizePx = 128)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("В плейлист", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${t.title} · ${t.artist}", fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).border(1.5.dp, LocalAccent.current.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(LIcons.Plus, null, Modifier.size(22.dp), tint = LocalAccent.current) }
                        Spacer(Modifier.width(12.dp))
                        Text("Новый плейлист", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current)
                    }
                }
                items(lists, key = { it.id }) { pl ->
                    val has = pl.id in inLists
                    val check by animateFloatAsState(if (has) 1f else 0f, tween(220), label = "chk")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (has) vm.removeFromPlaylist(pl.id, t.uri) else vm.addToPlaylist(pl.id, listOf(t.uri))
                                inLists = if (has) inLists - pl.id else inLists + pl.id
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCollage(vm, pl.id, 48.dp, 14.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pluralRu(pl.trackCount, "трек", "трека", "треков"), fontSize = 12.sp, color = LC.Dim)
                        }
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(LocalAccent.current.copy(alpha = check))
                                .border(1.5.dp, LocalAccent.current.copy(alpha = 0.4f + 0.6f * check), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(LIcons.Check, null, Modifier.size(16.dp).graphicsLayer { alpha = check; scaleX = 0.6f + 0.4f * check; scaleY = 0.6f + 0.4f * check }, tint = Color(0xFF0B0B12))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PillButton("Готово", null, onClose, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
    }
    if (creating) {
        NameDialog(
            title = "Новый плейлист",
            initial = "",
            confirm = "Создать",
            onDone = { name ->
                vm.createPlaylist(name, listOf(t.uri)) { newId -> inLists = inLists + newId }
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

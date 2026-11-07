package app.lumen.player.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.TrackEntity
import app.lumen.player.playback.PlayerState
import app.lumen.player.ui.Dest
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.Nav
import app.lumen.player.ui.components.AlbumTile
import app.lumen.player.ui.components.Badge
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LChip
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.SectionHeader
import app.lumen.player.ui.components.TrackActions
import app.lumen.player.ui.components.TrackRow
import app.lumen.player.ui.components.formatMs
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.components.rememberArtwork
import app.lumen.player.ui.formatLabel
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import java.util.Calendar
import kotlinx.coroutines.launch

@Composable
internal fun topInset(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

// ======================= ГЛАВНАЯ =======================

@Composable
fun HomeScreen(vm: MainViewModel, nav: Nav, actions: TrackActions, bottomPad: Dp, onOpenPlayer: () -> Unit) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val recent by vm.recentAlbums.collectAsStateWithLifecycle()
    val frequent by vm.frequent.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val colors by vm.coverColors.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val forgotten by androidx.compose.runtime.produceState(emptyList<TrackEntity>(), tracks.size) {
        value = runCatching { vm.forgottenTracks() }.getOrDefault(emptyList())
    }

    // Обратная сторона луны больше не используется на главной — не даём акценту залипнуть.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.setMoonSide(false) }

    androidx.activity.compose.BackHandler(enabled = searchOpen) {
        searchOpen = false
        vm.query.value = ""
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val floors = remember(listState) { HomeFloors(listState, scope) }
    // Сообщаем, виден ли первый экран: от этого зависит размер мини-плеера.
    androidx.compose.runtime.LaunchedEffect(listState, searchOpen, tracks.isEmpty()) {
        if (searchOpen || tracks.isEmpty()) {
            vm.homeAtTop.value = searchOpen.not() && tracks.isEmpty()
            return@LaunchedEffect
        }
        androidx.compose.runtime.snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < floors.heroPx * 0.35f
        }.collect { vm.homeAtTop.value = it }
    }
    // Страховка: если прокрутка остановилась между этажами — доводим до ближайшего.
    androidx.compose.runtime.LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 0) {
                floors.goTo(if (listState.firstVisibleItemScrollOffset > floors.heroPx / 2) 1 else 0)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = topInset() + 12.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), fontSize = 13.sp, color = LC.Dim)
                LumenBrand(vm, colors)
            }
            IconBtn(if (searchOpen) LIcons.Close else LIcons.Search, if (searchOpen) "Закрыть поиск" else "Поиск", {
                searchOpen = !searchOpen
                if (!searchOpen) vm.query.value = ""
            })
            Spacer(Modifier.width(8.dp))
            IconBtn(LIcons.Sliders, "Настройки", { nav.tab(Dest.Settings, null) })
        }

        androidx.compose.animation.AnimatedContent(
            targetState = when {
                searchOpen -> 1
                tracks.isEmpty() -> 2
                else -> 0
            },
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(320)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220))
            },
            label = "homeMode",
            modifier = Modifier.weight(1f),
        ) { mode ->
            when (mode) {
                1 -> Column(Modifier.fillMaxSize()) {
                    SearchField(
                        query,
                        { vm.query.value = it },
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    )
                    SearchResults(vm, query, results, p.currentId, favs, actions, bottomPad, Modifier.weight(1f))
                }
                2 -> Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    EmptyLibrary(scanning, onRescan = { vm.rescan() }, onSettings = { nav.tab(Dest.Settings, null) })
                }
                else -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val heroH = maxHeight
                val heroPx = with(androidx.compose.ui.platform.LocalDensity.current) { heroH.toPx() }
                floors.heroPx = heroPx
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(floors.connection),
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottomPad + 8.dp),
                ) {
                    // Первый экран: только большая кнопка (и мини-плеер снизу).
                    item(key = "hero") {
                        // Ровно в высоту экрана: нижний этаж целиком за краем, ничего не выглядывает.
                        Column(
                            Modifier
                                .height(heroH)
                                .fillMaxWidth()
                                .padding(top = 34.dp, bottom = bottomPad - 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ListenButton(
                                vm = vm,
                                colors = colors,
                                playing = p.isPlaying,
                                hasQueue = p.queue.isNotEmpty(),
                                onClick = {
                                    if (p.queue.isNotEmpty()) vm.toggle() else vm.playLoved(true)
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            Text(
                                "${pluralRu(tracks.size, "трек", "трека", "треков")}  ·  ${pluralRu(albums.size, "альбом", "альбома", "альбомов")}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = LC.Text.copy(alpha = 0.72f),
                                letterSpacing = 0.4.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            ScrollHint(
                                visible = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24,
                                onClick = { floors.goTo(1) },
                            )
                        }
                    }
                    item(key = "quick") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PillButton("Перемешать всё", LIcons.Shuffle, { vm.shuffle(tracks) }, Modifier.weight(1f))
                            PillButton("Забытое", LIcons.Moon, { vm.playLoved(false) }, Modifier.weight(1f), primary = false)
                        }
                    }
                    item(key = "wrapped") { WrappedTeaser { nav.open(Dest.Wrapped) } }
                    if (recent.isNotEmpty()) {
                        item(key = "recentH") { SectionHeader("Недавно добавленные", "Все") { nav.tab(Dest.Library, 1) } }
                        item(key = "recent") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(recent, key = { "r:" + it.albumKey }) { a ->
                                    AlbumTile(a, { nav.open(Dest.Album(a.albumKey)) }, Modifier.width(148.dp), coverSize = 148.dp)
                                }
                            }
                        }
                    }
                    if (frequent.isNotEmpty()) {
                        item(key = "freqH") { SectionHeader("Часто слушаю") }
                        itemsIndexed(frequent.take(6), key = { _, t -> "f:" + t.uri }) { i, t ->
                            TrackRow(t, t.uri == p.currentId, t.uri in favs, actions, onClick = { vm.play(frequent, i) })
                        }
                    }
                    if (artists.isNotEmpty()) {
                        item(key = "artH") { SectionHeader("Исполнители", "Все") { nav.tab(Dest.Library, 2) } }
                        item(key = "art") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(artists.sortedByDescending { it.trackCount }.take(14), key = { "a:" + it.name }) { ar ->
                                    Column(
                                        Modifier
                                            .width(92.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable { nav.open(Dest.Artist(ar.name)) }
                                            .padding(vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Cover(ar.coverUri, Modifier.size(84.dp), corner = 999.dp, sizePx = 192, seed = ar.name)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            ar.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (forgotten.isNotEmpty()) {
                        item(key = "forgH") { SectionHeader("Давно не слушал") }
                        itemsIndexed(forgotten, key = { _, t -> "g:" + t.uri }) { i, t ->
                            TrackRow(t, t.uri == p.currentId, t.uri in favs, actions, onClick = { vm.play(forgotten, i) })
                        }
                    }
                }
                }
            }
        }
    }
}

/** Мягкая подсказка «листай вниз»: стрелка плавно покачивается и гаснет при прокрутке. */
@Composable
private fun ScrollHint(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (visible) 1f else 0f,
        androidx.compose.animation.core.tween(500),
        label = "hintA",
    )
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "hint")
    val bob by t.animateFloat(
        0f,
        1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "bob",
    )
    Column(
        modifier
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = visible, onClickLabel = "Листать вниз", onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationY = bob * 6.dp.toPx()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("листай вниз", fontSize = 12.sp, color = LC.Dim, letterSpacing = 1.sp)
        Icon(LIcons.Down, null, Modifier.size(20.dp), tint = LC.Dim)
    }
}

@Suppress("unused")
@Composable
private fun ContinueCard(vm: MainViewModel, p: PlayerState, last: TrackEntity?, onOpen: () -> Unit) {
    val accent = LocalAccent.current
    val hasCurrent = p.currentId != null
    val id = p.currentId ?: last?.uri ?: return
    val title = if (hasCurrent) p.title else last?.title.orEmpty()
    val artist = if (hasCurrent) p.artist else last?.artist.orEmpty()
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .glass(shape)
            .clickable { if (hasCurrent) onOpen() else vm.resume() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(id, Modifier.size(68.dp), corner = 16.dp, sizePx = 192)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("ПРОДОЛЖИТЬ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent, letterSpacing = 1.sp)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(accent)
                .clickable { if (hasCurrent) vm.toggle() else vm.resume() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (p.isPlaying) LIcons.Pause else LIcons.Play, null, Modifier.size(20.dp), tint = LC.OnAccent)
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .glass(RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LIcons.Search, null, Modifier.size(18.dp), tint = LC.Dim)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Треки, альбомы, исполнители", color = LC.Faint, fontSize = 15.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = LC.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconBtn(LIcons.Close, "Очистить", { onChange("") }, glass = false, size = 40.dp, iconSize = 16.dp, tint = LC.Dim)
        }
    }
}

@Composable
private fun EmptyLibrary(scanning: Boolean, onRescan: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier
            .padding(20.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (scanning) {
            CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(16.dp))
            Text("Сканирую библиотеку…", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Icon(LIcons.Note, null, Modifier.size(40.dp), tint = LocalAccent.current)
            Spacer(Modifier.height(12.dp))
            Text("Музыка не найдена", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Положи треки в папку Music или добавь свою папку в настройках.",
                color = LC.Dim,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Обновить", LIcons.Refresh, onRescan)
                PillButton("Добавить папку", LIcons.Folder, onSettings, primary = false)
            }
        }
    }
}

// ======================= БИБЛИОТЕКА =======================

@Composable
private fun LibraryHeader(tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) { LibraryTitle(tab) }
}

/** Заголовок раздела: «Библиотека» и текущая категория (сама категория выбирается снизу). */
@Composable
private fun LibraryTitle(tab: Int) {
    Text("Библиотека", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
    androidx.compose.animation.AnimatedContent(
        targetState = tab,
        transitionSpec = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
        },
        label = "libTitle",
    ) { t ->
        Text(LIB_CATS.firstOrNull { it.first == t }?.second ?: "Папки", fontSize = 15.sp, color = LC.Dim)
    }
    Spacer(Modifier.height(10.dp))
}

/** Категории библиотеки в нижней панели: номер вкладки → название. */
private val LIB_CATS = listOf(4 to "Плейлисты", 0 to "Треки", 1 to "Альбомы", 2 to "Артисты")

@Composable
fun LibraryScreen(
    vm: MainViewModel,
    nav: Nav,
    actions: TrackActions,
    tab: Int,
    onTab: (Int) -> Unit,
    bottomPad: Dp,
) {
    Box(Modifier.fillMaxSize()) {
        // Смена раздела: новый въезжает со стороны, куда двигались по панели, старый мягко уходит.
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val from = LIB_CATS.indexOfFirst { it.first == initialState }
                val to = LIB_CATS.indexOfFirst { it.first == targetState }
                val dir = if (to >= from) 1 else -1
                val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                (androidx.compose.animation.slideInHorizontally(spec) { w -> dir * w / 6 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(380, delayMillis = 60)) +
                    androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(420), initialScale = 0.97f)) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(spec) { w -> -dir * w / 6 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(420), targetScale = 0.97f))
            },
            label = "libTab",
            modifier = Modifier.fillMaxSize(),
        ) { t ->
            LibraryContent(vm, nav, actions, t, onTab, bottomPad + 78.dp)
        }
        CategoryDock(
            tab = tab,
            onTab = onTab,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad - 6.dp),
        )
    }
}

@Composable
private fun LibraryContent(
    vm: MainViewModel,
    nav: Nav,
    actions: TrackActions,
    tab: Int,
    onTab: (Int) -> Unit,
    bottomPad: Dp,
) {
    val top = topInset() + 16.dp
    when (tab) {
        1 -> {
            val albums by vm.albums.collectAsStateWithLifecycle()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = bottomPad),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(horizontal = 0.dp)) { LibraryHeaderNoPad(tab, onTab) }
                }
                items(albums, key = { it.albumKey }) { a ->
                    AlbumTile(a, { nav.open(Dest.Album(a.albumKey)) })
                }
            }
        }
        else -> {
            val tracks by vm.tracks.collectAsStateWithLifecycle()
            val artists by vm.artists.collectAsStateWithLifecycle()
            val folders by vm.folders.collectAsStateWithLifecycle()
            val favs by vm.favoriteUris.collectAsStateWithLifecycle()
            val p by vm.player.collectAsStateWithLifecycle()
            val playlists by vm.playlists.collectAsStateWithLifecycle()
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = top, bottom = bottomPad),
            ) {
                item { LibraryHeader(tab, onTab) }
                when (tab) {
                    0 -> {
                        item {
                            Row(
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PillButton("Слушать", LIcons.Play, { vm.play(tracks, 0) }, Modifier.weight(1f))
                                PillButton("Перемешать", LIcons.Shuffle, { vm.shuffle(tracks) }, Modifier.weight(1f), primary = false)
                            }
                        }
                        itemsIndexed(tracks, key = { _, t -> t.uri }) { i, t ->
                            TrackRow(t, t.uri == p.currentId, t.uri in favs, actions, onClick = { vm.play(tracks, i) })
                        }
                    }
                    4 -> playlistsTab(vm, nav, playlists) { vm.newPlaylistPrompt.value = true }
                    2 -> items(artists, key = { it.name }) { a ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { nav.open(Dest.Artist(a.name)) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(a.coverUri, Modifier.size(56.dp), corner = 999.dp, sizePx = 160, seed = a.name)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${pluralRu(a.albumCount, "альбом", "альбома", "альбомов")} · ${pluralRu(a.trackCount, "трек", "трека", "треков")}",
                                    fontSize = 13.sp,
                                    color = LC.Dim,
                                )
                            }
                        }
                    }
                    else -> items(folders, key = { it.folder }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { nav.open(Dest.Folder(f.folder, f.folderName)) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(52.dp).glass(RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(LIcons.Folder, null, Modifier.size(24.dp), tint = LocalAccent.current)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.folderName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pluralRu(f.trackCount, "трек", "трека", "треков"), fontSize = 13.sp, color = LC.Dim)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeaderNoPad(tab: Int, onTab: (Int) -> Unit) {
    Column { LibraryTitle(tab) }
}

// ======================= КОЛЛЕКЦИИ =======================

@Composable
fun AlbumScreen(vm: MainViewModel, key: String, nav: Nav, actions: TrackActions, bottomPad: Dp) {
    val tracks by remember(key) { vm.albumTracks(key) }.collectAsState(initial = emptyList())
    val album by remember(key) { vm.album(key) }.collectAsState(initial = null)
    val a = album
    val totalMs = tracks.sumOf { it.durationMs }
    val subtitle = buildList {
        if ((a?.year ?: 0) > 0) add(a!!.year.toString())
        add(pluralRu(tracks.size, "трек", "трека", "треков"))
        add("${totalMs / 60000} мин")
    }.joinToString(" · ")
    CollectionScreen(
        vm = vm,
        nav = nav,
        actions = actions,
        bottomPad = bottomPad,
        title = a?.album ?: tracks.firstOrNull()?.album.orEmpty(),
        link = a?.albumArtist,
        onLink = { a?.let { nav.open(Dest.Artist(it.albumArtist)) } },
        subtitle = subtitle,
        badge = tracks.firstOrNull()?.let { formatLabel(it.mime, it.uri) },
        coverUri = a?.coverUri ?: tracks.firstOrNull()?.uri,
        seed = key,
        round = false,
        tracks = tracks,
        numbered = true,
        discs = true,
    )
}

@Composable
fun ArtistScreen(vm: MainViewModel, name: String, nav: Nav, actions: TrackActions, bottomPad: Dp) {
    val tracks by remember(name) { vm.artistTracks(name) }.collectAsState(initial = emptyList())
    val albums by remember(name) { vm.albumsOfArtist(name) }.collectAsState(initial = emptyList())
    CollectionScreen(
        vm = vm,
        nav = nav,
        actions = actions,
        bottomPad = bottomPad,
        title = name,
        link = null,
        onLink = null,
        subtitle = "${pluralRu(albums.size, "альбом", "альбома", "альбомов")} · ${pluralRu(tracks.size, "трек", "трека", "треков")}",
        badge = null,
        coverUri = tracks.firstOrNull()?.uri,
        seed = name,
        round = true,
        tracks = tracks,
        numbered = false,
        discs = false,
        extra = {
            if (albums.size > 1) {
                item { SectionHeader("Альбомы") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(albums, key = { it.albumKey }) { a ->
                            AlbumTile(a, { nav.open(Dest.Album(a.albumKey)) }, Modifier.width(132.dp), coverSize = 132.dp)
                        }
                    }
                }
                item { SectionHeader("Все треки") }
            }
        },
    )
}

@Composable
fun FolderScreen(vm: MainViewModel, path: String, name: String, nav: Nav, actions: TrackActions, bottomPad: Dp) {
    val tracks by remember(path) { vm.folderTracks(path) }.collectAsState(initial = emptyList())
    CollectionScreen(
        vm = vm,
        nav = nav,
        actions = actions,
        bottomPad = bottomPad,
        title = name,
        link = null,
        onLink = null,
        subtitle = pluralRu(tracks.size, "трек", "трека", "треков"),
        badge = null,
        coverUri = tracks.firstOrNull()?.uri,
        seed = path,
        round = false,
        tracks = tracks,
        numbered = false,
        discs = false,
    )
}

@Composable
fun FavoritesScreen(vm: MainViewModel, nav: Nav, actions: TrackActions, bottomPad: Dp) {
    val tracks by vm.favorites.collectAsStateWithLifecycle()
    CollectionScreen(
        vm = vm,
        nav = nav,
        actions = actions,
        bottomPad = bottomPad,
        title = "Любимое",
        link = null,
        onLink = null,
        subtitle = if (tracks.isEmpty()) "Отмечай треки сердечком" else pluralRu(tracks.size, "трек", "трека", "треков"),
        badge = null,
        coverUri = tracks.firstOrNull()?.uri,
        seed = "favorites",
        round = false,
        tracks = tracks,
        numbered = false,
        discs = false,
    )
}

@Composable
private fun CollectionScreen(
    vm: MainViewModel,
    nav: Nav,
    actions: TrackActions,
    bottomPad: Dp,
    title: String,
    link: String?,
    onLink: (() -> Unit)?,
    subtitle: String,
    badge: String?,
    coverUri: String?,
    seed: String,
    round: Boolean,
    tracks: List<TrackEntity>,
    numbered: Boolean,
    discs: Boolean,
    extra: (LazyListScope.() -> Unit)? = null,
) {
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    val backdrop = rememberArtwork(coverUri, 64)
    val multiDisc = discs && tracks.map { it.discNo }.distinct().size > 1

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                if (backdrop != null) {
                    Image(
                        bitmap = backdrop,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(440.dp).blur(50.dp),
                        contentScale = ContentScale.Crop,
                        alpha = 0.7f,
                        filterQuality = FilterQuality.High,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .background(Brush.verticalGradient(listOf(Color(0x0007070D), Color(0x6607070D), LC.Bg)))
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = topInset() + 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        if (nav.canGoBack()) IconBtn(LIcons.Back, "Назад", nav.back)
                    }
                    Cover(
                        coverUri,
                        Modifier.size(208.dp),
                        corner = if (round) 999.dp else 26.dp,
                        sizePx = 640,
                        seed = seed,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        title,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    if (link != null) {
                        Text(
                            link,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalAccent.current,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = onLink != null) { onLink?.invoke() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(subtitle, fontSize = 13.sp, color = LC.Dim)
                        if (badge != null) {
                            Spacer(Modifier.width(8.dp))
                            Badge(badge)
                        }
                    }
                    if (tracks.isNotEmpty()) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PillButton("Слушать", LIcons.Play, { vm.play(tracks, 0) }, Modifier.weight(1f))
                            PillButton("Перемешать", LIcons.Shuffle, { vm.shuffle(tracks) }, Modifier.weight(1f), primary = false)
                        }
                    }
                }
            }
        }
        extra?.invoke(this)
        tracks.forEachIndexed { i, t ->
            if (multiDisc && (i == 0 || tracks[i - 1].discNo != t.discNo)) {
                item(key = "disc:${t.discNo}:$i") {
                    Text(
                        "ДИСК ${t.discNo}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = LC.Faint,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = t.uri) {
                TrackRow(
                    t,
                    t.uri == p.currentId,
                    t.uri in favs,
                    actions,
                    onClick = { vm.play(tracks, i) },
                    number = if (numbered) t.trackNo else null,
                )
            }
        }
    }
}

@Suppress("unused")
private fun durationLabel(ms: Long) = formatMs(ms)


/**
 * Главная из двух «этажей»: верхний — кнопка, нижний — всё остальное.
 * Пока палец на экране, верхний этаж не двигается — запоминаем только направление.
 * Как только палец отпущен, плавно довозим до нужного этажа целиком.
 * Снизу, упёршись в начало нижнего этажа и потянув вниз, так же возвращаемся к кнопке.
 */
private class HomeFloors(
    private val list: androidx.compose.foundation.lazy.LazyListState,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var heroPx = 1f
    private var animating = false
    private var pending: Int? = null

    /** Для кнопки «листай вниз» и страховки. */
    fun goTo(floor: Int) {
        if (animating) return
        scope.launch { glide(floor) }
    }

    private suspend fun glide(floor: Int) {
        val idx = list.firstVisibleItemIndex
        if (idx > 1) return
        val pos = if (idx == 0) list.firstVisibleItemScrollOffset.toFloat() else heroPx + list.firstVisibleItemScrollOffset
        val dist = (if (floor == 0) 0f else heroPx) - pos
        if (kotlin.math.abs(dist) < 1f) return
        animating = true
        try {
            // Приоритет как у пальца — чтобы никакая инерция не перебила доводку.
            list.scroll(androidx.compose.foundation.MutatePriority.UserInput) {
                var done = 0f
                androidx.compose.animation.core.animate(
                    0f,
                    dist,
                    animationSpec = androidx.compose.animation.core.tween(
                        700,
                        easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                    ),
                ) { v, _ ->
                    done += scrollBy(v - done)
                }
            }
        } finally {
            animating = false
        }
    }

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            if (animating) return available
            val idx = list.firstVisibleItemIndex
            val off = list.firstVisibleItemScrollOffset
            val user = source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput
            if (idx == 0) {
                if (user) {
                    if (available.y < -1f) pending = 1
                    else if (available.y > 1f) pending = if (off > 0) 0 else null
                }
                return available
            }
            if (idx == 1 && available.y > 0f) {
                if (off == 0) {
                    if (user && available.y > 1f) pending = 0
                    return available
                }
                // Прокрутка вверх упирается в начало нижнего этажа и не проскакивает к кнопке.
                if (available.y > off) return androidx.compose.ui.geometry.Offset(0f, available.y - off)
            }
            if (user && available.y < 0f && pending == 0) pending = null
            return androidx.compose.ui.geometry.Offset.Zero
        }

        override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
            val target = pending
            pending = null
            if (target != null) {
                glide(target)
                return available
            }
            if (animating) return available
            if (list.firstVisibleItemIndex == 0) {
                if (list.firstVisibleItemScrollOffset > 0) glide(if (list.firstVisibleItemScrollOffset > heroPx / 2) 1 else 0)
                return available
            }
            return androidx.compose.ui.unit.Velocity.Zero
        }
    }
}

/**
 * Поиск: сначала треки из библиотеки, затем — найденное в сети (Deezer).
 * Тап по треку из сети — поиск его в интернете. Без запроса показывается история:
 * треки, которые были включены из поиска.
 */
@Composable
private fun SearchResults(
    vm: MainViewModel,
    query: String,
    results: List<TrackEntity>,
    currentId: String?,
    favs: Set<String>,
    actions: TrackActions,
    bottomPad: Dp,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val history by vm.searchHistory.collectAsStateWithLifecycle()
    val online by vm.onlineResults.collectAsStateWithLifecycle()
    val onlineLoading by vm.onlineLoading.collectAsStateWithLifecycle()
    val onlineError by vm.onlineError.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val q = query.trim()

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPad)) {
        if (q.isEmpty()) {
            if (history.isEmpty()) {
                item(key = "hEmpty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 70.dp, start = 32.dp, end = 32.dp).animateItem(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(LIcons.Search, null, Modifier.size(40.dp), tint = LC.Dim.copy(alpha = 0.5f))
                        Spacer(Modifier.height(14.dp))
                        Text("Здесь будет история поиска", color = LC.Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    }
                }
            } else {
                item(key = "hHead") { SectionHeader("История поиска", "Очистить") { vm.clearSearchHistory() } }
                itemsIndexed(history, key = { _, t -> "h:" + t.uri }) { i, t ->
                    Box(Modifier.animateItem()) {
                        TrackRow(t, t.uri == currentId, t.uri in favs, actions, onClick = { vm.playFromSearch(history, i) })
                    }
                }
            }
            return@LazyColumn
        }

        if (results.isNotEmpty()) {
            item(key = "lHead") { SectionHeader("В библиотеке") }
            itemsIndexed(results, key = { _, t -> "s:" + t.uri }) { i, t ->
                TrackRow(t, t.uri == currentId, t.uri in favs, actions, onClick = { vm.playFromSearch(results, i) })
            }
        }

        item(key = "oHead") {
            Row(Modifier.fillMaxWidth().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("В сети") }
                if (onlineLoading) CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
            }
        }
        val list = online
        when {
            list == null && !onlineLoading -> {}
            onlineError && list.isNullOrEmpty() -> item(key = "oErr") {
                Text(
                    "Нет связи — показаны только треки из библиотеки",
                    color = LC.Dim,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            list != null && list.isEmpty() && !onlineLoading -> item(key = "oNone") {
                Text(
                    "В сети ничего нового не нашлось",
                    color = LC.Dim,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            list != null -> items(list, key = { "o:" + it.key }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .clickable(onClickLabel = "Найти в интернете") { searchWeb(ctx, r.query) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteCover(r.cover, r.key, RoundedCornerShape(12.dp), Modifier.size(52.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (r.reason.isNotBlank()) "${r.artist} · ${r.reason}" else r.artist,
                            fontSize = 13.sp,
                            color = LC.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(LIcons.Search, "Найти в интернете", Modifier.size(20.dp), tint = LC.Dim)
                }
            }
        }
        item(key = "web") {
            Row(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(18.dp))
                    .clickable { searchWeb(ctx, q) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Search, null, Modifier.size(20.dp), tint = accent)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Искать «$q» в интернете",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * «lumen by CheKavo». CheKavo — переливающийся градиент из цветов обложки
 * (статичный) с мягким неоновым ореолом; на ударах слегка вздрагивает и вспыхивает.
 */
@Composable
private fun LumenBrand(vm: MainViewModel, colors: app.lumen.player.ui.theme.CoverColors) {
    val beat = app.lumen.player.ui.components.rememberBeatSignal(vm, app.lumen.player.ui.components.LocalHomeActive.current)
    val a by androidx.compose.animation.animateColorAsState(colors.a, androidx.compose.animation.core.tween(900), label = "ba")
    val c by androidx.compose.animation.animateColorAsState(colors.c, androidx.compose.animation.core.tween(900), label = "bc")
    val acc by androidx.compose.animation.animateColorAsState(colors.accent, androidx.compose.animation.core.tween(900), label = "bacc")
    Row(verticalAlignment = Alignment.Bottom) {
        Text("lumen", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Text(
            " by ",
            fontSize = 14.sp,
            color = LC.Dim,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val lv = beat.level().coerceIn(0f, 1f)
                    val sc = 1f + 0.07f * lv
                    scaleX = sc
                    scaleY = sc
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.6f)
                }
                .drawBehind {
                    val lv = beat.level().coerceIn(0f, 1f)
                    val r = size.width * 0.7f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(acc.copy(alpha = 0.16f + 0.3f * lv), Color.Transparent),
                            center,
                            r,
                        ),
                        radius = r,
                        center = center,
                    )
                }
        ) {
            // Статичный градиент по слову.
            val w = 320f
            val x0 = 0f
            Text(
                "CheKavo",
                fontSize = 27.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.3).sp,
                style = TextStyle(
                    brush = Brush.linearGradient(
                        listOf(acc, a, Color.White.copy(alpha = 0.95f), c),
                        start = androidx.compose.ui.geometry.Offset(x0, 0f),
                        end = androidx.compose.ui.geometry.Offset(x0 + w, 60f),
                        tileMode = androidx.compose.ui.graphics.TileMode.Clamp,
                    ),
                    shadow = androidx.compose.ui.graphics.Shadow(acc.copy(alpha = 0.55f), blurRadius = 18f),
                ),
            )
        }
    }
}


/** Карточка-приглашение в «Твой месяц в музыке» на главной. */
@Composable
private fun WrappedTeaser(onClick: () -> Unit) {
    val month = remember { java.text.SimpleDateFormat("LLLL", java.util.Locale("ru")).format(java.util.Date()) }
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "teaser")
    val shift by t.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(6000, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "sh",
    )
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        listOf(Color(0xFF7B2FF7), Color(0xFFFF5F6D), Color(0xFFFFB347)),
                        start = androidx.compose.ui.geometry.Offset(-size.width * shift, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width * (2f - shift), size.height),
                    )
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Твой $month в музыке", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("Минуты, любимые песни и открытия — в историях", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
        }
        Icon(LIcons.Sparkle, null, Modifier.size(28.dp), tint = Color.White)
    }
}


/**
 * Стеклянная панель категорий снизу. Выезжает при входе в библиотеку.
 * Можно тапнуть, а можно зажать и вести пальцем — подсветка едет следом, выбор — при отпускании.
 */
@Composable
private fun CategoryDock(tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 260f)) }
    val selIndex = LIB_CATS.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    var hover by remember { mutableIntStateOf(-1) }
    val shownIndex = if (hover >= 0) hover else selIndex
    val pos by androidx.compose.animation.core.animateFloatAsState(
        shownIndex.toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 420f),
        label = "dockPos",
    )
    val pressed by androidx.compose.animation.core.animateFloatAsState(if (hover >= 0) 1f else 0f, androidx.compose.animation.core.tween(160), label = "dockPress")
    val onTabNow by androidx.compose.runtime.rememberUpdatedState(onTab)
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .height(60.dp)
            .graphicsLayer {
                translationY = (1f - enter.value) * 140.dp.toPx()
                alpha = enter.value
                val s = 1f + 0.02f * pressed
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xCC2A2335), Color(0xE6161219))))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f), accent.copy(alpha = 0.3f))), shape)
            .pointerInput(Unit) {
                val n = LIB_CATS.size
                fun idx(x: Float) = (x / (size.width / n.toFloat())).toInt().coerceIn(0, n - 1)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    hover = idx(down.position.x)
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        val i = idx(ch.position.x)
                        if (i != hover) {
                            hover = i
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    }
                    val pick = hover
                    hover = -1
                    if (pick >= 0) onTabNow(LIB_CATS[pick].first)
                }
            }
            .padding(5.dp),
    ) {
        // Подсветка выбранной категории — едет за пальцем.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth / LIB_CATS.size
            Box(
                Modifier
                    .graphicsLayer { translationX = w.toPx() * pos }
                    .width(w)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(21.dp))
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.22f))))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(21.dp))
            )
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            LIB_CATS.forEachIndexed { i, (_, name) ->
                val on = i == shownIndex
                val c by androidx.compose.animation.animateColorAsState(if (on) Color.White else LC.Dim, androidx.compose.animation.core.tween(200), label = "dockC")
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(name, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = c, maxLines = 1)
                }
            }
        }
    }
}

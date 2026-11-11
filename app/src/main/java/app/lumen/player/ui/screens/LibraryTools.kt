package app.lumen.player.ui.screens

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.SmartCond
import app.lumen.player.data.SmartField
import app.lumen.player.data.SmartOp
import app.lumen.player.data.SmartRules
import app.lumen.player.data.SmartSort
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.Dest
import app.lumen.player.ui.DupGroup
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.Nav
import app.lumen.player.ui.SmartRow
import app.lumen.player.ui.components.Badge
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LChip
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.TrackActions
import app.lumen.player.ui.components.TrackRow
import app.lumen.player.ui.components.formatMs
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.formatLabel
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

// ================= общее =================

@Composable
private fun ScreenTop(nav: Nav, menu: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav.canGoBack()) IconBtn(LIcons.Back, "Назад", nav.back)
        Spacer(Modifier.weight(1f))
        menu?.invoke()
    }
}

@Composable
private fun BigTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 13.sp, color = LC.Dim, textAlign = TextAlign.Center)
    }
}

/** Обложка умного плейлиста: коллаж и «искра» в углу. */
@Composable
fun SmartCover(covers: List<String>, size: Dp, corner: Dp) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.7f), Color(0xFF15101F)))),
        contentAlignment = Alignment.Center,
    ) {
        when {
            covers.size >= 4 -> Column {
                Row {
                    Cover(covers[0], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                    Cover(covers[1], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                }
                Row {
                    Cover(covers[2], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                    Cover(covers[3], Modifier.size(size / 2), corner = 0.dp, sizePx = 256)
                }
            }
            covers.isNotEmpty() -> Cover(covers[0], Modifier.size(size), corner = 0.dp, sizePx = 512)
        }
        // Мягкая подложка под значок, чтобы он читался на любой обложке.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(size * 0.06f)
                .size(size * 0.32f)
                .clip(CircleShape)
                .background(Color(0xCC0B0812))
                .border(1.dp, accent.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(LIcons.Sparkle, null, Modifier.size(size * 0.18f), tint = accent) }
    }
}

// ================= «Не нравится» =================

@Composable
fun DislikedScreen(vm: MainViewModel, nav: Nav, actions: TrackActions, bottomPad: Dp) {
    val tracks by vm.dislikedTracks.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topInset() + 12.dp, bottom = bottomPad)) {
        item { ScreenTop(nav) }
        item {
            Spacer(Modifier.height(10.dp))
            BigTitle(
                "Не нравится",
                if (tracks.isEmpty()) "Пусто. Отметь трек через меню «⋮» → «Не нравится», и он перестанет попадаться в перемешивании, умной очереди, «Похожем» и подборках."
                else "${pluralRu(tracks.size, "трек", "трека", "треков")} · из альбомов и плейлистов не пропадают, но в подборки не попадут. Вернуть — через меню трека.",
            )
            Spacer(Modifier.height(14.dp))
        }
        items(tracks, key = { it.uri }) { t ->
            Box(Modifier.animateItem()) {
                TrackRow(t, t.uri == p.currentId, t.uri in favs, actions, onClick = { vm.play(tracks, tracks.indexOf(t)) })
            }
        }
    }
}

// ================= умные плейлисты =================

/** Строки умных плейлистов во вкладке «Плейлисты». */
fun LazyListScope.smartPlaylistRows(nav: Nav, rows: List<SmartRow>) {
    item(key = "newSmart") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { nav.open(Dest.SmartEditor(0)) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, LocalAccent.current.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(LIcons.Sparkle, null, Modifier.size(26.dp), tint = LocalAccent.current) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Умный плейлист", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current)
                Text("Собирается сам по правилам", fontSize = 12.sp, color = LC.Dim)
            }
        }
    }
    items(rows, key = { "smart:${it.id}" }) { r ->
        Row(
            Modifier
                .fillMaxWidth()
                .animateItem()
                .clickable { nav.open(Dest.SmartPlaylist(r.id)) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartCover(r.covers, 60.dp, 16.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${pluralRu(r.count, "трек", "трека", "треков")} · ${r.rules.summary()}",
                    fontSize = 13.sp,
                    color = LC.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SmartPlaylistScreen(vm: MainViewModel, id: Long, nav: Nav, actions: TrackActions, bottomPad: Dp) {
    val pl by remember(id) { vm.smartPlaylist(id) }.collectAsState(initial = null)
    val tracks by remember(id) { vm.smartTracks(id) }.collectAsState(initial = emptyList())
    val p by vm.player.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val rules = remember(pl?.rules) { SmartRules.parse(pl?.rules ?: "") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topInset() + 12.dp, bottom = bottomPad)) {
        item {
            ScreenTop(nav) {
                Box {
                    IconBtn(LIcons.More, "Действия", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Изменить правила") },
                            leadingIcon = { Icon(LIcons.Rules, null, Modifier.size(20.dp)) },
                            onClick = { menu = false; nav.open(Dest.SmartEditor(id)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Сохранить как обычный") },
                            leadingIcon = { Icon(LIcons.Playlist, null, Modifier.size(20.dp)) },
                            onClick = {
                                menu = false
                                val name = pl?.name ?: "Плейлист"
                                vm.createPlaylist(name, tracks.map { it.uri }) { vm.toast("Сохранено: «$name»") }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            leadingIcon = { Icon(LIcons.Trash, null, Modifier.size(20.dp)) },
                            onClick = { menu = false; vm.deleteSmart(id); nav.back() },
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                SmartCover(tracks.take(4).map { it.uri }, 200.dp, 28.dp)
                Spacer(Modifier.height(18.dp))
                BigTitle(
                    pl?.name.orEmpty(),
                    "${pluralRu(tracks.size, "трек", "трека", "треков")} · ${tracks.sumOf { it.durationMs } / 60000} мин",
                )
                Text(
                    rules.summary(),
                    fontSize = 13.sp,
                    color = LocalAccent.current,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { nav.open(Dest.SmartEditor(id)) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (tracks.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Слушать", LIcons.Play, { vm.play(tracks, 0) }, Modifier.weight(1f))
                        PillButton("Перемешать", LIcons.Shuffle, { vm.shuffle(tracks) }, Modifier.weight(1f), primary = false)
                    }
                } else if (pl != null) {
                    Text(
                        "Под эти правила пока ничего не подходит",
                        fontSize = 14.sp,
                        color = LC.Faint,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.uri }) { i, t ->
            Box(Modifier.animateItem()) {
                TrackRow(t, t.uri == p.currentId, t.uri in favs, actions, onClick = { vm.play(tracks, i) })
            }
        }
    }
}

/** Редактор правил: заготовки, условия, сортировка, лимит и живой предпросмотр. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun SmartEditorScreen(vm: MainViewModel, id: Long, nav: Nav) {
    var name by remember { mutableStateOf("") }
    var matchAll by remember { mutableStateOf(true) }
    val conds = remember { mutableStateListOf<SmartCond>() }
    var sort by remember { mutableStateOf(SmartSort.RANDOM) }
    var limit by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(id <= 0) }
    var preview by remember { mutableStateOf<List<TrackEntity>?>(null) }

    LaunchedEffect(id) {
        if (id > 0) {
            vm.smartOnce(id)?.let { e ->
                val r = SmartRules.parse(e.rules)
                name = e.name
                matchAll = r.matchAll
                conds.clear(); conds.addAll(r.conds)
                sort = r.sort
                limit = r.limit
            }
            loaded = true
        } else if (conds.isEmpty()) {
            conds.add(SmartCond(SmartField.ARTIST, SmartOp.CONTAINS, ""))
        }
    }
    fun current() = SmartRules(matchAll, conds.toList(), sort, limit)
    // Предпросмотр пересчитывается сам, с небольшой задержкой после правки.
    LaunchedEffect(Unit) {
        snapshotFlow { current() }.debounce(250).collect { r -> preview = vm.smartPreview(r) }
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(top = topInset() + 12.dp, bottom = 140.dp),
    ) {
        item { ScreenTop(nav) }
        item {
            Text(
                if (id > 0) "Правила плейлиста" else "Умный плейлист",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                "Состав обновляется сам: добавил трек, поставил лайк, послушал — плейлист уже другой.",
                fontSize = 13.sp,
                color = LC.Dim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            EditorLabel("Название")
            InputField(name, "Например, «Вечерний FLAC»", Modifier.padding(horizontal = 20.dp)) { name = it.take(60) }
        }
        if (id <= 0) item {
            EditorLabel("Заготовки")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmartRules.templates.forEach { (title, r) ->
                    LChip(title, false) {
                        if (name.isBlank() || SmartRules.templates.any { it.first == name }) name = title
                        matchAll = r.matchAll
                        conds.clear(); conds.addAll(r.conds)
                        sort = r.sort
                        limit = r.limit
                    }
                }
            }
        }
        item {
            EditorLabel("Условия")
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LChip("Все сразу", matchAll) { matchAll = true }
                LChip("Любое из них", !matchAll) { matchAll = false }
            }
            Spacer(Modifier.height(8.dp))
        }
        itemsIndexed(conds, key = { i, _ -> "c$i" }) { i, c ->
            CondCard(
                c,
                onChange = { conds[i] = it },
                onRemove = { conds.removeAt(i) },
                modifier = Modifier.animateItem(),
            )
        }
        item {
            Row(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { conds.add(SmartCond(SmartField.ARTIST, SmartOp.CONTAINS, "")) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Plus, null, Modifier.size(20.dp), tint = LocalAccent.current)
                Spacer(Modifier.width(8.dp))
                Text("Добавить условие", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current)
            }
        }
        item {
            EditorLabel("Порядок")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { SmartSort.entries.forEach { s -> LChip(s.label, sort == s) { sort = s } } }
            EditorLabel("Сколько треков")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0, 25, 50, 100, 200, 500).forEach { n -> LChip(if (n == 0) "Все" else "до $n", limit == n) { limit = n } }
            }
        }
        item {
            EditorLabel("Предпросмотр")
            val pv = preview
            AnimatedContent(
                pv?.size,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "pvCount",
            ) { n ->
                Text(
                    if (n == null) "Считаю…" else if (n == 0) "Ничего не подходит — ослабь условия" else "Подходит: ${pluralRu(n, "трек", "трека", "треков")}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(preview.orEmpty().take(6), key = { "pv:${it.uri}" }) { t ->
            Row(
                Modifier.fillMaxWidth().animateItem().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(t.uri, Modifier.size(40.dp), corner = 10.dp, sizePx = 128)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, fontSize = 12.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        PillButton(
            if (id > 0) "Сохранить" else "Создать плейлист",
            LIcons.Check,
            {
                if (!loaded) return@PillButton
                val r = current()
                vm.saveSmart(id, name.ifBlank { "Умный плейлист" }, r) { newId ->
                    nav.back()
                    if (id <= 0) nav.open(Dest.SmartPlaylist(newId))
                }
            },
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun EditorLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = LC.Faint,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun InputField(
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LC.Text.copy(alpha = 0.08f))
            .border(1.dp, LC.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(hint, fontSize = 15.sp, color = LC.Faint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = LC.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(LocalAccent.current),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CondCard(c: SmartCond, onChange: (SmartCond) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var pick by remember { mutableStateOf(false) }
    Column(
        modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LC.Glass)
            .border(1.dp, LC.Border, RoundedCornerShape(20.dp))
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 400f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { pick = true }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.field.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(LIcons.Down, null, Modifier.size(18.dp), tint = LC.Dim)
                }
                DropdownMenu(pick, { pick = false }) {
                    SmartField.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f.label) },
                            onClick = {
                                pick = false
                                if (f != c.field) {
                                    val op = if (f.kind == c.field.kind) c.op else SmartOp.forKind(f.kind).first()
                                    val v = when {
                                        f.kind == c.field.kind -> c.value
                                        f.kind == SmartField.Kind.DAYS -> "30"
                                        f == SmartField.FORMAT -> "FLAC"
                                        else -> ""
                                    }
                                    onChange(SmartCond(f, op, v))
                                }
                            },
                        )
                    }
                }
            }
            IconBtn(LIcons.Close, "Убрать условие", onRemove, glass = false, size = 36.dp, iconSize = 16.dp, tint = LC.Dim)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartOp.forKind(c.field.kind).forEach { op -> LChip(op.label, c.op == op) { onChange(c.copy(op = op)) } }
        }
        if (c.field.kind != SmartField.Kind.BOOL) {
            Spacer(Modifier.height(10.dp))
            if (c.field == SmartField.FORMAT) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("FLAC", "MP3", "AAC", "ALAC", "OPUS", "OGG", "WAV").forEach { f ->
                        LChip(f, c.value.equals(f, true)) { onChange(c.copy(value = f)) }
                    }
                }
            } else {
                val numeric = c.field.kind != SmartField.Kind.TEXT
                InputField(
                    c.value,
                    when (c.field.kind) {
                        SmartField.Kind.DAYS -> "Сколько дней"
                        SmartField.Kind.NUMBER -> "Число"
                        else -> "Текст"
                    },
                    numeric = numeric,
                ) { v -> onChange(c.copy(value = if (numeric) v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) else v.take(80))) }
            }
        }
    }
}

// ================= дубликаты =================

@Composable
fun DuplicatesScreen(vm: MainViewModel, nav: Nav, bottomPad: Dp) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    var groups by remember { mutableStateOf<List<DupGroup>?>(null) }
    val keep = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var confirm by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    LaunchedEffect(tracks.size) { groups = vm.findDuplicates() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) vm.onDuplicatesDeleted(pending)
        pending = emptyList()
    }

    fun doDelete(pairs: List<Pair<String, String>>) {
        scope.launch {
            val viaSystem = Build.VERSION.SDK_INT >= 30
            val direct = pairs.filter { !(viaSystem && it.first.startsWith("content://media/")) }
            val done = vm.deleteDirect(direct.map { it.first }).toSet()
            val directOk = direct.filter { it.first in done }
            if (directOk.isNotEmpty()) vm.onDuplicatesDeleted(directOk)
            if (direct.size > directOk.size) vm.toast("Часть файлов Android не дал удалить — удали их вручную")
            val media = pairs - direct.toSet()
            if (media.isNotEmpty()) {
                val sender = vm.deleteRequest(media.map { it.first })
                if (sender != null) {
                    pending = media
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }
            }
        }
    }

    fun request(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        // Системное окно подтверждения есть только для медиатеки на Android 11+; иначе спросим сами.
        val needOwn = Build.VERSION.SDK_INT < 30 || pairs.any { !it.first.startsWith("content://media/") }
        if (needOwn) confirm = pairs else doDelete(pairs)
    }

    fun extraOf(g: DupGroup): List<Pair<String, String>> {
        val k = keep[g.id] ?: g.best
        return g.tracks.filter { it.uri != k }.map { it.uri to k }
    }

    val list = groups
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topInset() + 12.dp, bottom = bottomPad)) {
        item { ScreenTop(nav) }
        item {
            Spacer(Modifier.height(8.dp))
            val extra = list?.sumOf { it.tracks.size - 1 } ?: 0
            BigTitle(
                "Дубликаты",
                when {
                    list == null -> "Ищу одинаковые треки…"
                    list.isEmpty() -> "Повторов не нашлось — библиотека чистая"
                    else -> "${pluralRu(list.size, "группа", "группы", "групп")} · лишних ${pluralRu(extra, "файл", "файла", "файлов")}. " +
                        "Отмечен лучший по качеству — нажми на другой, чтобы оставить его."
                },
            )
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            } else if (list.size > 1) {
                PillButton(
                    "Оставить лучшее везде",
                    LIcons.Sparkle,
                    { request(list.flatMap { extraOf(it) }) },
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            Text(
                "Лайки, прослушивания и места в плейлистах перейдут к оставшемуся файлу.",
                fontSize = 12.sp,
                color = LC.Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        items(list.orEmpty(), key = { it.id }) { g ->
            DupCard(
                g,
                keepUri = keep[g.id] ?: g.best,
                onKeep = { keep[g.id] = it },
                onPlay = { t -> vm.play(listOf(t), 0) },
                onDelete = { request(extraOf(g)) },
                onIgnore = {
                    vm.ignoreDuplicate(g.id)
                    groups = groups?.filter { it.id != g.id }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }

    AnimatedVisibility(confirm != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
        val pairs = confirm.orEmpty()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, null) { confirm = null },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(app.lumen.player.ui.theme.LT.sheetBrush())
                    .border(1.dp, LC.Border, RoundedCornerShape(28.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .padding(22.dp),
            ) {
                Text("Удалить ${pluralRu(pairs.size, "файл", "файла", "файлов")}?", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("Файлы удалятся с телефона насовсем. Всё, что с ними связано в Lumen, перейдёт к оставшимся копиям.", fontSize = 14.sp, color = LC.Dim)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Отмена", null, { confirm = null }, Modifier.weight(1f), primary = false)
                    PillButton("Удалить", LIcons.Trash, { confirm = null; doDelete(pairs) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DupCard(
    g: DupGroup,
    keepUri: String,
    onKeep: (String) -> Unit,
    onPlay: (TrackEntity) -> Unit,
    onDelete: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val first = g.tracks.first()
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(LC.Glass)
            .border(1.dp, LC.Border, RoundedCornerShape(22.dp))
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(first.uri, Modifier.size(44.dp), corner = 12.dp, sizePx = 128)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(first.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(first.artist, fontSize = 13.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        g.tracks.forEach { t ->
            val selected = t.uri == keepUri
            val sel by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "dupSel")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onKeep(t.uri) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(2.dp, accent.copy(alpha = 0.35f + 0.65f * sel), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(12.dp).graphicsLayer { scaleX = sel; scaleY = sel }.clip(CircleShape).background(accent))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).graphicsLayer { alpha = 0.55f + 0.45f * sel }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(formatLabel(t.mime, t.uri), strong = selected)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOfNotNull(
                                t.bitrate.takeIf { it > 0 }?.let { "${it / 1000} кбит/с" },
                                t.size.takeIf { it > 0 }?.let { "%.1f МБ".format(it / 1048576f) },
                                formatMs(t.durationMs),
                            ).joinToString(" · "),
                            fontSize = 13.sp,
                            color = LC.Dim,
                        )
                        if (t.uri == g.best) {
                            Spacer(Modifier.width(8.dp))
                            Text("лучшее", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
                        }
                    }
                    Text(
                        t.path.ifEmpty { t.folderName },
                        fontSize = 12.sp,
                        color = LC.Faint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconBtn(LIcons.Play, "Послушать", { onPlay(t) }, glass = false, size = 36.dp, iconSize = 16.dp, tint = LC.Dim)
            }
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Оставить отмеченный", LIcons.Check, onDelete, Modifier.weight(1.4f))
            PillButton("Не дубли", null, onIgnore, Modifier.weight(1f), primary = false)
        }
    }
}

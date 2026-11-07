package app.lumen.player.ui.screens

import android.content.Intent
import android.os.Build
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.FolderRow
import app.lumen.player.playback.EqBand
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LChip
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun folderLabel(uri: String): String {
    val seg = Uri.decode(Uri.parse(uri).lastPathSegment ?: uri)
    val path = seg.substringAfter(':', seg)
    val volume = seg.substringBefore(':', "")
    val where = if (volume == "primary" || volume.isEmpty()) "Память телефона" else "SD-карта"
    return if (path.isBlank()) where else "$where › $path"
}

private fun folderPath(f: FolderRow): String = when {
    f.folder.startsWith("content://") -> folderLabel(f.folder)
    f.folder.startsWith("/storage/emulated/0") -> "Телефон" + f.folder.removePrefix("/storage/emulated/0").replace("/", " › ")
    f.folder.startsWith("/storage/") -> "SD-карта" + f.folder.removePrefix("/storage/").substringAfter('/', "").let { if (it.isEmpty()) "" else " › " + it.replace("/", " › ") }
    else -> f.folder
}

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    bottomPad: Dp,
    onOpenStats: () -> Unit,
    onEditTheme: (String) -> Unit = {},
    onOpen: (app.lumen.player.ui.Dest) -> Unit = {},
) {
    val dislikedCount = vm.dislikedTracks.collectAsStateWithLifecycle().value.size
    val ctx = LocalContext.current
    val folders by vm.folderList.collectAsStateWithLifecycle()
    val skipShort by vm.skipShort.collectAsStateWithLifecycle()
    val online by vm.onlineLyrics.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val eq by vm.eq.collectAsStateWithLifecycle()
    val allFolders by vm.allFolders.collectAsStateWithLifecycle()
    val excluded by vm.excludedFolders.collectAsStateWithLifecycle()
    var foldersOpen by rememberSaveable { mutableStateOf(false) }
    var eqOpen by rememberSaveable { mutableStateOf(false) }
    var autoEqOpen by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var withMusic by rememberSaveable { mutableStateOf(false) }
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val smartQ by vm.smartQueue.collectAsStateWithLifecycle()
    val showFmt by vm.showFormat.collectAsStateWithLifecycle()
    val norm by vm.normalize.collectAsStateWithLifecycle()
    val fadeP by vm.fadePause.collectAsStateWithLifecycle()
    val resumeL by vm.resumeLong.collectAsStateWithLifecycle()
    val btR by vm.btResume.collectAsStateWithLifecycle()
    val lockS by vm.lockScreen.collectAsStateWithLifecycle()
    var playbackOpen by rememberSaveable { mutableStateOf(false) }
    val bulk by vm.lyricsBulk.collectAsStateWithLifecycle()
    val flashes by vm.beatFlashes.collectAsStateWithLifecycle()
    val intensity by vm.beatIntensity.collectAsStateWithLifecycle()
    val crossfade by vm.crossfadeSec.collectAsStateWithLifecycle()
    val cfSoft by vm.crossfadeSoft.collectAsStateWithLifecycle()
    val upd by vm.updateState.collectAsStateWithLifecycle()
    val autoUpd by vm.autoUpdate.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            vm.importLibrary(uri) { ok, matched, total ->
                importing = false
                Toast.makeText(
                    ctx,
                    if (ok) "Импорт готов: найдено $matched из $total треков" else "Это не архив Lumen",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Чтение + запись: запись нужна, чтобы класть .lrc с текстом рядом с треками.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            vm.addFolder(uri.toString())
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            exporting = true
            vm.exportLibrary(uri, withMusic) { ok, count ->
                exporting = false
                Toast.makeText(
                    ctx,
                    if (ok) "Архив готов: ${pluralRu(count, "трек", "трека", "треков")}" else "Не удалось сохранить архив",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }

        // ---------- статистика ----------
        item {
            Card(Modifier.clickable(onClick = onOpenStats)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Статистика", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Сколько часов прослушано, запуски, топ треков и исполнителей, время суток",
                            fontSize = 13.sp,
                            color = LC.Dim,
                        )
                    }
                    Icon(LIcons.Back, null, Modifier.size(20.dp).graphicsLayer { rotationZ = 180f }, tint = LC.Dim)
                }
            }
        }

        // ---------- вспышки ----------
        item {
            Card {
                Text("Вспышки под бит", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ToggleRow(
                    "Вспышки в плеере",
                    "Подсветка и обложка пульсируют в такт ударам. Ритм считается один раз и сохраняется — дальше вспышки начинаются мгновенно",
                    flashes,
                ) { vm.setBeatFlashes(it) }
                if (flashes) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сила вспышек", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("${(intensity * 100).roundToInt()}%", fontSize = 14.sp, color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                    }
                    var local by remember(intensity) { mutableFloatStateOf(intensity) }
                    Slider(
                        value = local,
                        onValueChange = {
                            local = it
                            vm.setBeatIntensity(it)
                        },
                        valueRange = 0.1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = LocalAccent.current,
                            inactiveTrackColor = Color(0x26FFFFFF),
                        ),
                    )
                }
            }
        }

        // ---------- обновления ----------
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Обновления", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when (val s = upd) {
                                is app.lumen.player.ui.UpdateUi.Checking -> "Проверяю…"
                                is app.lumen.player.ui.UpdateUi.UpToDate -> "У тебя последняя версия · ${vm.versionName}"
                                is app.lumen.player.ui.UpdateUi.Available -> "Доступна версия ${s.release.versionName}"
                                is app.lumen.player.ui.UpdateUi.Downloading -> "Загружаю ${s.release.versionName} · ${(s.progress * 100).roundToInt()}%"
                                is app.lumen.player.ui.UpdateUi.NeedPermission -> "Скачано — нужно разрешение на установку"
                                is app.lumen.player.ui.UpdateUi.Installing -> "Устанавливаю…"
                                is app.lumen.player.ui.UpdateUi.Error -> s.message
                                else -> "Версия ${vm.versionName}"
                            },
                            fontSize = 13.sp,
                            color = LC.Dim,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val hasNew = upd is app.lumen.player.ui.UpdateUi.Available || upd is app.lumen.player.ui.UpdateUi.Downloading ||
                        upd is app.lumen.player.ui.UpdateUi.NeedPermission
                    PillButton(
                        if (hasNew) "Открыть" else "Проверить",
                        null,
                        {
                            if (hasNew) vm.updateSheet.value = true else vm.checkUpdates(manual = true)
                        },
                        primary = hasNew,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Проверять при запуске", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    LSwitch(autoUpd) { vm.setAutoUpdate(it) }
                }
            }
        }

        // ---------- темы ----------
        item {
            Card { ThemesSection(onEditTheme) }
        }

        // ---------- интерфейс ----------
        item {
            Card {
                ToggleRow(
                    "Показывать формат файла",
                    "Метки FLAC, MP3 и качество звука в плеере, списках, альбомах и плейлистах",
                    showFmt,
                ) { vm.setShowFormat(it) }
            }
        }

        // ---------- воспроизведение ----------
        item {
            ExpandableCard(
                title = "Воспроизведение",
                subtitle = listOfNotNull(
                    "выравнивание".takeIf { norm },
                    "плавная пауза".takeIf { fadeP },
                    "умная очередь".takeIf { smartQ },
                ).joinToString(" · ").ifEmpty { "Всё выключено" }.replaceFirstChar { it.uppercase() },
                expanded = playbackOpen,
                onToggle = { playbackOpen = !playbackOpen },
            ) {
                Spacer(Modifier.height(6.dp))
                ToggleRow("Выравнивание громкости", "Громкие треки звучат на одном уровне с тихими — без скачков между песнями", norm) { vm.setNormalize(it) }
                ToggleRow("Плавная пауза и старт", "Музыка мягко затихает при паузе и нарастает при запуске", fadeP) { vm.setFadePause(it) }
                ToggleRow("Умная очередь", "Когда очередь кончается, Lumen продолжит похожими треками из твоей библиотеки", smartQ) { vm.setSmartQueue(it) }
                ToggleRow("Помнить место в длинных треках", "Подкасты, миксы и аудиокниги от 20 минут продолжатся с того же места", resumeL) { vm.setResumeLong(it) }
                ToggleRow("Продолжать при подключении наушников", "Bluetooth-наушники подключились — музыка продолжится, если ты недавно поставил паузу", btR) { vm.setBtResume(it) }
                ToggleRow("Экран блокировки Lumen", "Крупные часы и текст песни на заблокированном экране, пока играет музыка", lockS) { on ->
                    if (on && Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(ctx)) {
                        Toast.makeText(ctx, "Разреши Lumen «Поверх других окон» — так система позволит показать экран блокировки", Toast.LENGTH_LONG).show()
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    vm.setLockScreen(on)
                }
                if (lockS) {
                    Text(
                        "На Xiaomi ещё включи в «О приложении» → «Другие разрешения»: «Экран блокировки» и «Всплывающие окна в фоне».",
                        fontSize = 12.sp,
                        color = LC.Faint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // ---------- кроссфейд ----------
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Кроссфейд", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Конец песни плавно перетекает в начало следующей",
                            fontSize = 13.sp,
                            color = LC.Dim,
                        )
                    }
                    LSwitch(crossfade > 0) { vm.setCrossfadeSec(if (it) 6 else 0) }
                }
                androidx.compose.animation.AnimatedVisibility(
                    crossfade > 0,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) +
                        androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(320)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                        androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(280)),
                ) {
                    Column {
                        var local by remember(crossfade) { mutableFloatStateOf(crossfade.toFloat()) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                            Text("Длительность", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${local.roundToInt()} с", fontSize = 14.sp, color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = local,
                            onValueChange = { local = it },
                            onValueChangeFinished = { vm.setCrossfadeSec(local.roundToInt().coerceAtLeast(1)) },
                            valueRange = 1f..12f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = LocalAccent.current,
                                inactiveTrackColor = Color(0x26FFFFFF),
                            ),
                        )
                        var soft by remember(cfSoft) { mutableFloatStateOf(cfSoft) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Мягкость перехода", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    soft < 0.33f -> "Резко"
                                    soft < 0.7f -> "Сбалансированно"
                                    else -> "Очень плавно"
                                },
                                fontSize = 14.sp,
                                color = LocalAccent.current,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Slider(
                            value = soft,
                            onValueChange = { soft = it },
                            onValueChangeFinished = { vm.setCrossfadeSoft(soft) },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = LocalAccent.current,
                                inactiveTrackColor = Color(0x26FFFFFF),
                            ),
                        )
                        Text(
                            "Длительность — насколько рано начинается переход. Мягкость — как плавно одна песня растворяется в другой.",
                            fontSize = 12.sp,
                            color = LC.Faint,
                        )
                    }
                }
            }
        }

        // ---------- эквалайзер ----------
        item {
            ExpandableCard(
                title = "Эквалайзер",
                subtitle = when {
                    !eq.available -> "Включи любой трек — эквалайзер подключится к плееру"
                    !eq.enabled -> "Выключен · ${eq.bands.size} полос"
                    eq.preset >= 0 -> "Включён · ${eq.presets.getOrNull(eq.preset) ?: "пресет"}"
                    else -> "Включён · своя настройка"
                },
                expanded = eqOpen,
                onToggle = { eqOpen = !eqOpen },
                trailing = { LSwitch(eq.enabled && eq.available, enabled = eq.available) { vm.eqSetEnabled(it) } },
            ) {
                if (eq.available) {
                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.graphicsLayer { alpha = if (eq.enabled) 1f else 0.45f },
                    ) {
                        itemsIndexed(eq.presets) { i, name ->
                            LChip(name, eq.enabled && eq.preset == i) { if (eq.enabled) vm.eqPreset(i) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = if (eq.enabled) 1f else 0.45f },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        eq.bands.forEachIndexed { i, b ->
                            EqBandSlider(b, eq.minLevel, eq.maxLevel, Modifier.weight(1f), enabled = eq.enabled) { vm.eqSetBand(i, it) }
                        }
                    }
                    if (eq.bassSupported) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Усиление баса", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${eq.bass / 10}%", fontSize = 14.sp, color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                        }
                        var bass by remember(eq.bass) { mutableFloatStateOf(eq.bass.toFloat()) }
                        Slider(
                            value = bass,
                            onValueChange = { bass = it },
                            onValueChangeFinished = { vm.eqSetBass(bass.roundToInt()) },
                            valueRange = 0f..1000f,
                            enabled = eq.enabled,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = LocalAccent.current,
                                inactiveTrackColor = Color(0x26FFFFFF),
                            ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    PillButton(
                        "Сбросить",
                        LIcons.Refresh,
                        { if (eq.enabled) vm.eqReset() },
                        Modifier.fillMaxWidth().graphicsLayer { alpha = if (eq.enabled) 1f else 0.45f },
                        primary = false,
                    )
                    if (!eq.enabled) {
                        Spacer(Modifier.height(6.dp))
                        Text("Включи эквалайзер переключателем сверху, чтобы менять настройки.", fontSize = 12.sp, color = LC.Faint)
                    }
                }
            }
        }

        // ---------- AutoEq ----------
        item {
            val st by vm.autoEq.collectAsStateWithLifecycle()
            val results by vm.autoEqResults.collectAsStateWithLifecycle()
            val busy by vm.autoEqBusy.collectAsStateWithLifecycle()
            val err by vm.autoEqError.collectAsStateWithLifecycle()
            var q by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(autoEqOpen) { if (autoEqOpen) vm.refreshAutoEqDevice() }
            ExpandableCard(
                title = "AutoEq — звук под наушники",
                subtitle = when {
                    !st.supported -> "Нужен Android 9 или новее"
                    st.profile == null -> "${st.deviceName}: профиль не выбран"
                    !st.enabled -> "Выключен · ${st.deviceName}"
                    else -> "${st.deviceName} · ${st.profile?.name}"
                },
                expanded = autoEqOpen,
                onToggle = { autoEqOpen = !autoEqOpen },
                trailing = if (st.supported) ({ LSwitch(st.enabled, enabled = st.profile != null) { vm.setAutoEqEnabled(it) } }) else null,
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Поправка, которая выравнивает звучание конкретной модели наушников по измерениям проекта AutoEq. " +
                        "Для каждых наушников запоминается свой профиль и включается сам при подключении. Работает и с 24-битным FLAC.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                if (st.supported) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LIcons.Wave, null, Modifier.size(20.dp), tint = LocalAccent.current)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Сейчас: ${st.deviceName}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                st.profile?.let { "Профиль: ${it.name} (${it.source.substringBefore(" on ")})" } ?: "Найди свою модель ниже",
                                fontSize = 12.sp,
                                color = LC.Dim,
                            )
                        }
                        if (st.profile != null) IconBtn(LIcons.Trash, "Убрать профиль", { vm.clearAutoEq() }, glass = false, size = 40.dp, iconSize = 18.dp, tint = LC.Dim)
                    }
                    Spacer(Modifier.height(12.dp))
                    SearchField(q, { q = it; vm.searchAutoEq(it) })
                    if (busy) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Загружаю…", fontSize = 13.sp, color = LC.Dim)
                        }
                    }
                    err?.let { Text(it, fontSize = 13.sp, color = Color(0xFFFF8A8A), modifier = Modifier.padding(top = 8.dp)) }
                    if (q.trim().length >= 2 && results.isEmpty() && !busy && err == null) {
                        Text("Ничего не нашлось. Попробуй короче: «HD 600», «Buds2», «AirPods».", fontSize = 13.sp, color = LC.Faint, modifier = Modifier.padding(top = 8.dp))
                    }
                    results.forEach { e ->
                        val chosen = st.profile?.name == e.name && st.profile?.source == e.source
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.pickAutoEq(e) }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (chosen) LocalAccent.current else LC.Text)
                                Text("измерения: ${e.source}", fontSize = 12.sp, color = LC.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (chosen) Icon(LIcons.Check, null, Modifier.size(18.dp), tint = LocalAccent.current)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Профили: github.com/jaakkopasanen/AutoEq", fontSize = 11.sp, color = LC.Faint)
                }
            }
        }

        // ---------- библиотека ----------
        item {
            Card {
                Text("Библиотека", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${pluralRu(tracks.size, "трек", "трека", "треков")} в медиатеке и выбранных папках. Новые файлы появляются сами.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Пересканировать", LIcons.Refresh, { vm.rescan() }, Modifier.weight(1f), primary = false)
                    if (scanning) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    "Скрывать короткие треки",
                    "Звуки короче 30 секунд не попадут в библиотеку",
                    skipShort,
                ) { vm.setSkipShort(it) }
                NavRow(
                    LIcons.Dislike,
                    "Не нравится",
                    if (dislikedCount == 0) "Отмечай через меню трека — они пропадут из подборок"
                    else "${pluralRu(dislikedCount, "трек", "трека", "треков")} не попадают в подборки и перемешивание",
                ) { onOpen(app.lumen.player.ui.Dest.Disliked) }
                NavRow(LIcons.Duplicate, "Поиск дубликатов", "Одинаковые треки в разных файлах и форматах") {
                    onOpen(app.lumen.player.ui.Dest.Duplicates)
                }
            }
        }

        // ---------- свои папки ----------
        item {
            Card {
                Text("Папки с музыкой", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Музыка из системной медиатеки находится сама. Добавь папку, если треки лежат там, где Android их не видит.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(10.dp))
                folders.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LIcons.Folder, null, Modifier.size(20.dp), tint = LocalAccent.current)
                        Spacer(Modifier.width(10.dp))
                        Text(folderLabel(f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconBtn(LIcons.Trash, "Убрать папку", { vm.removeFolder(f) }, glass = false, size = 40.dp, iconSize = 18.dp, tint = LC.Dim)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PillButton("Добавить папку", LIcons.Plus, { pickFolder.launch(null) }, Modifier.fillMaxWidth())
            }
        }

        // ---------- исключённые папки ----------
        item {
            val hiddenCount = allFolders.count { it.folder in excluded }
            ExpandableCard(
                title = "Скрытые папки",
                subtitle = if (allFolders.isEmpty()) "Папки появятся после сканирования"
                else "Скрыто $hiddenCount из ${allFolders.size} · нажми, чтобы выбрать",
                expanded = foldersOpen,
                onToggle = { foldersOpen = !foldersOpen },
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Выключи папку — её треки пропадут из приложения. Файлы на телефоне не трогаются.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(6.dp))
                allFolders.forEach { f ->
                    val hidden = f.folder in excluded
                    val dim by androidx.compose.animation.core.animateFloatAsState(if (hidden) 0.5f else 1f, tween(260), label = "fdim")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { vm.setFolderHidden(f.folder, !hidden) }
                            .padding(vertical = 8.dp)
                            .graphicsLayer { alpha = dim },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(LIcons.Folder, null, Modifier.size(20.dp), tint = if (hidden) LC.Dim else LocalAccent.current)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.folderName, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${folderPath(f)} · ${pluralRu(f.trackCount, "звук", "звука", "звуков")}",
                                fontSize = 12.sp,
                                color = LC.Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        LSwitch(!hidden) { show -> vm.setFolderHidden(f.folder, !show) }
                    }
                }
            }
        }

        // ---------- тексты ----------
        item {
            Card {
                Text("Тексты песен", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ToggleRow(
                    "Искать тексты онлайн",
                    "Текст ищется сразу при запуске трека: сначала .lrc рядом с файлом, потом LRCLIB. Найденное сохраняется файлом и доступно офлайн",
                    online,
                ) { vm.setOnlineLyrics(it) }
                val karaokeOn by vm.karaoke.collectAsStateWithLifecycle()
                ToggleRow(
                    "Караоке-подсветка",
                    "Текущая строка заливается по мере пения. Долгий тап по строке — карточка-цитата",
                    karaokeOn,
                ) { vm.setKaraoke(it) }
                Spacer(Modifier.height(8.dp))
                val b = bulk
                when {
                    b != null && b.running -> {
                        Text(
                            if (b.total == 0) "Проверяю библиотеку…" else "Скачиваю тексты: ${b.done} из ${b.total} · найдено ${b.found}",
                            fontSize = 13.sp,
                            color = LC.Dim,
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (b.total > 0) b.done.toFloat() / b.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = LocalAccent.current,
                            trackColor = Color(0x26FFFFFF),
                        )
                        Spacer(Modifier.height(10.dp))
                        PillButton("Остановить", LIcons.Close, { vm.stopLyricsDownload() }, Modifier.fillMaxWidth(), primary = false)
                    }
                    else -> {
                        if (b != null) {
                            Text(
                                when {
                                    b.offline -> "Остановлено: нет сети. Скачано ${b.done} из ${b.total}."
                                    b.total == 0 -> "Все тексты уже скачаны."
                                    else -> "Готово: найдено ${b.found} из ${b.total}."
                                },
                                fontSize = 13.sp,
                                color = LC.Dim,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        PillButton(
                            "Скачать тексты для всей библиотеки",
                            LIcons.Lyrics,
                            { if (online) vm.startLyricsDownload() },
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = if (online) 1f else 0.4f },
                            primary = false,
                        )
                    }
                }
            }
        }

        // ---------- перенос на ПК ----------
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Экспорт и импорт", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (exporting) CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    "Всё упаковывается в один ZIP-архив: медиатека с тегами, любимое, статистика и история прослушиваний, " +
                        "скрытые папки, настройки и сохранённые тексты песен. Архив открывается и на телефоне, и в Lumen для ПК.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    "Вместе с самими треками",
                    "Музыкальные файлы тоже попадут в архив — около ${humanSize(vm.musicBytes())}",
                    withMusic,
                ) { withMusic = it }
                Spacer(Modifier.height(10.dp))
                PillButton(
                    "Экспортировать в архив",
                    LIcons.Plus,
                    {
                        if (transfer == null) {
                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportFile.launch("lumen-$date.zip")
                        }
                    },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                PillButton(
                    "Импортировать из архива",
                    LIcons.Refresh,
                    { if (transfer == null) importFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "application/octet-stream", "*/*")) },
                    Modifier.fillMaxWidth(),
                    primary = false,
                )
                androidx.compose.animation.AnimatedVisibility(
                    transfer != null,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    val t = transfer
                    val p by androidx.compose.animation.core.animateFloatAsState(t?.second ?: 1f, tween(400), label = "tr")
                    Column(Modifier.padding(top = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t?.first ?: "Готово", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${(p * 100).roundToInt()}%", fontSize = 14.sp, color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x1FFFFFFF))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(p.coerceIn(0.02f, 1f))
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(LocalAccent.current)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Импорт вернёт всё из архива. Треки сопоставляются по пути, а если его нет — по названию, исполнителю и длительности. " +
                        "Если в архиве есть треки, которых нет на телефоне, они сохранятся в «Музыка/Lumen». Старые .json-файлы тоже открываются.",
                    fontSize = 12.sp,
                    color = LC.Faint,
                )
            }
        }

        item {
            Card {
                Text("О приложении", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Lumen ${vm.versionName}", fontSize = 14.sp, color = LC.Dim)
                Text("Тексты песен: lrclib.net", fontSize = 14.sp, color = LC.Dim)
                Text("Никакой рекламы и слежки — всё работает на телефоне.", fontSize = 14.sp, color = LC.Dim)
            }
        }
    }
}

private fun freqLabel(hz: Int): String = when {
    hz >= 1000 -> {
        val k = hz / 1000f
        if (k >= 10f || k == k.toInt().toFloat()) "${k.roundToInt()}k" else "%.1fk".format(k).replace('.', ',')
    }
    else -> hz.toString()
}

/** Вертикальный ползунок полосы эквалайзера: тяни вверх/вниз или тапни в нужное место. */
@Composable
private fun EqBandSlider(band: EqBand, min: Int, max: Int, modifier: Modifier, enabled: Boolean, onChange: (Int) -> Unit) {
    val accent = LocalAccent.current
    val change by rememberUpdatedState(onChange)
    var local by remember(band.level) { mutableIntStateOf(band.level) }
    val shown by animateFloatAsState(local.toFloat(), label = "eqBand")
    val range = (max - min).coerceAtLeast(1)

    fun levelAt(y: Float, h: Float): Int {
        val f = 1f - (y / h).coerceIn(0f, 1f)
        val raw = min + f * range
        return ((raw / 100f).roundToInt() * 100).coerceIn(min, max)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val db = local / 100
        Text(if (db > 0) "+$db" else "$db", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LC.Dim)
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .width(36.dp)
                .height(150.dp)
                .pointerInput(min, max, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { pos ->
                        local = levelAt(pos.y, size.height.toFloat())
                        change(local)
                    }
                }
                .pointerInput(min, max, enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(onDragEnd = { change(local) }) { c, _ ->
                        c.consume()
                        val l = levelAt(c.position.y, size.height.toFloat())
                        if (l != local) {
                            local = l
                            change(l)
                        }
                    }
                }
        ) {
            val cx = size.width / 2
            val h = size.height
            val pad = 8.dp.toPx()
            val usable = h - pad * 2
            fun yOf(level: Float) = pad + (1f - (level - min) / range) * usable
            val zeroY = yOf(0f.coerceIn(min.toFloat(), max.toFloat()))
            val knobY = yOf(shown)
            drawLine(Color(0x24FFFFFF), Offset(cx, pad), Offset(cx, h - pad), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0x4DFFFFFF), Offset(cx - 9.dp.toPx(), zeroY), Offset(cx + 9.dp.toPx(), zeroY), 1.dp.toPx())
            drawLine(accent, Offset(cx, zeroY), Offset(cx, knobY), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(Color(0x1FFFFFFF), 12.dp.toPx(), Offset(cx, knobY))
            drawCircle(Color.White, 8.dp.toPx(), Offset(cx, knobY))
        }
        Spacer(Modifier.height(6.dp))
        Text(freqLabel(band.freqHz), fontSize = 11.sp, color = LC.Dim)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) { content() }
}

@Composable
private fun LSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = accent,
            checkedBorderColor = accent,
            uncheckedThumbColor = LC.Dim,
            uncheckedTrackColor = Color(0x1FFFFFFF),
            uncheckedBorderColor = Color(0x33FFFFFF),
        ),
    )
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = LocalAccent.current)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = LC.Dim)
        }
        Icon(LIcons.Back, null, Modifier.size(18.dp).graphicsLayer { rotationZ = 180f }, tint = LC.Dim)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = LC.Dim)
        }
        Spacer(Modifier.width(12.dp))
        LSwitch(checked) { onChange(it) }
    }
}

/**
 * Раскрывающаяся карточка: шапка с названием и кратким статусом, стрелка плавно
 * поворачивается, содержимое мягко выезжает вниз и так же уезжает обратно.
 */
@Composable
private fun ExpandableCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val rot by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 180f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "chev",
    )
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClickLabel = if (expanded) "Свернуть" else "Развернуть", onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                androidx.compose.animation.AnimatedContent(
                    subtitle,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(tween(220)) togetherWith androidx.compose.animation.fadeOut(tween(160))
                    },
                    label = "sub",
                ) { t -> Text(t, fontSize = 13.sp, color = LC.Dim) }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                LIcons.Down,
                null,
                Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = rot },
                tint = LC.Dim,
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            expanded,
            enter = androidx.compose.animation.expandVertically(
                androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 260f),
                expandFrom = Alignment.Top,
            ) + androidx.compose.animation.fadeIn(tween(260, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(
                androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 320f),
                shrinkTowards = Alignment.Top,
            ) + androidx.compose.animation.fadeOut(tween(160)),
        ) {
            Column { content() }
        }
    }
}

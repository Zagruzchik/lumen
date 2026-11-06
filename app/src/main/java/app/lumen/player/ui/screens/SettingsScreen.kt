package app.lumen.player.ui.screens

import android.content.Intent
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun SettingsScreen(vm: MainViewModel, bottomPad: Dp) {
    val ctx = LocalContext.current
    val folders by vm.folderList.collectAsStateWithLifecycle()
    val skipShort by vm.skipShort.collectAsStateWithLifecycle()
    val online by vm.onlineLyrics.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val eq by vm.eq.collectAsStateWithLifecycle()
    val allFolders by vm.allFolders.collectAsStateWithLifecycle()
    val excluded by vm.excludedFolders.collectAsStateWithLifecycle()
    var showAllFolders by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addFolder(uri.toString())
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            exporting = true
            vm.exportLibrary(uri) { ok, count ->
                exporting = false
                Toast.makeText(
                    ctx,
                    if (ok) "Готово: ${pluralRu(count, "трек", "трека", "треков")} в файле" else "Не удалось сохранить файл",
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

        // ---------- эквалайзер ----------
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Эквалайзер", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (eq.available) "${eq.bands.size} полос, работает для всей музыки в Lumen"
                            else "Включи любой трек — эквалайзер подключится к плееру",
                            fontSize = 13.sp,
                            color = LC.Dim,
                        )
                    }
                    LSwitch(eq.enabled && eq.available, enabled = eq.available) { vm.eqSetEnabled(it) }
                }
                if (eq.available) {
                    Spacer(Modifier.height(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(eq.presets) { i, name ->
                            LChip(name, eq.enabled && eq.preset == i) { vm.eqPreset(i) }
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
                            EqBandSlider(b, eq.minLevel, eq.maxLevel, Modifier.weight(1f)) { vm.eqSetBand(i, it) }
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
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = LocalAccent.current,
                                inactiveTrackColor = Color(0x26FFFFFF),
                            ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    PillButton("Сбросить", LIcons.Refresh, { vm.eqReset() }, Modifier.fillMaxWidth(), primary = false)
                }
            }
        }

        // ---------- библиотека ----------
        item {
            Card {
                Text("Библиотека", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${pluralRu(tracks.size, "трек", "трека", "треков")} в медиатеке и выбранных папках",
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
            Card(Modifier.animateContentSize()) {
                Text("Исключённые папки", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val hiddenCount = allFolders.count { it.folder in excluded }
                Text(
                    "Все папки, где найдены звуки. Выключи папку — её треки пропадут из приложения. " +
                        "Скрыто: $hiddenCount из ${allFolders.size}.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(8.dp))
                val shown = if (showAllFolders) allFolders else allFolders.take(6)
                shown.forEach { f ->
                    val hidden = f.folder in excluded
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.setFolderHidden(f.folder, !hidden) }
                            .padding(vertical = 8.dp)
                            .graphicsLayer { alpha = if (hidden) 0.5f else 1f },
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
                if (allFolders.size > 6) {
                    Spacer(Modifier.height(6.dp))
                    PillButton(
                        if (showAllFolders) "Свернуть" else "Показать все (${allFolders.size})",
                        null,
                        { showAllFolders = !showAllFolders },
                        Modifier.fillMaxWidth(),
                        primary = false,
                    )
                }
            }
        }

        // ---------- тексты ----------
        item {
            Card {
                Text("Тексты песен", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ToggleRow(
                    "Искать тексты онлайн",
                    "Если рядом с треком нет .lrc, текст подтянется из LRCLIB (бесплатно, без регистрации)",
                    online,
                ) { vm.setOnlineLyrics(it) }
            }
        }

        // ---------- перенос на ПК ----------
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Перенос медиатеки на ПК", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (exporting) CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    "Сохрани всю медиатеку в один файл: треки с путями и тегами, любимое, статистику прослушиваний, " +
                        "исключённые папки и настройки. Программа Lumen для ПК по этому файлу перенесёт музыку и библиотеку целиком.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(12.dp))
                PillButton(
                    "Экспортировать медиатеку",
                    LIcons.Plus,
                    {
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        exportFile.launch("lumen-library-$date.json")
                    },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("Программа для ПК — в разработке.", fontSize = 12.sp, color = LC.Faint)
            }
        }

        item {
            Card {
                Text("О приложении", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Lumen 0.2.0", fontSize = 14.sp, color = LC.Dim)
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
private fun EqBandSlider(band: EqBand, min: Int, max: Int, modifier: Modifier, onChange: (Int) -> Unit) {
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
                .pointerInput(min, max) {
                    detectTapGestures { pos ->
                        local = levelAt(pos.y, size.height.toFloat())
                        change(local)
                    }
                }
                .pointerInput(min, max) {
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

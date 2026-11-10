package app.lumen.player.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.LChip
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.SectionHeader
import app.lumen.player.ui.components.TrackActions
import app.lumen.player.ui.components.TrackRow
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent

/** Блок «В сети» в поиске, когда подключён свой сервер: SoundCloud / YouTube, ссылки, сразу играет. */
fun LazyListScope.netResultsSection(
    vm: MainViewModel,
    q: String,
    currentId: String?,
    favs: Set<String>,
    actions: TrackActions,
) {
    val link = vm.linkIn(q)
    if (link != null) {
        item(key = "netLink") { LinkCard(vm, link) }
        return
    }
    item(key = "netHead") {
        val loading by vm.netLoading.collectAsStateWithLifecycle()
        val src by vm.netSource.collectAsStateWithLifecycle()
        Column {
            Row(Modifier.fillMaxWidth().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("В сети") }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = LocalAccent.current, strokeWidth = 2.dp)
            }
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LChip("SoundCloud", src == "soundcloud") { vm.setNetSource("soundcloud") }
                LChip("YouTube", src == "youtube") { vm.setNetSource("youtube") }
                val mode by vm.onlineMode.collectAsStateWithLifecycle()
                if (mode != "server") LChip("Bandcamp", src == "bandcamp") { vm.setNetSource("bandcamp") }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    item(key = "netBody") {
        val err by vm.netError.collectAsStateWithLifecycle()
        val list by vm.netResults.collectAsStateWithLifecycle()
        val loading by vm.netLoading.collectAsStateWithLifecycle()
        when {
            err != null -> Text(
                err.orEmpty(),
                color = Color(0xFFFF8A8A),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            list?.isEmpty() == true && !loading -> Text(
                "Ничего не нашлось — попробуй другой источник",
                color = LC.Dim,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
    netRows(vm, currentId, favs, actions)
}

private fun LazyListScope.netRows(vm: MainViewModel, currentId: String?, favs: Set<String>, actions: TrackActions) {
    item(key = "netRows") {
        val list by vm.netResults.collectAsStateWithLifecycle()
        val l = list.orEmpty()
        Column {
            l.forEachIndexed { i, t ->
                TrackRow(t, t.uri == currentId, t.uri in favs, actions, onClick = { vm.playNet(l, i) })
            }
        }
    }
}

@Composable
private fun LinkCard(vm: MainViewModel, link: String) {
    val busy by vm.netBusy.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(enabled = busy == null) { vm.openLink(link) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy == link) CircularProgressIndicator(Modifier.size(22.dp), color = accent, strokeWidth = 2.dp)
        else Icon(LIcons.Play, null, Modifier.size(22.dp), tint = accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Открыть ссылку", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                "Трек сразу заиграет, плейлист сохранится в «Плейлисты»",
                fontSize = 12.sp,
                color = LC.Dim,
            )
            Text(link, fontSize = 12.sp, color = LC.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OnlineField(value: String, hint: String, ascii: Boolean = true, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
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
            keyboardOptions = KeyboardOptions(keyboardType = if (ascii) KeyboardType.Uri else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = LC.Dim)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = LC.Dim,
                uncheckedTrackColor = Color(0x1FFFFFFF),
                uncheckedBorderColor = Color(0x33FFFFFF),
            ),
        )
    }
}

/** Настройки → «Онлайн-музыка»: встроенный движок или свой сервер music-api. */
@Composable
fun OnlineSection(vm: MainViewModel) {
    val mode by vm.onlineMode.collectAsStateWithLifecycle()
    val ready by vm.onlineReady.collectAsStateWithLifecycle()
    val status by vm.onlineStatus.collectAsStateWithLifecycle()
    val checking by vm.onlineChecking.collectAsStateWithLifecycle()
    val viaServer by vm.onlineViaServer.collectAsStateWithLifecycle()
    val mp3 by vm.onlineMp3.collectAsStateWithLifecycle()
    val opus by vm.onlineOpus.collectAsStateWithLifecycle()
    val original by vm.onlineOriginal.collectAsStateWithLifecycle()
    val auto by vm.onlineAutoDownload.collectAsStateWithLifecycle()
    var server by rememberSaveable { mutableStateOf(vm.onlineServerValue()) }
    var key by rememberSaveable { mutableStateOf(vm.onlineKeyValue()) }
    var help by rememberSaveable { mutableStateOf(false) }
    val builtin = mode != "server"

    Text("Онлайн-музыка", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text(
        "Поиск и прослушивание SoundCloud, YouTube Music и Bandcamp прямо в Lumen: лайки, плейлисты, ссылки, скачивание в медиатеку.",
        fontSize = 13.sp,
        color = LC.Dim,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LChip("Встроенный", builtin) { vm.setOnlineMode("builtin") }
        LChip("Свой сервер", !builtin) { vm.setOnlineMode("server") }
    }
    Text(
        if (builtin) "Работает сразу, без Termux и настроек. Ссылки на звук достаются прямо в телефоне."
        else "Всё идёт через твой music-api (yt-dlp). Нужен запущенный сервер.",
        fontSize = 12.sp,
        color = LC.Faint,
        modifier = Modifier.padding(top = 6.dp),
    )

    AnimatedVisibility(!builtin) {
        Column {
            Spacer(Modifier.height(8.dp))
            OnlineField(server, "Адрес сервера, например http://127.0.0.1:8787") { server = it.trim() }
            OnlineField(key, "Ключ доступа (из music-api/.api_key)") { key = it.trim() }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (ready) "Сохранить и проверить" else "Подключить", LIcons.Check, { vm.saveOnline(server, key) }, Modifier.weight(1f))
            }
        }
    }
    if (builtin) {
        Spacer(Modifier.height(8.dp))
        PillButton("Проверить связь", null, { vm.checkOnline() }, Modifier.fillMaxWidth(), primary = false)
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(16.dp), color = LocalAccent.current, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        status?.let {
            Text(it, fontSize = 13.sp, color = if (it.startsWith("Ошибка")) Color(0xFFFF8A8A) else LocalAccent.current)
        }
    }
    val dl by vm.downloads.collectAsStateWithLifecycle()
    if (dl.isNotEmpty()) {
        val known = dl.values.filter { it >= 0f }
        Text(
            "Скачивается: ${dl.size}" + if (known.isNotEmpty()) " · ${(known.average() * 100).toInt()}%" else " · готовлю файл…",
            fontSize = 13.sp,
            color = LocalAccent.current,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    if (builtin) {
        SwitchRow(
            "Оригинал автора с SoundCloud",
            "Если автор разрешил скачивание, берём его исходный файл — часто это FLAC или WAV. Иначе — лучший поток",
            original,
        ) { vm.setOnlineOriginal(it) }
        SwitchRow(
            "YouTube в Opus",
            if (opus) "Чуть лучше звучит, но не все плееры понимают .webm" else "M4A (AAC) — играет везде",
            opus,
        ) { vm.setOnlineOpus(it) }
    } else {
        SwitchRow(
            "Играть через сервер",
            "Сервер сам перегоняет звук в MP3. Включи, если онлайн-треки не стартуют или рвутся. Перемотка будет медленнее",
            viaServer,
        ) { vm.setOnlineViaServer(it) }
        SwitchRow(
            "Скачивать в MP3",
            if (mp3) "Файлы играют везде, но это пережатие (320 кбит/с)" else "Оригинальный формат — лучшее качество (M4A/Opus)",
            mp3,
        ) { vm.setOnlineMp3(it) }
    }
    SwitchRow(
        "Скачивать понравившееся",
        "Поставил лайк онлайн-треку — он сам сохраняется в «Музыка/Lumen» и появляется в библиотеке",
        auto,
    ) { vm.setOnlineAutoDownload(it) }
    Text(
        "FLAC бывает только там, где он есть у источника: YouTube и обычные потоки SoundCloud — это сжатый звук, и перегон в FLAC не добавил бы качества.",
        fontSize = 12.sp,
        color = LC.Faint,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (!builtin) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { help = !help }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Как запустить сервер", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current, modifier = Modifier.weight(1f))
            Icon(LIcons.Down, null, Modifier.size(18.dp).graphicsLayer { rotationZ = if (help) 180f else 0f }, tint = LC.Dim)
        }
        AnimatedVisibility(help) {
            Column {
                listOf(
                    "В Termux: cd ~/music-api && ./run.sh — сервер напишет ключ доступа.",
                    "Сервер на этом же телефоне — адрес http://127.0.0.1:8787. На другом устройстве в той же сети — его IP, например http://192.168.1.5:8787.",
                    "Скопируй ключ из music-api/.api_key в поле выше и нажми «Подключить».",
                    "Чтобы Android не усыплял Termux: в уведомлении Termux нажми «Acquire wakelock» и сними для него ограничения батареи.",
                ).forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("${i + 1}.", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LocalAccent.current, modifier = Modifier.width(22.dp))
                        Text(step, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

package app.lumen.player.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.LyricsRepository
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LChip
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LT
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Строка в редакторе: текст и время начала (null — ещё не отмечена). */
private data class EditLine(val text: String, val time: Long?)

private val stampRe = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

private fun stampMs(m: MatchResult): Long {
    val f = m.groupValues[3]
    val frac = when (f.length) { 0 -> 0L; 1 -> f.toLong() * 100; 2 -> f.toLong() * 10; else -> f.take(3).toLong() }
    return m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac
}

/** Текст (обычный или LRC) → строки. Повторы вида [00:10][01:20]Припев разворачиваются по времени. */
private fun parseLines(text: String): List<EditLine> {
    val out = ArrayList<EditLine>()
    text.lines().forEach { raw ->
        val line = raw.trimEnd()
        val tags = stampRe.findAll(line).toList()
        if (tags.isEmpty()) {
            if (line.isNotBlank()) out += EditLine(line.trim(), null)
        } else {
            val body = line.substring(tags.last().range.last + 1).trim()
            tags.forEach { out += EditLine(body, stampMs(it)) }
        }
    }
    return if (out.isNotEmpty() && out.all { it.time != null }) out.sortedBy { it.time } else out
}

private fun fmtTime(ms: Long): String {
    val m = ms.coerceAtLeast(0)
    return "%02d:%02d.%02d".format(m / 60_000, (m / 1000) % 60, (m % 1000) / 10)
}

/** Строки → текст. Если отмечена хоть одна — получается LRC, пропуски заполняются между соседями. */
private fun serialize(lines: List<EditLine>): String {
    if (lines.none { it.time != null }) return lines.joinToString("\n") { it.text }
    val times = lines.map { it.time }.toMutableList()
    val known = times.indices.filter { times[it] != null }
    // До первой отметки — по 2 с назад, после последней — по 3 с вперёд, между — равномерно.
    for (i in 0 until known.first()) times[i] = (times[known.first()]!! - (known.first() - i) * 2000L).coerceAtLeast(0)
    for (i in known.last() + 1 until times.size) times[i] = times[known.last()]!! + (i - known.last()) * 3000L
    for (k in 0 until known.size - 1) {
        val a = known[k]
        val b = known[k + 1]
        if (b - a > 1) {
            val ta = times[a]!!
            val tb = times[b]!!
            for (i in a + 1 until b) times[i] = ta + (tb - ta) * (i - a) / (b - a)
        }
    }
    return lines.indices.joinToString("\n") { "[${fmtTime(times[it]!!)}]${lines[it].text}" }
}

/**
 * Редактор текста песни: вставить или найти текст, а потом расставить тайминги —
 * слушаешь трек и жмёшь «Отметить» в начале каждой строки. Точная подгонка — кнопками ±0,1 с.
 */
@Composable
fun LyricsEditorScreen(vm: MainViewModel, t: TrackEntity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val accent = LocalAccent.current
    val p by vm.player.collectAsStateWithLifecycle()
    val isCurrent = p.currentId == t.uri

    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<EditLine>() }
    var target by remember { mutableIntStateOf(0) }
    var confirmClose by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(t.uri) {
        val raw = vm.lyricsRaw(t)
        text = raw
        original = raw
        loaded = true
        // Есть синхронный текст — сразу на тайминги, иначе — на ввод текста.
        if (raw.isNotBlank() && stampRe.containsMatchIn(raw)) {
            lines.clear(); lines.addAll(parseLines(raw))
            target = lines.indexOfFirst { it.time == null }.let { if (it < 0) 0 else it }
            tab = 1
        }
    }

    fun syncFromText() {
        val fresh = parseLines(text)
        // Если строки не менялись — тайминги сохраняем.
        val old = lines.toList()
        val merged = fresh.mapIndexed { i, l -> if (l.time == null && old.getOrNull(i)?.text == l.text) old[i] else l }
        lines.clear(); lines.addAll(merged)
        target = target.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    }

    fun current(): String = if (tab == 1) serialize(lines) else text
    fun dirty() = loaded && current().trim() != original.trim()

    fun close() {
        if (dirty()) confirmClose = true else onClose()
    }

    fun save() {
        val out = current().trim()
        vm.saveLyrics(t, out)
        original = out
        onClose()
    }

    BackHandler { if (searchOpen) searchOpen = false else close() }

    Box(
        Modifier
            .fillMaxSize()
            .background(LT.background)
            .clickable(remember { MutableInteractionSource() }, null) {}
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // ---- верх ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(LIcons.Close, "Закрыть", { close() })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Текст песни", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${t.title} · ${t.artist}", fontSize = 12.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PillButton("Сохранить", LIcons.Check, { save() })
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LChip("Текст", tab == 0) {
                    if (tab == 1) text = serialize(lines)
                    tab = 0
                }
                LChip("Тайминги", tab == 1) {
                    if (tab == 0) syncFromText()
                    tab = 1
                }
            }
            Spacer(Modifier.height(8.dp))

            AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "lyrTab", modifier = Modifier.weight(1f)) { tb ->
                if (tb == 0) {
                    // ---- ввод текста ----
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LChip("Вставить", false) {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                                if (clip.isNotBlank()) text = if (text.isBlank()) clip.trim() else text.trimEnd() + "\n" + clip.trim()
                            }
                            LChip("Найти в LRCLIB", false) { searchOpen = true }
                            LChip("Без таймингов", false) { text = parseLines(text).joinToString("\n") { it.text } }
                            LChip("Очистить", false) { text = "" }
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, LC.Border, RoundedCornerShape(18.dp))
                                .padding(14.dp),
                        ) {
                            if (text.isEmpty() && loaded) {
                                Text(
                                    "Вставь или напиши текст — по строке на строчку песни.\nПотом открой «Тайминги» и расставь время под музыку.\nМожно сразу вставить готовый LRC с [мм:сс.сс].",
                                    fontSize = 15.sp,
                                    color = LC.Faint,
                                    lineHeight = 22.sp,
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                textStyle = TextStyle(color = LC.Text, fontSize = 16.sp, lineHeight = 24.sp),
                                cursorBrush = SolidColor(accent),
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            )
                        }
                        Text(
                            "${parseLines(text).size} строк" + if (stampRe.containsMatchIn(text)) " · есть тайминги" else "",
                            fontSize = 12.sp,
                            color = LC.Faint,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    TimingPane(
                        vm = vm,
                        t = t,
                        isCurrent = isCurrent,
                        playing = p.isPlaying,
                        lines = lines,
                        target = target,
                        onTarget = { target = it },
                        onStamp = {
                            if (lines.isNotEmpty()) {
                                val at = (vm.position() - 120).coerceAtLeast(0)
                                lines[target] = lines[target].copy(time = at)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (target < lines.lastIndex) target++
                            }
                        },
                    )
                }
            }
        }

        // ---- поиск в LRCLIB ----
        AnimatedVisibility(searchOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            LrclibSearch(vm, "${t.artist} ${t.title}", onPick = { picked ->
                text = picked
                searchOpen = false
                if (stampRe.containsMatchIn(picked)) {
                    lines.clear(); lines.addAll(parseLines(picked)); target = 0
                }
            }, onClose = { searchOpen = false })
        }

        // ---- выход без сохранения ----
        AnimatedVisibility(confirmClose, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) { confirmClose = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(LT.sheetBrush())
                        .border(1.dp, LC.Border, RoundedCornerShape(28.dp))
                        .clickable(remember { MutableInteractionSource() }, null) {}
                        .padding(22.dp),
                ) {
                    Text("Сохранить изменения?", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Не сохранять", null, { confirmClose = false; onClose() }, Modifier.weight(1f), primary = false)
                        PillButton("Сохранить", LIcons.Check, { confirmClose = false; save() }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingPane(
    vm: MainViewModel,
    t: TrackEntity,
    isCurrent: Boolean,
    playing: Boolean,
    lines: MutableList<EditLine>,
    target: Int,
    onTarget: (Int) -> Unit,
    onStamp: () -> Unit,
) {
    val accent = LocalAccent.current
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isCurrent, playing) {
        while (true) {
            if (isCurrent) pos = vm.position()
            delay(if (playing) 50 else 300)
        }
    }
    val active = if (!isCurrent) -1 else lines.indexOfLast { it.time != null && it.time <= pos }
    val listState = rememberLazyListState()
    LaunchedEffect(target) {
        if (lines.isNotEmpty()) listState.animateScrollToItem((target - 3).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Сначала добавь текст во вкладке «Текст»", fontSize = 15.sp, color = LC.Dim)
            }
            return@Column
        }
        if (!isCurrent) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.14f))
                    .clickable { vm.playForEditor(t) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Play, null, Modifier.size(20.dp), tint = accent)
                Spacer(Modifier.width(10.dp))
                Text("Включи этот трек, чтобы расставлять тайминги", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            itemsIndexed(lines) { i, l ->
                val isTarget = i == target
                val bg by animateColorAsState(
                    when {
                        isTarget -> accent.copy(alpha = 0.18f)
                        i == active -> Color.White.copy(alpha = 0.06f)
                        else -> Color.Transparent
                    },
                    tween(200),
                    label = "lineBg",
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .clickable { onTarget(i) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            l.time?.let { fmtTime(it) } ?: "—:—",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = if (l.time != null) accent else LC.Faint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = l.time != null && isCurrent) {
                                    // Проверить: отмотать чуть раньше строки и послушать.
                                    vm.seekAndPlay((l.time!! - 1500).coerceAtLeast(0))
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            l.text.ifBlank { "♪" },
                            fontSize = 16.sp,
                            fontWeight = if (i == active) FontWeight.Bold else FontWeight.Medium,
                            color = if (i == active) Color.White else LC.Text.copy(alpha = if (l.time != null) 0.95f else 0.7f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AnimatedVisibility(isTarget && l.time != null) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(-500L to "−0,5", -100L to "−0,1", 100L to "+0,1", 500L to "+0,5").forEach { (d, lbl) ->
                                LChip(lbl, false) { lines[i] = l.copy(time = ((l.time ?: 0) + d).coerceAtLeast(0)) }
                            }
                            LChip("Сброс", false) { lines[i] = l.copy(time = null) }
                        }
                    }
                }
            }
        }

        // ---- управление ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(LT.sheetBrush())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(LIcons.Back, "Назад 3 с", { vm.seekBy(-3000) }, size = 44.dp, iconSize = 18.dp)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(enabled = isCurrent) { vm.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (playing && isCurrent) LIcons.Pause else LIcons.Play, null, Modifier.size(24.dp), tint = Color.White) }
                Spacer(Modifier.width(8.dp))
                IconBtn(LIcons.Back, "Вперёд 3 с", { vm.seekBy(3000) }, size = 44.dp, iconSize = 18.dp, modifier = Modifier.graphicsLayerRotate())
                Spacer(Modifier.width(12.dp))
                Text(fmtTime(pos), fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${lines.count { it.time != null }}/${lines.size}", fontSize = 13.sp, color = LC.Dim)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isCurrent) accent else Color.White.copy(alpha = 0.1f))
                    .clickable(enabled = isCurrent, onClick = onStamp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Отметить строку", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = if (isCurrent) LC.OnAccent else LC.Dim)
                    Text(
                        lines.getOrNull(target)?.text?.ifBlank { "♪" }.orEmpty(),
                        fontSize = 12.sp,
                        color = (if (isCurrent) LC.OnAccent else LC.Dim).copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LChip("↶ Отменить", false) {
                    val prev = (target - 1).coerceAtLeast(0)
                    lines[prev] = lines[prev].copy(time = null)
                    onTarget(prev)
                }
                LChip("Всё раньше 0,2 с", false) { for (i in lines.indices) lines[i].time?.let { lines[i] = lines[i].copy(time = (it - 200).coerceAtLeast(0)) } }
                LChip("Всё позже 0,2 с", false) { for (i in lines.indices) lines[i].time?.let { lines[i] = lines[i].copy(time = it + 200) } }
                LChip("Начать заново", false) {
                    for (i in lines.indices) lines[i] = lines[i].copy(time = null)
                    onTarget(0)
                    if (isCurrent) vm.seekAndPlay(0)
                }
            }
        }
    }
}

private fun Modifier.graphicsLayerRotate(): Modifier = this.graphicsLayer { rotationZ = 180f }

@Composable
private fun LrclibSearch(vm: MainViewModel, initial: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<LyricsRepository.Candidate>>(emptyList()) }
    fun run() {
        scope.launch {
            busy = true
            err = null
            runCatching { vm.searchLyricsOnline(q) }
                .onSuccess { results = it; if (it.isEmpty()) err = "Ничего не нашлось — попробуй короче" }
                .onFailure { err = "Нет связи с LRCLIB" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { run() }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxSize(0.85f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(LT.sheetBrush())
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        ) {
            Text("Найти текст", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, LC.Border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        q,
                        { q = it },
                        singleLine = true,
                        textStyle = TextStyle(color = LC.Text, fontSize = 15.sp),
                        cursorBrush = SolidColor(LocalAccent.current),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                PillButton("Найти", LIcons.Search, { run() })
            }
            Spacer(Modifier.height(8.dp))
            if (busy) CircularProgressIndicator(Modifier.size(22.dp).align(Alignment.CenterHorizontally), color = LocalAccent.current, strokeWidth = 2.dp)
            err?.let { Text(it, fontSize = 14.sp, color = LC.Dim, modifier = Modifier.padding(8.dp)) }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(results) { _, c ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick((c.synced ?: c.plain).orEmpty()) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.synced != null) {
                                Text(
                                    "синхр.",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LocalAccent.current,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LocalAccent.current.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            listOf(c.artist, c.album, if (c.durationSec > 0) "%d:%02d".format(c.durationSec / 60, c.durationSec % 60) else null)
                                .filter { !it.isNullOrBlank() }.joinToString(" · "),
                            fontSize = 12.sp,
                            color = LC.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (c.plain ?: c.synced.orEmpty().replace(stampRe, "")).lines().filter { it.isNotBlank() }.take(2).joinToString(" / "),
                            fontSize = 12.sp,
                            color = LC.Faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

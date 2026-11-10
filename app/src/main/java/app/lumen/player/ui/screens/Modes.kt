package app.lumen.player.ui.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.playback.AlarmConfig
import app.lumen.player.playback.LumenAlarm
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LChip
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.RollingText
import app.lumen.player.ui.components.rememberArtwork
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================= автомобильный режим =================

/** Экран не гаснет, пока этот элемент на экране. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun clockText(pattern: String): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val f = remember(pattern) { SimpleDateFormat(pattern, Locale("ru")) }
    return f.format(Date(now))
}

/**
 * В машине: огромные кнопки, крупный текст, смахивание влево-вправо — следующий/предыдущий трек.
 * Экран не гаснет.
 */
@Composable
fun CarModeScreen(vm: MainViewModel, onExit: () -> Unit) {
    val p by vm.player.collectAsStateWithLifecycle()
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    KeepScreenOn()
    BackHandler(onBack = onExit)
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(p.currentId, p.isPlaying) {
        while (true) {
            val d = p.durationMs
            progress = if (d > 0) (vm.position().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    val art = rememberArtwork(track?.uri, 128)
    var drag by remember { mutableFloatStateOf(0f) }
    val shift by animateFloatAsState(drag, tween(120), label = "carDrag")
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050507))
            .clickable(remember { MutableInteractionSource() }, null) {}
            .pointerInput(Unit) {
                val limit = with(density) { 110.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -limit) vm.next() else if (drag > limit) vm.previous()
                        if (kotlin.math.abs(drag) > limit) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { ch, dx ->
                    ch.consume()
                    drag += dx
                }
            },
    ) {
        if (art != null) {
            Image(
                art,
                null,
                Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = 0.35f },
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(onClick = onExit)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LIcons.Close, null, Modifier.size(22.dp), tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("Выйти", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(clockText("HH:mm"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.graphicsLayer { translationX = shift * 0.35f; alpha = 1f - (kotlin.math.abs(shift) / 900f).coerceIn(0f, 0.6f) }) {
                AnimatedContent(
                    track?.uri,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                    label = "carTitle",
                ) { _ ->
                    Column {
                        Text(
                            track?.title ?: "Ничего не играет",
                            fontSize = 40.sp,
                            lineHeight = 46.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            track?.artist.orEmpty(),
                            fontSize = 24.sp,
                            color = LC.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(CircleShape).background(accent))
            }
            Spacer(Modifier.height(34.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CarButton(LIcons.Prev, "Предыдущий", 104.dp, Color.White.copy(alpha = 0.12f), Color.White) { vm.previous() }
                CarButton(if (p.isPlaying) LIcons.Pause else LIcons.Play, if (p.isPlaying) "Пауза" else "Играть", 148.dp, accent, LC.OnAccent) { vm.toggle() }
                CarButton(LIcons.Next, "Следующий", 104.dp, Color.White.copy(alpha = 0.12f), Color.White) { vm.next() }
            }
            Spacer(Modifier.height(26.dp))
            val fav = track?.uri?.let { it in favs } == true
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CarButton(if (fav) LIcons.Heart else LIcons.HeartOutline, "В любимое", 76.dp, Color.White.copy(alpha = 0.08f), if (fav) accent else Color.White) {
                    track?.let { vm.toggleFavorite(it.uri) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Смахни влево — следующий трек, вправо — предыдущий",
                fontSize = 14.sp,
                color = LC.Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CarButton(icon: ImageVector, desc: String, size: Dp, bg: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.42f), tint = tint) }
}

// ================= ночные часы =================

/** Идёт ли зарядка (обновляется при подключении и отключении питания). */
@Composable
fun rememberCharging(): Boolean {
    val ctx = LocalContext.current
    var charging by remember { mutableStateOf(isCharging(ctx, null)) }
    DisposableEffect(Unit) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { charging = isCharging(c, i) }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return charging
}

private fun isCharging(ctx: Context, intent: Intent?): Boolean {
    if (intent?.action == Intent.ACTION_POWER_CONNECTED) return true
    if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) return false
    val st = (intent ?: ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
}

private fun batteryPercent(ctx: Context): Int =
    ctx.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

/**
 * Ночные часы: телефон на зарядке, никто его не трогает минуту — экран превращается
 * в тусклые крупные часы с тем, что играет. Часы чуть смещаются каждую минуту, чтобы не «выжечь» экран.
 */
@Composable
fun NightClock(vm: MainViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    val p by vm.player.collectAsStateWithLifecycle()
    BackHandler(onBack = onExit)
    KeepScreenOn()
    val time = clockText("HH:mm")
    val date = clockText("EEEE, d MMMM")
    var dx by remember { mutableIntStateOf(0) }
    var dy by remember { mutableIntStateOf(0) }
    var battery by remember { mutableIntStateOf(batteryPercent(ctx)) }
    LaunchedEffect(time) {
        dx = (-28..28).random()
        dy = (-40..40).random()
        battery = batteryPercent(ctx)
    }
    val ox by animateFloatAsState(dx.toFloat(), tween(4000), label = "ncx")
    val oy by animateFloatAsState(dy.toFloat(), tween(4000), label = "ncy")
    val hour = remember(time) { time.substringBefore(':').toIntOrNull() ?: 12 }
    // Глубокой ночью — тёплый тусклый цвет, чтобы не слепить.
    val ink = if (hour >= 22 || hour < 7) Color(0xFFFFB38A) else Color.White
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onExit),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset(ox.dp, oy.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RollingText(time, 104.sp, ink.copy(alpha = 0.55f), fontWeight = FontWeight.Light)
            Text(date.replaceFirstChar { it.uppercase() }, fontSize = 18.sp, color = ink.copy(alpha = 0.35f))
            Spacer(Modifier.height(26.dp))
            if (track != null) {
                Text(
                    "${if (p.isPlaying) "♪ " else "❚❚ "}${track?.title} — ${track?.artist}",
                    fontSize = 15.sp,
                    color = ink.copy(alpha = 0.3f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
            if (battery >= 0) {
                Spacer(Modifier.height(8.dp))
                Text("Заряд $battery%", fontSize = 13.sp, color = ink.copy(alpha = 0.25f))
            }
        }
    }
}

// ================= будильник и Android Auto в настройках =================

private val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@Composable
fun AlarmSection(vm: MainViewModel) {
    val c by vm.alarm.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val smart by vm.smartRows.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    fun upd(n: AlarmConfig) = vm.setAlarm(n)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Музыкальный будильник", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(alarmSubtitle(c), fontSize = 13.sp, color = LC.Dim)
        }
        androidx.compose.material3.Switch(
            checked = c.enabled,
            onCheckedChange = { upd(c.copy(enabled = it)) },
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
    Spacer(Modifier.height(14.dp))
    // Время: крупные цифры и кнопки ±.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Stepper({ upd(c.copy(hour = (c.hour + 23) % 24)) }, { upd(c.copy(hour = (c.hour + 1) % 24)) }) {
            RollingText("%02d".format(c.hour), 48.sp, if (c.enabled) LC.Text else LC.Dim, fontWeight = FontWeight.Bold)
        }
        Text(":", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = LC.Dim, modifier = Modifier.padding(horizontal = 4.dp))
        Stepper({ upd(c.copy(minute = (c.minute + 55) % 60)) }, { upd(c.copy(minute = (c.minute + 5) % 60)) }) {
            RollingText("%02d".format(c.minute), 48.sp, if (c.enabled) LC.Text else LC.Dim, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DAY_NAMES.forEachIndexed { i, d ->
            val on = c.days and (1 shl i) != 0
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (on) accent else Color.White.copy(alpha = 0.07f))
                    .clickable { upd(c.copy(days = c.days xor (1 shl i))) },
                contentAlignment = Alignment.Center,
            ) { Text(d, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) LC.OnAccent else LC.Text) }
        }
    }
    Text(
        if (c.days == 0) "Дни не выбраны — прозвенит один раз" else "Повторяется по отмеченным дням",
        fontSize = 12.sp,
        color = LC.Faint,
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text("Что играть", fontSize = 13.sp, color = LC.Dim, modifier = Modifier.padding(bottom = 6.dp))
    val sources = buildList {
        add("play:loved" to "Самое любимое")
        add("play:favshuffle" to "Любимое вперемешку")
        add("play:forgotten" to "Обратная сторона")
        smart.forEach { add("sp:${it.id}" to it.name) }
        playlists.forEach { add("pl:${it.id}" to it.name) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { (id, name) ->
            // Внутри плейлиста — начинаем с первого трека.
            val src = if (id.startsWith("pl:") || id.startsWith("sp:")) "ctx:$id\u001F" else id
            LChip(name, c.source == src) { upd(c.copy(source = src, sourceName = name)) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Громкость нарастает", fontSize = 13.sp, color = LC.Dim, modifier = Modifier.padding(bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30 to "30 с", 60 to "1 мин", 180 to "3 мин", 300 to "5 мин").forEach { (s, l) ->
            LChip(l, c.rampSec == s) { upd(c.copy(rampSec = s)) }
        }
    }
    if (c.enabled && !LumenAlarm.canSchedule(ctx) && Build.VERSION.SDK_INT >= 31) {
        Spacer(Modifier.height(12.dp))
        Text("Чтобы будильник звонил минута в минуту, разреши точные будильники.", fontSize = 13.sp, color = Color(0xFFFFB38A))
        Spacer(Modifier.height(6.dp))
        PillButton("Разрешить", null, {
            runCatching {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }, Modifier.fillMaxWidth(), primary = false)
    }
    Spacer(Modifier.height(6.dp))
    Text("Громкость медиа поднимется минимум до половины, чтобы не проспать.", fontSize = 12.sp, color = LC.Faint)
}

fun alarmSubtitle(c: AlarmConfig): String {
    val at = LumenAlarm.nextAt(c) ?: return "Выключен"
    val left = (at - System.currentTimeMillis()) / 60_000
    val h = left / 60
    val m = left % 60
    return "Прозвенит через " + (if (h > 0) "$h ч " else "") + "$m мин"
}

@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconBtn(LIcons.ArrowUp, "Больше", onPlus, size = 40.dp, iconSize = 18.dp)
        Box(Modifier.padding(vertical = 4.dp)) { content() }
        IconBtn(LIcons.ArrowDown, "Меньше", onMinus, size = 40.dp, iconSize = 18.dp)
    }
}

/** Как включить Lumen в Android Auto, если он установлен не из Google Play. */
@Composable
fun AutoSection(onCarMode: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text("В машине", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text(
        "Android Auto: Lumen появится среди музыкальных приложений — вкладки «Для тебя», «Плейлисты», «Альбомы», «Исполнители» и голосовой поиск.",
        fontSize = 13.sp,
        color = LC.Dim,
    )
    Spacer(Modifier.height(12.dp))
    PillButton("Автомобильный режим", LIcons.Play, onCarMode, Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    Text("Если Android Auto нет — огромные кнопки прямо в телефоне, экран не гаснет.", fontSize = 12.sp, color = LC.Faint)
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Lumen не виден в Android Auto?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current, modifier = Modifier.weight(1f))
        Icon(LIcons.Down, null, Modifier.size(18.dp).graphicsLayer { rotationZ = if (open) 180f else 0f }, tint = LC.Dim)
    }
    androidx.compose.animation.AnimatedVisibility(open) {
        Column {
            listOf(
                "Открой приложение Android Auto на телефоне (Настройки → Подключённые устройства → Android Auto).",
                "Пролистай вниз и 10 раз нажми на «Версия» — включится режим разработчика.",
                "Нажми ⋮ в правом верхнем углу → «Настройки для разработчиков».",
                "Включи «Неизвестные источники» (Unknown sources).",
                "Переподключи телефон к машине. В списке приложений Auto выбери, какие показывать, — включи Lumen.",
            ).forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text("${i + 1}.", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LocalAccent.current, modifier = Modifier.width(22.dp))
                    Text(step, fontSize = 14.sp)
                }
            }
            Text(
                "Это нужно один раз: Android Auto по умолчанию показывает только приложения из Google Play.",
                fontSize = 12.sp,
                color = LC.Faint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ================= фоновая работа =================

/**
 * Помощник «чтобы музыку не выключало»: оптимизация батареи и автозапуск (на Xiaomi/Redmi/POCO
 * без этого система иногда закрывает плеер в фоне).
 */
@Composable
fun BackgroundSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    val ignoring = remember(tick) { pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true }
    val xiaomi = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
    // Вернулись из настроек — перепроверяем.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    Text("Работа в фоне", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text("Чтобы система не выключала музыку при погашенном экране.", fontSize = 13.sp, color = LC.Dim)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ignoring) LIcons.Check else LIcons.Info, null, Modifier.size(20.dp), tint = if (ignoring) LocalAccent.current else Color(0xFFFFB38A))
        Spacer(Modifier.width(10.dp))
        Text(
            if (ignoring) "Экономия батареи не мешает Lumen" else "Экономия батареи может останавливать музыку",
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
    }
    if (!ignoring) {
        Spacer(Modifier.height(8.dp))
        PillButton("Разрешить работу в фоне", null, {
            open(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}")))
                || open(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, Modifier.fillMaxWidth())
    }
    if (xiaomi) {
        Spacer(Modifier.height(10.dp))
        Text("На Xiaomi, Redmi и POCO ещё включи «Автозапуск» и в «Контроле активности» выбери «Нет ограничений».", fontSize = 13.sp, color = LC.Dim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Автозапуск", null, {
                open(Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                    || open(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
            }, Modifier.weight(1f), primary = false)
            PillButton("Батарея", null, {
                open(
                    Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                        .putExtra("package_name", ctx.packageName)
                        .putExtra("package_label", "Lumen")
                ) || open(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
            }, Modifier.weight(1f), primary = false)
        }
    }
}

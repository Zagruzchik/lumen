package app.lumen.player.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.lumen.player.data.SharePeer
import app.lumen.player.data.ShareUi
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LT
import app.lumen.player.ui.theme.LocalAccent
import kotlin.math.roundToInt

/** Разрешения для поиска телефонов рядом. */
fun nearbyPermissions(): Array<String> = when {
    // Сервисы Google для поиска рядом требуют геолокацию и на новых Android тоже.
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    Build.VERSION.SDK_INT >= 31 -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
}

/**
 * Окно «Поделиться» / «Получить»: докачка недостающего, радар поиска, запрос на приём,
 * прогресс передачи и итог. Работает поверх любого экрана.
 */
@Composable
fun ShareSheet(
    vm: MainViewModel,
    st: ShareUi,
    onClose: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    sheetModifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val busy = st is ShareUi.Transfer || st is ShareUi.Connecting

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) vm.shareGo()
        else vm.shareFail(
            "Без разрешений «Устройства поблизости» и «Геолокация» (точная) Lumen не найдёт второй телефон. " +
                "Разреши их в «О приложении» → «Разрешения» и попробуй снова"
        )
    }
    // Как только окно перешло к поиску — проверяем разрешения и стартуем.
    val searching = st is ShareUi.Searching
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        val need = nearbyPermissions()
        if (need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) vm.shareGo()
        else perms.launch(need)
    }

    BackHandler { if (!busy) onClose() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, null) { if (!busy && st !is ShareUi.Incoming) onClose() }
        )
        Column(
            sheetModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp)
                .navigationBarsPadding()
                .clip(RoundedCornerShape(32.dp))
                .background(LT.sheetBrush())
                .border(1.dp, LC.Border, RoundedCornerShape(32.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                st,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 60)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = 380f), initialScale = 0.96f)) togetherWith
                        fadeOut(tween(140))
                },
                label = "share",
            ) { s ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (s) {
                        is ShareUi.Preparing -> Preparing(s, accent) { vm.shareCancel() }
                        is ShareUi.Searching -> Searching(s, accent, vm, onClose)
                        is ShareUi.Incoming -> Incoming(s, accent, vm)
                        is ShareUi.Connecting -> {
                            Radar(accent, fast = true)
                            Title("Соединяюсь с ${s.peer}")
                            Sub("Переходим на Wi‑Fi — так файлы летят быстрее")
                            Spacer(Modifier.height(16.dp))
                            PillButton("Отмена", null, { vm.shareCancel() }, Modifier.fillMaxWidth(), primary = false)
                        }
                        is ShareUi.Transfer -> Transfer(s, accent) { vm.shareCancel() }
                        is ShareUi.Done -> {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.size(84.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(LIcons.Check, null, Modifier.size(40.dp), tint = accent) }
                            Spacer(Modifier.height(14.dp))
                            Title(
                                if (s.sending) "Отправлено" else "Получено"
                            )
                            Sub(
                                buildString {
                                    append(pluralRu(s.count, "трек", "трека", "треков"))
                                    if (s.skipped > 0) append(" · ${s.skipped} уже были у получателя")
                                }
                            )
                            Spacer(Modifier.height(16.dp))
                            val pl = s.playlistId
                            if (pl != null) {
                                PillButton("Открыть «${s.title}»", LIcons.Playlist, { onClose(); onOpenPlaylist(pl) }, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(10.dp))
                                PillButton("Готово", null, onClose, Modifier.fillMaxWidth(), primary = false)
                            } else {
                                PillButton("Готово", null, onClose, Modifier.fillMaxWidth())
                            }
                        }
                        is ShareUi.Failed -> {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.size(72.dp).clip(CircleShape).background(Color(0x33FF6B6B)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(LIcons.Info, null, Modifier.size(34.dp), tint = Color(0xFFFF8A8A)) }
                            Spacer(Modifier.height(14.dp))
                            Title("Не получилось")
                            Sub(s.message)
                            Spacer(Modifier.height(16.dp))
                            PillButton("Закрыть", null, onClose, Modifier.fillMaxWidth())
                        }
                        ShareUi.Idle -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
}

@Composable
private fun Sub(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, fontSize = 14.sp, color = LC.Dim, textAlign = TextAlign.Center, lineHeight = 19.sp)
}

@Composable
private fun Preparing(s: ShareUi.Preparing, accent: Color, onCancel: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Ring((s.done + s.progress) / s.total.coerceAtLeast(1), accent)
    Spacer(Modifier.height(14.dp))
    Title("Докачиваю недостающее")
    Sub("${s.done + 1} из ${s.total}: «${s.current}»\nКогда всё будет на телефоне — начнём отправку")
    Spacer(Modifier.height(16.dp))
    PillButton("Отмена", null, onCancel, Modifier.fillMaxWidth(), primary = false)
}

@Composable
private fun Searching(s: ShareUi.Searching, accent: Color, vm: MainViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Radar(accent, fast = false)
    if (s.sending) {
        Title("Поднеси телефоны друг к другу")
        Sub(
            "Отправляю ${if (s.count == 1) "«${s.title}»" else pluralRu(s.count, "трек", "трека", "треков")}.\n" +
                "На втором телефоне: Lumen → Плейлисты → «Получить»"
        )
        if (s.peers.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.peers.forEach { p -> PeerRow(p, accent, single = s.peers.size == 1) { vm.shareConnect(p) } }
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            "Другим способом (Quick Share, Mi Share…)",
            LIcons.Share,
            {
                val uris = ArrayList(vm.shareUris())
                if (uris.isNotEmpty()) {
                    val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                        .setType("audio/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    runCatching { ctx.startActivity(Intent.createChooser(i, "Отправить треки")) }
                    onClose()
                }
            },
            Modifier.fillMaxWidth(),
            primary = false,
        )
        Spacer(Modifier.height(10.dp))
        PillButton("Отмена", null, onClose, Modifier.fillMaxWidth(), primary = false)
    } else {
        Title("Жду отправителя")
        Sub("Поднеси телефоны друг к другу и нажми «Поделиться» на втором.\nЭтот телефон виден как «${s.me}»")
        Spacer(Modifier.height(16.dp))
        PillButton("Отмена", null, onClose, Modifier.fillMaxWidth(), primary = false)
    }
}

@Composable
private fun PeerRow(p: SharePeer, accent: Color, single: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(LIcons.Person, null, Modifier.size(20.dp), tint = LC.OnAccent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (single) "Соединяюсь сам…" else "Нажми, чтобы отправить", fontSize = 12.sp, color = LC.Dim)
        }
    }
}

@Composable
private fun Incoming(s: ShareUi.Incoming, accent: Color, vm: MainViewModel) {
    Radar(accent, fast = true)
    Title("${s.peer.name} хочет отправить")
    Sub(s.summary.ifBlank { "музыку" })
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Отклонить", null, { vm.shareReject() }, Modifier.weight(1f), primary = false)
        PillButton("Принять", LIcons.Download, { vm.shareAccept() }, Modifier.weight(1f))
    }
}

@Composable
private fun Transfer(s: ShareUi.Transfer, accent: Color, onCancel: () -> Unit) {
    val frac = if (s.total > 0) (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) else 0f
    Spacer(Modifier.height(6.dp))
    Ring(frac, accent)
    Spacer(Modifier.height(14.dp))
    Title(if (s.sending) "Отправляю на ${s.peer}" else "Получаю от ${s.peer}")
    Sub(
        buildString {
            if (s.count == 0 && !s.sending) append("Сверяю, что уже есть на телефоне…")
            else {
                append("${s.done} из ${pluralRu(s.count, "трека", "треков", "треков")}")
                if (s.total > 0) append(" · ${humanSize(s.bytes)} из ${humanSize(s.total)}")
            }
            if (s.skipped > 0) append("\n${s.skipped} уже есть — не передаю повторно")
        }
    )
    Spacer(Modifier.height(16.dp))
    PillButton("Отменить", null, onCancel, Modifier.fillMaxWidth(), primary = false)
}

/** Кольцо прогресса с процентами. */
@Composable
private fun Ring(fraction: Float, accent: Color) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 1f, stiffness = 120f), label = "ring")
    val track = LC.ink(0x1F)
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 9.dp.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(w))
            drawArc(accent, -90f, 360f * f, false, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("${(f * 100).roundToInt()}%", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Радар: волны расходятся от телефона, пока идёт поиск. */
@Composable
private fun Radar(accent: Color, fast: Boolean) {
    val t by rememberInfiniteTransition(label = "radar").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(if (fast) 1300 else 2400, easing = LinearEasing)),
        label = "radarT",
    )
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2
            for (k in 0 until 3) {
                val p = (t + k / 3f) % 1f
                drawCircle(accent.copy(alpha = 0.55f * (1f - p)), radius = maxR * (0.3f + 0.7f * p), style = Stroke(2.dp.toPx()))
            }
            drawCircle(accent.copy(alpha = 0.16f), radius = maxR * 0.3f)
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(LIcons.Share, null, Modifier.size(24.dp), tint = LC.OnAccent)
        }
    }
    Spacer(Modifier.height(10.dp))
}

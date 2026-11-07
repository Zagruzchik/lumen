package app.lumen.player.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.Release
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.UpdateUi
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.theme.LocalAccent
import app.lumen.player.ui.components.PillButton
import kotlin.math.roundToInt

/** Размер файла по-человечески. */
fun humanSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 * 1024 -> "${(b / 1024f).roundToInt()} КБ"
    else -> "%.1f МБ".format(b / 1024f / 1024f)
}

/**
 * Карточка обновления поверх приложения: что нового, загрузка с живым прогрессом,
 * при необходимости — переход к разрешению «Установка неизвестных приложений».
 */
@Composable
fun UpdateSheet(vm: MainViewModel, onClose: () -> Unit, sheetModifier: Modifier = Modifier) {
    val st by vm.updateState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val busy = st is UpdateUi.Downloading || st is UpdateUi.Installing

    BackHandler { if (st is UpdateUi.Downloading) vm.cancelUpdate() else onClose() }

    val rel: Release? = when (val s = st) {
        is UpdateUi.Available -> s.release
        is UpdateUi.Downloading -> s.release
        is UpdateUi.NeedPermission -> s.release
        is UpdateUi.Installing -> s.release
        is UpdateUi.Error -> s.release
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, null) { if (!busy) onClose() }
        )
        Column(
            sheetModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp)
                .navigationBarsPadding()
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF241D2E), Color(0xFF15121B))))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(32.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UpdateOrb(accent, st is UpdateUi.Downloading || st is UpdateUi.Installing)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Вышло обновление", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        buildString {
                            append("${vm.versionName} → ${rel?.versionName ?: "…"}")
                            rel?.sizeBytes?.let { humanSize(it) }?.takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                        },
                        fontSize = 14.sp,
                        color = LC.Dim,
                    )
                }
            }

            val notes = rel?.notes?.trim().orEmpty()
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Что нового", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x14FFFFFF))
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Text(notes, fontSize = 15.sp, lineHeight = 21.sp, color = LC.Text.copy(alpha = 0.9f))
                }
            }

            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = st::class,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                label = "updBottom",
            ) { _ ->
                when (val s = st) {
                    is UpdateUi.Downloading -> Column {
                        val p by animateFloatAsState(s.progress, tween(300, easing = FastOutSlowInEasing), label = "dl")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Загружаю…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${(p * 100).roundToInt()}%", fontSize = 15.sp, color = accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        ProgressTrack(p, accent)
                        Spacer(Modifier.height(14.dp))
                        PillButton("Отменить", LIcons.Close, { vm.cancelUpdate() }, Modifier.fillMaxWidth(), primary = false)
                    }
                    is UpdateUi.Installing -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = accent, strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Передаю установщику — подтверди в окне Android", fontSize = 15.sp, color = LC.Dim)
                    }
                    is UpdateUi.NeedPermission -> Column {
                        Text(
                            "Обновление скачано. Разреши Lumen устанавливать обновления — один раз, дальше всё само.",
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            color = LC.Dim,
                        )
                        Spacer(Modifier.height(14.dp))
                        PillButton("Открыть разрешение", LIcons.Sliders, {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }, Modifier.fillMaxWidth())
                    }
                    is UpdateUi.Error -> Column {
                        Text(s.message, fontSize = 15.sp, color = Color(0xFFFF8A8A))
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("Позже", LIcons.Close, onClose, Modifier.weight(1f), primary = false)
                            if (s.release != null) PillButton("Повторить", LIcons.Play, { vm.startUpdate() }, Modifier.weight(1f))
                        }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Позже", LIcons.Close, onClose, Modifier.weight(1f), primary = false)
                        PillButton("Обновить", LIcons.Sparkle, { vm.startUpdate() }, Modifier.weight(1.4f))
                    }
                }
            }
        }
    }
}

/** Светящийся шар с медленно бегущим кольцом; во время загрузки кольцо крутится быстрее. */
@Composable
private fun UpdateOrb(accent: Color, active: Boolean) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 1400 else 5000, easing = LinearEasing)), label = "spin")
    Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), center, r))
            drawCircle(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)), Offset.Zero, Offset(size.width, size.height)), r * 0.72f)
            drawArc(
                Color.White.copy(alpha = 0.85f),
                startAngle = spin,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(center.x - r * 0.9f, center.y - r * 0.9f),
                size = androidx.compose.ui.geometry.Size(r * 1.8f, r * 1.8f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Icon(LIcons.Sparkle, null, Modifier.size(24.dp), tint = Color.White)
    }
}

/** Полоса загрузки с мягким бликом. */
@Composable
private fun ProgressTrack(p: Float, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(Color(0x1FFFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(p.coerceIn(0.02f, 1f))
                .height(10.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.7f), accent, Color.White.copy(alpha = 0.9f))))
        )
    }
}

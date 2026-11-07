package app.lumen.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlin.math.roundToInt

/**
 * Быстрое меню плейлистов на удержании лайка.
 * Зажал — меню выросло над кнопкой; ведёшь палец по плейлистам — они подсвечиваются;
 * отпустил над плейлистом — трек добавлен. Отпустил мимо — меню остаётся, можно тапнуть.
 */
class QuickMenuState {
    var open by mutableStateOf(false)
    var anchor by mutableStateOf(Rect.Zero)
    var uri by mutableStateOf<String?>(null)
    var hovered by mutableIntStateOf(-1)
    /** Прямоугольники пунктов в координатах окна: −1 — «Новый плейлист», дальше индексы плейлистов. */
    val rects = mutableStateMapOf<Int, Rect>()

    fun hit(p: Offset): Int = rects.entries.firstOrNull { it.value.contains(p) }?.key ?: NONE

    fun close() {
        open = false
        hovered = NONE
    }

    companion object {
        const val NONE = -100
        const val NEW = -1
    }
}

/**
 * Жест для кнопки лайка: короткий тап — лайк, удержание — меню плейлистов с выбором одним движением.
 */
fun Modifier.likeWithPlaylists(
    state: QuickMenuState,
    uri: () -> String?,
    coords: () -> LayoutCoordinates?,
    onTap: () -> Unit,
    onPick: (Int) -> Unit,
    onLongPressHaptic: () -> Unit,
    onHoverHaptic: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        var released = false
        var cancelled = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            if (waitForUpOrCancellation() == null) cancelled = true else released = true
        }
        if (released) {
            onTap()
            return@awaitEachGesture
        }
        if (cancelled) return@awaitEachGesture
        val c = coords() ?: return@awaitEachGesture
        state.uri = uri() ?: return@awaitEachGesture
        state.anchor = c.boundsInWindow()
        state.rects.clear()
        state.hovered = QuickMenuState.NONE
        state.open = true
        onLongPressHaptic()
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull() ?: break
            ch.consume()
            val pos = c.localToWindow(ch.position)
            val h = state.hit(pos)
            if (h != state.hovered) {
                state.hovered = h
                if (h != QuickMenuState.NONE) onHoverHaptic()
            }
            if (!ch.pressed) break
        }
        val picked = state.hovered
        state.hovered = QuickMenuState.NONE
        if (picked != QuickMenuState.NONE) {
            onPick(picked)
            state.close()
        }
    }
}

/** Само меню: стеклянная карточка над кнопкой, рисуется поверх всего экрана. */
@Composable
fun QuickPlaylistMenu(
    state: QuickMenuState,
    names: List<Pair<String, Int>>,
    onPick: (Int) -> Unit,
) {
    if (!state.open) return
    val accent = LocalAccent.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var cardH by remember { mutableIntStateOf(0) }
    var cardW by remember { mutableIntStateOf(0) }
    val appear by animateFloatAsState(if (cardH > 0) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = 420f), label = "qm")
    BackHandler { state.close() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f * appear))
            .clickable(remember { MutableInteractionSource() }, null) { state.close() }
    ) {
        val gap = with(density) { 12.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        Column(
            Modifier
                .offset {
                    val x = (state.anchor.right - cardW).coerceAtLeast(margin)
                    val y = (state.anchor.top - cardH - gap).coerceAtLeast(margin)
                    IntOffset(x.roundToInt(), y.roundToInt())
                }
                .onSizeChanged { cardH = it.height; cardW = it.width }
                .graphicsLayer {
                    alpha = appear
                    val s = 0.85f + 0.15f * appear
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
                }
                .width(250.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(app.lumen.player.ui.theme.LT.sheetBrush())
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(24.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(vertical = 8.dp),
        ) {
            Text(
                "Добавить в плейлист",
                fontSize = 12.sp,
                color = LC.Dim,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            names.forEachIndexed { i, (name, count) ->
                MenuItem(state, i, name, "$count", accent) { onPick(i); state.close(); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            }
            MenuItem(state, QuickMenuState.NEW, "Новый плейлист", null, accent, isNew = true) { onPick(QuickMenuState.NEW); state.close() }
        }
    }
}

@Composable
private fun MenuItem(
    state: QuickMenuState,
    key: Int,
    title: String,
    trailing: String?,
    accent: Color,
    isNew: Boolean = false,
    onClick: () -> Unit,
) {
    val hot = state.hovered == key
    val k by animateFloatAsState(if (hot) 1f else 0f, tween(140), label = "hot")
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { state.rects[key] = it.boundsInWindow() }
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.28f * k))
            .clickable(onClick = onClick)
            .graphicsLayer {
                val s = 1f + 0.04f * k
                scaleX = s
                scaleY = s
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(if (isNew) LIcons.Plus else LIcons.Playlist, null, Modifier.size(20.dp), tint = if (isNew || hot) accent else LC.Text)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = if (hot) FontWeight.Bold else FontWeight.Medium,
            color = if (isNew) accent else LC.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = LC.Dim)
    }
}

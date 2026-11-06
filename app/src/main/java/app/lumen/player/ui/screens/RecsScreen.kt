package app.lumen.player.ui.screens

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.data.RecItem
import app.lumen.player.data.RecSection
import app.lumen.player.data.RemoteImages
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.placeholderBrush
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import java.net.URLEncoder

// ---------- поиск ----------

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Системный поиск по названию (Google/браузер), без него — открываем поиск в браузере. */
fun searchWeb(ctx: Context, query: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        openUrl(ctx, "https://www.google.com/search?q=${enc(query)}")
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(ctx, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show() }
}

// ---------- экран ----------

@Composable
fun RecsScreen(vm: MainViewModel, bottomPad: Dp) {
    val state by vm.recs.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    LaunchedEffect(Unit) { vm.refreshRecs() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top + 16.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Для тебя", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        when {
                            state.loading -> "Подбираю музыку…"
                            state.offline && state.sections.isEmpty() -> "Нужен интернет"
                            state.offline -> "Нет сети — показаны сохранённые"
                            state.updatedAt > 0 -> "Обновлено ${agoText(state.updatedAt)} · по данным Deezer"
                            else -> "По твоей статистике и библиотеке"
                        },
                        fontSize = 13.sp,
                        color = LC.Dim,
                    )
                }
                RefreshButton(state.loading) { vm.refreshRecs(force = true) }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (state.sections.isEmpty()) {
            if (state.loading) {
                items(3) { SkeletonRow() }
            } else {
                item {
                    Column(
                        Modifier
                            .padding(20.dp)
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(24.dp))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(LIcons.Sparkle, null, Modifier.size(40.dp), tint = accent)
                        Spacer(Modifier.height(12.dp))
                        Text("Пока нечего показать", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Рекомендации строятся по исполнителям из твоей библиотеки и статистики. Нужен интернет.",
                            fontSize = 14.sp,
                            color = LC.Dim,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        PillButton("Попробовать снова", LIcons.Refresh, { vm.refreshRecs(force = true) })
                    }
                }
            }
            return@LazyColumn
        }

        state.sections.forEach { section ->
            item(key = "h:" + section.title) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
                    Text(section.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(section.subtitle, fontSize = 13.sp, color = LC.Dim)
                }
            }
            item(key = "r:" + section.title) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(section.items, key = { _, it -> it.key }) { i, rec ->
                        Appear(i) {
                            when (section.style) {
                                RecSection.STYLE_BIG -> BigTile(rec) { vm.hideRec(rec) }
                                RecSection.STYLE_ARTISTS -> ArtistTile(rec) { vm.hideRec(rec) }
                                else -> TrackTile(rec) { vm.hideRec(rec) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun agoText(t: Long): String {
    val min = (System.currentTimeMillis() - t) / 60_000
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 60 * 24 -> "${min / 60} ч назад"
        else -> "${min / (60 * 24)} дн назад"
    }
}

@Composable
private fun RefreshButton(loading: Boolean, onClick: () -> Unit) {
    val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin",
    )
    IconBtn(
        LIcons.Refresh,
        "Обновить рекомендации",
        onClick,
        modifier = Modifier.graphicsLayer { rotationZ = if (loading) spin else 0f },
    )
}

/** Плитки появляются мягко и по очереди. */
@Composable
private fun Appear(index: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 45L).coerceAtMost(400L))
        a.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = a.value
            translationY = (1f - a.value) * 24.dp.toPx()
        }
    ) { content() }
}

@Composable
private fun rememberRemote(url: String): ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    val st = produceState(RemoteImages.peek(url)?.asImageBitmap(), url) {
        value = RemoteImages.load(ctx, url)?.asImageBitmap()
    }
    return st.value
}

@Composable
private fun RemoteCover(url: String, seed: String, shape: Shape, modifier: Modifier) {
    val bmp = rememberRemote(url)
    val shown by animateFloatAsState(if (bmp != null) 1f else 0f, tween(450), label = "img")
    Box(modifier.clip(shape).background(placeholderBrush(seed))) {
        if (bmp != null) {
            Image(
                bmp,
                null,
                Modifier.fillMaxSize().graphicsLayer { alpha = shown },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Общая механика плитки: пружинит на нажатии, тап — поиск, удержание — меню вариантов. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileShell(rec: RecItem, onHide: () -> Unit, content: @Composable (pressScale: Float) -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "tile")
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClickLabel = "Найти «${rec.query}»",
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = true
            },
        ) { searchWeb(ctx, rec.query) }
    ) {
        content(press)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Найти в браузере") }, leadingIcon = { Icon(LIcons.Search, null, Modifier.size(20.dp)) }, onClick = {
                menu = false
                openUrl(ctx, "https://www.google.com/search?q=${enc(rec.query)}")
            })
            DropdownMenuItem(text = { Text("Найти на YouTube") }, leadingIcon = { Icon(LIcons.Play, null, Modifier.size(20.dp)) }, onClick = {
                menu = false
                openUrl(ctx, "https://www.youtube.com/results?search_query=${enc(rec.query)}")
            })
            DropdownMenuItem(text = { Text("Найти в Яндекс Музыке") }, leadingIcon = { Icon(LIcons.Note, null, Modifier.size(20.dp)) }, onClick = {
                menu = false
                openUrl(ctx, "https://music.yandex.ru/search?text=${enc(rec.query)}")
            })
            DropdownMenuItem(text = { Text("Скопировать название") }, leadingIcon = { Icon(LIcons.Check, null, Modifier.size(20.dp)) }, onClick = {
                menu = false
                clipboard.setText(AnnotatedString(rec.query))
                Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
            })
            DropdownMenuItem(text = { Text("Не показывать") }, leadingIcon = { Icon(LIcons.Close, null, Modifier.size(20.dp)) }, onClick = {
                menu = false
                onHide()
            })
        }
    }
}

@Composable
private fun TrackTile(rec: RecItem, onHide: () -> Unit) {
    TileShell(rec, onHide) { press ->
        Column(
            Modifier
                .width(150.dp)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
        ) {
            Box {
                RemoteCover(rec.cover, rec.key, RoundedCornerShape(20.dp), Modifier.size(150.dp))
                Box(
                    Modifier
                        .matchParentSizeCompat(150.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0x99000000))))
                )
                Icon(
                    LIcons.Search,
                    null,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .padding(7.dp),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(rec.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rec.artist, fontSize = 12.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rec.reason, fontSize = 11.sp, color = LocalAccent.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BigTile(rec: RecItem, onHide: () -> Unit) {
    TileShell(rec, onHide) { press ->
        Box(
            Modifier
                .size(width = 240.dp, height = 240.dp)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
        ) {
            RemoteCover(rec.cover, rec.key, RoundedCornerShape(26.dp), Modifier.fillMaxSize())
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x99100B18))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(rec.reason.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = LocalAccent.current)
                Text(rec.title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rec.artist, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistTile(rec: RecItem, onHide: () -> Unit) {
    TileShell(rec, onHide) { press ->
        Column(
            Modifier
                .width(112.dp)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(112.dp).clip(CircleShape).border(2.dp, LocalAccent.current.copy(alpha = 0.6f), CircleShape).padding(4.dp)) {
                RemoteCover(rec.cover, rec.key, CircleShape, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(8.dp))
            Text(rec.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(rec.reason, fontSize = 11.sp, color = LC.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

private fun Modifier.matchParentSizeCompat(side: Dp): Modifier = this.size(side)

@Composable
private fun SkeletonRow() {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.14f, infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Column(Modifier.padding(start = 20.dp, top = 14.dp)) {
        Box(Modifier.size(width = 180.dp, height = 18.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = a)))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Box(Modifier.size(150.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = a)))
            }
        }
    }
}


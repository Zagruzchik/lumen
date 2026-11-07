package app.lumen.player.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.ui.Nav
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.theme.AccentMode
import app.lumen.player.ui.theme.BackdropMode
import app.lumen.player.ui.theme.BuiltinThemes
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import app.lumen.player.ui.theme.ThemeJson
import app.lumen.player.ui.theme.ThemeGuide
import app.lumen.player.ui.theme.ThemeSpec
import app.lumen.player.ui.theme.ThemeStore
import kotlin.math.roundToInt

// ======================= МИНИ-ПРЕДПРОСМОТР =======================

/** Маленькая копия интерфейса в цветах темы — рисуется по самой теме, а не по текущей. */
@Composable
fun ThemePreview(t: ThemeSpec, modifier: Modifier = Modifier, accentOverride: Color? = null) {
    val accent = accentOverride ?: if (t.accentMode == AccentMode.FIXED) t.accent else Color(0xFFFF7A59)
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(t.background)) {
        Canvas(Modifier.fillMaxSize()) {
            when (t.backdrop) {
                BackdropMode.SOLID -> Unit
                else -> {
                    val cols = if (t.backdrop == BackdropMode.GRADIENT) t.gradient else listOf(Color(0xFFFF5F6D), Color(0xFF7B2FF7), Color(0xFFFFB347))
                    val k = t.backdropStrength
                    val pts = listOf(Offset(size.width * 0.1f, size.height * 0.05f), Offset(size.width, size.height * 0.45f), Offset(size.width * 0.15f, size.height))
                    cols.take(3).forEachIndexed { i, c ->
                        val r = size.width * 0.9f
                        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = k), Color.Transparent), pts[i], r), r, pts[i])
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text("Привет", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = t.text)
            Text("12 альбомов", fontSize = 9.sp, color = t.textDim)
            Spacer(Modifier.height(8.dp))
            // Карточка-стекло.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(t.card)
                    .border(1.dp, t.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.4f)))))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Box(Modifier.width(48.dp).height(5.dp).clip(CircleShape).background(t.text))
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.width(32.dp).height(4.dp).clip(CircleShape).background(t.textFaint))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Мини-плеер: стеклянная панель.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(t.panelTint)
                    .border(1.dp, t.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(t.cardStrong))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(t.textDim))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(18.dp).clip(CircleShape).background(accent))
            }
        }
    }
}

// ======================= РАЗДЕЛ В НАСТРОЙКАХ =======================

/** Карточка «Темы»: плитки тем, создание, импорт. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThemesSection(onEdit: (String) -> Unit) {
    val ctx = LocalContext.current
    val current = ThemeStore.current
    val all = ThemeStore.all
    var menuFor by remember { mutableStateOf<ThemeSpec?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                ThemeStore.import(text)
            }.onSuccess { Toast.makeText(ctx, "Тема «${it.name}» добавлена", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Это не тема Lumen: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    Column {
        Text("Темы", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(current.description.ifBlank { current.name }, fontSize = 13.sp, color = LC.Dim)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
            items(all, key = { it.id }) { t ->
                val on = t.id == current.id
                val ring by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.7f), label = "ring")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        ThemePreview(
                            t,
                            Modifier
                                .width(104.dp)
                                .height(150.dp)
                                .border((1 + 2 * ring).dp, LocalAccent.current.copy(alpha = 0.25f + 0.75f * ring), RoundedCornerShape(22.dp))
                                .combinedClickable(
                                    onClick = { ThemeStore.apply(t) },
                                    onLongClick = { menuFor = t },
                                ),
                        )
                        DropdownMenu(menuFor?.id == t.id, { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Изменить копию") }, onClick = {
                                menuFor = null
                                onEdit(t.id)
                            })
                            DropdownMenuItem(text = { Text("Поделиться") }, onClick = {
                                menuFor = null
                                shareTheme(ctx, t)
                            })
                            if (!ThemeStore.isBuiltin(t)) {
                                DropdownMenuItem(text = { Text("Удалить") }, onClick = {
                                    menuFor = null
                                    ThemeStore.delete(t)
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t.name, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(104.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Создать тему", LIcons.Plus, { onEdit(current.id) }, Modifier.weight(1f))
            PillButton("Импорт", LIcons.Refresh, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }, Modifier.weight(1f), primary = false)
        }
        Spacer(Modifier.height(8.dp))
        Text("Удержи плитку темы — изменить копию, поделиться или удалить", fontSize = 12.sp, color = LC.Faint)
        Spacer(Modifier.height(4.dp))
        Text(
            "Скопировать инструкцию для ИИ — чтобы любая нейросеть собрала тему по твоему описанию",
            fontSize = 13.sp,
            color = LocalAccent.current,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { copyAiGuide(ctx) }.padding(vertical = 6.dp),
        )
    }
}

fun shareTheme(ctx: Context, t: ThemeSpec) {
    runCatching {
        val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = java.io.File(dir, "${t.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")}.lumentheme.json")
        f.writeText(ThemeJson.write(t))
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Поделиться темой",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun copyAiGuide(ctx: Context) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("Lumen themes — guide for AI", ThemeGuide.AI_TEXT))
    Toast.makeText(ctx, "Инструкция для ИИ скопирована", Toast.LENGTH_SHORT).show()
}

// ======================= РЕДАКТОР =======================

@Composable
fun ThemeEditorScreen(baseId: String, nav: Nav) {
    val ctx = LocalContext.current
    val base = remember(baseId) { ThemeStore.all.firstOrNull { it.id == baseId } ?: BuiltinThemes.Lumen }
    var t by remember(baseId) {
        mutableStateOf(
            if (ThemeStore.isBuiltin(base)) base.copy(id = "custom-" + System.currentTimeMillis(), name = base.name + " — моя", author = "")
            else base
        )
    }
    var picking by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var onPicked by remember { mutableStateOf<(Color) -> Unit>({}) }
    var menu by remember { mutableStateOf(false) }

    fun pick(label: String, c: Color, set: (Color) -> Unit) {
        picking = label to c
        onPicked = set
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topInset() + 12.dp, bottom = 120.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(LIcons.Back, "Назад", nav.back)
                    Spacer(Modifier.width(12.dp))
                    Text("Редактор темы", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Box {
                        IconBtn(LIcons.More, "Ещё", { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Поделиться темой") }, onClick = { menu = false; shareTheme(ctx, t) })
                            DropdownMenuItem(text = { Text("Скопировать JSON") }, onClick = {
                                menu = false
                                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Lumen theme", ThemeJson.write(t)))
                                Toast.makeText(ctx, "JSON темы скопирован", Toast.LENGTH_SHORT).show()
                            })
                            DropdownMenuItem(text = { Text("Скопировать инструкцию для ИИ") }, onClick = { menu = false; copyAiGuide(ctx) })
                        }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    ThemePreview(t, Modifier.width(190.dp).height(270.dp))
                }
            }
            item {
                Section("Название") {
                    Field("Название", t.name) { t = t.copy(name = it.take(40)) }
                    Field("Автор", t.author) { t = t.copy(author = it.take(40)) }
                    Field("Описание", t.description) { t = t.copy(description = it.take(120)) }
                }
            }
            item {
                Section("Цвета") {
                    ColorRow("Фон", t.background) { pick("Фон", t.background) { c -> t = t.copy(background = c) } }
                    ColorRow("Текст", t.text) { pick("Текст", t.text) { c -> t = t.copy(text = c) } }
                    ColorRow("Второстепенный текст", t.textDim) { pick("Второстепенный текст", t.textDim) { c -> t = t.copy(textDim = c) } }
                    ColorRow("Бледный текст", t.textFaint) { pick("Бледный текст", t.textFaint) { c -> t = t.copy(textFaint = c) } }
                    Segmented(
                        "Акцент",
                        listOf("Из обложки" to (t.accentMode == AccentMode.COVER), "Свой цвет" to (t.accentMode == AccentMode.FIXED)),
                    ) { i -> t = t.copy(accentMode = if (i == 0) AccentMode.COVER else AccentMode.FIXED) }
                    if (t.accentMode == AccentMode.FIXED) {
                        ColorRow("Цвет акцента", t.accent) { pick("Цвет акцента", t.accent) { c -> t = t.copy(accent = c) } }
                    }
                    ColorRow("Текст на акценте", t.onAccent) { pick("Текст на акценте", t.onAccent) { c -> t = t.copy(onAccent = c) } }
                }
            }
            item {
                Section("Поверхности") {
                    ColorRow("Карточка", t.card) { pick("Карточка", t.card) { c -> t = t.copy(card = c) } }
                    ColorRow("Карточка (плотная)", t.cardStrong) { pick("Карточка (плотная)", t.cardStrong) { c -> t = t.copy(cardStrong = c) } }
                    ColorRow("Край стекла", t.border) { pick("Край стекла", t.border) { c -> t = t.copy(border = c) } }
                    ColorRow("Шторка сверху", t.sheetTop) { pick("Шторка сверху", t.sheetTop) { c -> t = t.copy(sheetTop = c) } }
                    ColorRow("Шторка снизу", t.sheetBottom) { pick("Шторка снизу", t.sheetBottom) { c -> t = t.copy(sheetBottom = c) } }
                    ColorRow("Тон панелей", t.panelTint) { pick("Тон панелей", t.panelTint) { c -> t = t.copy(panelTint = c) } }
                }
            }
            item {
                Section("Стекло") {
                    SliderRow("Размытие", t.blur, 0f..80f, "${t.blur.roundToInt()} dp") { t = t.copy(blur = it) }
                    SliderRow("Блик", t.highlight, 0f..1f, "${(t.highlight * 100).roundToInt()}%") { t = t.copy(highlight = it) }
                    SliderRow("Зерно", t.noise, 0f..0.3f, "${(t.noise * 100).roundToInt()}%") { t = t.copy(noise = it) }
                }
            }
            item {
                Section("Фон") {
                    Segmented(
                        "Фон приложения",
                        listOf("Обложка" to (t.backdrop == BackdropMode.COVER), "Градиент" to (t.backdrop == BackdropMode.GRADIENT), "Сплошной" to (t.backdrop == BackdropMode.SOLID)),
                    ) { i -> t = t.copy(backdrop = BackdropMode.entries[i]) }
                    SliderRow("Яркость фона", t.backdropStrength, 0f..1f, "${(t.backdropStrength * 100).roundToInt()}%") { t = t.copy(backdropStrength = it) }
                    if (t.backdrop == BackdropMode.GRADIENT) {
                        t.gradient.forEachIndexed { i, c ->
                            ColorRow("Градиент ${i + 1}", c) {
                                pick("Градиент ${i + 1}", c) { nc -> t = t.copy(gradient = t.gradient.toMutableList().also { it[i] = nc }) }
                            }
                        }
                    }
                    Segmented(
                        "Фон плеера",
                        listOf("Обложка" to (t.playerBackdrop == BackdropMode.COVER), "Пятна" to (t.playerBackdrop == BackdropMode.GRADIENT), "Сплошной" to (t.playerBackdrop == BackdropMode.SOLID)),
                    ) { i -> t = t.copy(playerBackdrop = BackdropMode.entries[i]) }
                    SliderRow("Затемнение плеера", t.playerDim, 0f..1f, "${(t.playerDim * 100).roundToInt()}%") { t = t.copy(playerDim = it) }
                }
            }
            item {
                Section("Эффекты") {
                    ToggleLine("Волны под бит", t.beatWaves) { t = t.copy(beatWaves = it) }
                    ToggleLine("Лучи у кнопки «Слушать»", t.rays) { t = t.copy(rays = it) }
                    ToggleLine("Обложка дышит под бит", t.coverBreath) { t = t.copy(coverBreath = it) }
                    SliderRow("Движение", t.motion, 0f..1f, if (t.motion <= 0f) "выкл." else "${(t.motion * 100).roundToInt()}%") { t = t.copy(motion = it) }
                }
            }
        }
        // Нижняя панель действий.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .padding(16.dp)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PillButton("Попробовать", LIcons.Play, { ThemeStore.apply(t) }, Modifier.weight(1f), primary = false)
            PillButton("Сохранить", LIcons.Check, {
                val saved = ThemeStore.save(t)
                Toast.makeText(ctx, "Тема «${saved.name}» сохранена", Toast.LENGTH_SHORT).show()
                nav.back()
            }, Modifier.weight(1f))
        }
        picking?.let { (label, c) ->
            ColorPickerDialog(label, c, onDone = { nc -> onPicked(nc); picking = null }, onDismiss = { picking = null })
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(LC.Glass)
            .border(1.dp, LC.Border, RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Text(title, fontSize = 13.sp, color = LocalAccent.current, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = LC.Dim)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = TextStyle(color = LC.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(LocalAccent.current),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(12.dp),
        )
    }
}

@Composable
private fun ColorRow(label: String, c: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("#%08X".format(c.toArgb()), fontSize = 12.sp, color = LC.Faint)
        Spacer(Modifier.width(10.dp))
        Checker(Modifier.size(30.dp).clip(CircleShape)) {
            Box(Modifier.fillMaxSize().background(c).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
        }
    }
}

/** Шахматка под полупрозрачным цветом — видно, насколько он прозрачный. */
@Composable
private fun Checker(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 6.dp.toPx()
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = 0f
                var col = row
                while (x < size.width) {
                    drawRect(if (col % 2 == 0) Color(0xFF3A3A40) else Color(0xFF8A8A92), Offset(x, y), androidx.compose.ui.geometry.Size(s, s))
                    x += s
                    col++
                }
                y += s
                row++
            }
        }
        content()
    }
}

@Composable
private fun SliderRow(label: String, v: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = v,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = LocalAccent.current, inactiveTrackColor = Color(0x26FFFFFF)),
        )
    }
}

@Composable
private fun ToggleLine(label: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = v,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current, checkedThumbColor = Color.White, uncheckedTrackColor = Color(0x22FFFFFF), uncheckedBorderColor = Color.Transparent),
        )
    }
}

@Composable
private fun Segmented(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f)).padding(3.dp),
        ) {
            options.forEachIndexed { i, (name, on) ->
                val bg by animateColorAsState(if (on) LocalAccent.current.copy(alpha = 0.35f) else Color.Transparent, tween(200), label = "seg")
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg).clickable { onPick(i) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(name, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
            }
        }
    }
}

// ======================= ВЫБОР ЦВЕТА =======================

private val SWATCHES = listOf(
    0xFF000000, 0xFF07070D, 0xFF141019, 0xFFFFFFFF, 0xFFF5F4FA, 0xFFFF7A59, 0xFFFF5F6D, 0xFFFFB347,
    0xFFFFD166, 0xFF43E97B, 0xFF29F0FF, 0xFF3D5AFE, 0xFF7B2FF7, 0xFFB98CFF, 0xFFFF2BD6, 0xFF9CC8FF,
).map { Color(it.toInt()) }

/** Выбор цвета: квадрат насыщенности/яркости, полоса оттенка, прозрачность, HEX и готовые цвета. */
@Composable
fun ColorPickerDialog(label: String, initial: Color, onDone: (Color) -> Unit, onDismiss: () -> Unit) {
    val hsv0 = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    var a by remember { mutableFloatStateOf(initial.alpha) }
    val color = Color(android.graphics.Color.HSVToColor((a * 255).roundToInt(), floatArrayOf(h, s, v)))
    var hex by remember(initial) { mutableStateOf("#%08X".format(initial.toArgb())) }
    fun setFrom(c: Color) {
        val f = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), f)
        h = f[0]; s = f[1]; v = f[2]; a = c.alpha
        hex = "#%08X".format(c.toArgb())
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(app.lumen.player.ui.theme.LT.sheetBrush())
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Checker(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) { Box(Modifier.fillMaxSize().background(color)) }
            }
            Spacer(Modifier.height(14.dp))
            // Насыщенность × яркость.
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(16.dp))) {
                val w = constraints.maxWidth.toFloat()
                val hh = constraints.maxHeight.toFloat()
                fun upd(p: Offset) {
                    s = (p.x / w).coerceIn(0f, 1f)
                    v = 1f - (p.y / hh).coerceIn(0f, 1f)
                    hex = "#%08X".format(Color(android.graphics.Color.HSVToColor((a * 255).roundToInt(), floatArrayOf(h, s, v))).toArgb())
                }
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { upd(it) } }
                        .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); upd(ch.position) } }
                ) {
                    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    drawCircle(Color.White, 10.dp.toPx(), Offset(s * size.width, (1f - v) * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                }
            }
            Spacer(Modifier.height(12.dp))
            GradientBar(
                Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }),
                h / 360f,
            ) { f -> h = f * 360f; hex = "#%08X".format(Color(android.graphics.Color.HSVToColor((a * 255).roundToInt(), floatArrayOf(h, s, v))).toArgb()) }
            Spacer(Modifier.height(10.dp))
            Checker(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))) {
                GradientBar(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 1f))), a) { f ->
                    a = f
                    hex = "#%08X".format(color.copy(alpha = f).toArgb())
                }
            }
            Text("Прозрачность: ${(a * 100).roundToInt()}%", fontSize = 12.sp, color = LC.Dim, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                hex,
                { new ->
                    hex = new.take(9)
                    runCatching { ThemeJson.parseColor(hex) }.getOrNull()?.let { c ->
                        val f = FloatArray(3)
                        android.graphics.Color.colorToHSV(c.toArgb(), f)
                        h = f[0]; s = f[1]; v = f[2]; a = c.alpha
                    }
                },
                singleLine = true,
                textStyle = TextStyle(color = LC.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(LocalAccent.current),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SWATCHES) { c ->
                    Box(Modifier.size(30.dp).clip(CircleShape).background(c).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape).clickable { setFrom(c.copy(alpha = a)) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Отмена", null, onDismiss, Modifier.weight(1f), primary = false)
                PillButton("Готово", LIcons.Check, { onDone(color) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GradientBar(brush: Brush, value: Float, onChange: (Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp)) {
        val w = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(13.dp))
                .background(brush)
                .pointerInput(Unit) { detectTapGestures { onChange((it.x / w).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); onChange((ch.position.x / w).coerceIn(0f, 1f)) } }
        )
        Box(
            Modifier
                .offset { IntOffset(((value.coerceIn(0f, 1f) * w) - 13.dp.toPx()).roundToInt().coerceIn(0, (w - 26.dp.toPx()).roundToInt()), 0) }
                .size(26.dp)
                .border(3.dp, Color.White, CircleShape)
        )
    }
}

@Suppress("unused")
private fun Modifier.fillHeight() = this.fillMaxHeight()

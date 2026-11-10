package app.lumen.player.ui.theme

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ДВИЖОК ТЕМ LUMEN.
 *
 * Тема — это набор «токенов» (цвета, стекло, фон, эффекты). Каждый экран берёт оформление
 * только из текущей темы, поэтому новая тема — это просто новый JSON-файл, без кода.
 * Формат и все поля описаны в мануале; ниже — их значение по умолчанию (тема «Lumen»).
 */
enum class AccentMode { COVER, FIXED, SYSTEM }
enum class BackdropMode { COVER, GRADIENT, SOLID }

@Immutable
data class ThemeSpec(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    // --- цвета ---
    val background: Color = Color(0xFF07070D),
    val text: Color = Color(0xFFF5F4FA),
    val textDim: Color = Color(0xB3F5F4FA),
    val textFaint: Color = Color(0x80F5F4FA),
    val accentMode: AccentMode = AccentMode.COVER,
    val accent: Color = Color(0xFFFF7A59),
    val onAccent: Color = Color(0xFF1A0B06),
    // --- поверхности ---
    val card: Color = Color(0x17FFFFFF),
    val cardStrong: Color = Color(0x26FFFFFF),
    val border: Color = Color(0x26FFFFFF),
    val sheetTop: Color = Color(0xF0282131),
    val sheetBottom: Color = Color(0xF5151219),
    val panelTint: Color = Color(0x8C281630),
    // --- стекло ---
    val blur: Float = 28f,
    val highlight: Float = 0.5f,
    val noise: Float = 0.06f,
    // --- фон ---
    val backdrop: BackdropMode = BackdropMode.COVER,
    val backdropStrength: Float = 0.55f,
    val gradient: List<Color> = listOf(Color(0xFFFF5F6D), Color(0xFF7B2FF7), Color(0xFFFFB347)),
    val playerBackdrop: BackdropMode = BackdropMode.COVER,
    val playerDim: Float = 0.55f,
    // --- эффекты ---
    val beatWaves: Boolean = true,
    val rays: Boolean = true,
    val coverBreath: Boolean = true,
    val motion: Float = 1f,
) {
    fun sheetBrush(): Brush = Brush.verticalGradient(listOf(sheetTop, sheetBottom))
}

/** Встроенные темы. */
object BuiltinThemes {
    val Lumen = ThemeSpec(
        id = "lumen",
        name = "Lumen",
        author = "CheKavo",
        description = "Фирменная: тёмное стекло и цвета обложки",
    )

    val Amoled = ThemeSpec(
        id = "amoled",
        name = "AMOLED",
        author = "CheKavo",
        description = "Чисто чёрная, без размытия и живых фонов — бережёт батарею",
        background = Color.Black,
        card = Color(0xFF0B0B0D),
        cardStrong = Color(0xFF141417),
        border = Color(0x1FFFFFFF),
        sheetTop = Color.Black,
        sheetBottom = Color.Black,
        panelTint = Color(0xFA000000),
        blur = 0f,
        highlight = 0f,
        noise = 0f,
        backdrop = BackdropMode.SOLID,
        backdropStrength = 0f,
        playerBackdrop = BackdropMode.SOLID,
        playerDim = 1f,
        beatWaves = false,
        rays = false,
        coverBreath = false,
        motion = 0f,
    )

    val Glass = ThemeSpec(
        id = "glass",
        name = "Стекло",
        author = "CheKavo",
        description = "Полный глассморфизм: всё из матового стекла поверх живого фона",
        background = Color(0xFF0B0814),
        card = Color(0x26FFFFFF),
        cardStrong = Color(0x38FFFFFF),
        border = Color(0x4DFFFFFF),
        sheetTop = Color(0xB8342B44),
        sheetBottom = Color(0xC71A1624),
        panelTint = Color(0x40FFFFFF),
        blur = 40f,
        highlight = 1f,
        noise = 0.08f,
        backdrop = BackdropMode.COVER,
        backdropStrength = 0.95f,
        playerBackdrop = BackdropMode.COVER,
        playerDim = 0.35f,
    )

    val Neon = ThemeSpec(
        id = "neon",
        name = "Неон",
        author = "CheKavo",
        description = "Синтвейв: фиолетово-бирюзовое свечение и неоновые контуры",
        background = Color(0xFF07041A),
        text = Color(0xFFF2EEFF),
        textDim = Color(0xB8D9D2FF),
        textFaint = Color(0x80B9AEFF),
        accentMode = AccentMode.FIXED,
        accent = Color(0xFF29F0FF),
        onAccent = Color(0xFF06021A),
        card = Color(0x1A8A5CFF),
        cardStrong = Color(0x2E8A5CFF),
        border = Color(0x8029F0FF),
        sheetTop = Color(0xF21C0F3A),
        sheetBottom = Color(0xF70A0620),
        panelTint = Color(0x99200A40),
        blur = 30f,
        highlight = 0.6f,
        backdrop = BackdropMode.GRADIENT,
        backdropStrength = 0.7f,
        gradient = listOf(Color(0xFFFF2BD6), Color(0xFF7B2FF7), Color(0xFF00E5FF)),
        playerBackdrop = BackdropMode.COVER,
        playerDim = 0.6f,
    )

    /** Material You: акцент берётся из системной палитры (обои Android 12+), фон — мягкий градиент. */
    val You = ThemeSpec(
        id = "you",
        name = "Material You",
        author = "CheKavo",
        description = "Цвет акцента из системной палитры Android — под твои обои",
        accentMode = AccentMode.SYSTEM,
        backdrop = BackdropMode.COVER,
        backdropStrength = 0.45f,
        playerBackdrop = BackdropMode.COVER,
    )

    val all = listOf(Lumen, Amoled, Glass, Neon, You)
}

/** Текущая тема и пользовательские темы (хранятся файлами в памяти приложения). */
object ThemeStore {
    var current by mutableStateOf(BuiltinThemes.Lumen)
        private set
    var custom by mutableStateOf<List<ThemeSpec>>(emptyList())
        private set

    private lateinit var dir: File
    private lateinit var appContext: Context

    val all: List<ThemeSpec> get() = BuiltinThemes.all + custom

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(appContext.filesDir, "themes").apply { mkdirs() }
        custom = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { ThemeJson.parse(f.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
        val selected = prefs().getString("theme", "lumen")
        current = all.firstOrNull { it.id == selected } ?: BuiltinThemes.Lumen
    }

    private fun prefs() = appContext.getSharedPreferences("lumen_theme", Context.MODE_PRIVATE)

    fun apply(t: ThemeSpec) {
        current = t
        prefs().edit().putString("theme", t.id).apply()
    }

    /** Сохраняет свою тему (новую или изменённую) и сразу применяет. */
    fun save(t: ThemeSpec): ThemeSpec {
        val fixed = if (BuiltinThemes.all.any { it.id == t.id }) t.copy(id = "custom-" + System.currentTimeMillis()) else t
        File(dir, "${fixed.id}.json").writeText(ThemeJson.write(fixed))
        custom = (custom.filter { it.id != fixed.id } + fixed).sortedBy { it.name.lowercase() }
        apply(fixed)
        return fixed
    }

    fun delete(t: ThemeSpec) {
        File(dir, "${t.id}.json").delete()
        custom = custom.filter { it.id != t.id }
        if (current.id == t.id) apply(BuiltinThemes.Lumen)
    }

    /** Импорт из файла: если id совпал со встроенной или чужой — получит новый. */
    fun import(json: String): ThemeSpec {
        val t = ThemeJson.parse(json)
        val id = if (all.any { it.id == t.id }) "custom-" + System.currentTimeMillis() else t.id
        return save(t.copy(id = id))
    }

    fun isBuiltin(t: ThemeSpec) = BuiltinThemes.all.any { it.id == t.id }
}

/** Чтение и запись тем в JSON. Неизвестные поля пропускаются, отсутствующие берутся из базовой темы. */
object ThemeJson {
    const val FORMAT = 1

    fun parse(text: String): ThemeSpec {
        val o = JSONObject(text)
        val base = BuiltinThemes.all.firstOrNull { it.id == o.optString("base") } ?: BuiltinThemes.Lumen
        val c = o.optJSONObject("colors") ?: JSONObject()
        val s = o.optJSONObject("surfaces") ?: JSONObject()
        val g = o.optJSONObject("glass") ?: JSONObject()
        val b = o.optJSONObject("backdrop") ?: JSONObject()
        val e = o.optJSONObject("effects") ?: JSONObject()
        fun col(obj: JSONObject, k: String, d: Color) = obj.optString(k).takeIf { it.isNotBlank() }?.let { parseColor(it) } ?: d
        fun num(obj: JSONObject, k: String, d: Float) = if (obj.has(k)) obj.optDouble(k, d.toDouble()).toFloat() else d
        fun bool(obj: JSONObject, k: String, d: Boolean) = if (obj.has(k)) obj.optBoolean(k, d) else d
        fun mode(v: String?, d: BackdropMode) = when (v?.lowercase()) {
            "cover" -> BackdropMode.COVER
            "gradient" -> BackdropMode.GRADIENT
            "solid" -> BackdropMode.SOLID
            else -> d
        }
        val id = o.optString("id").ifBlank { "custom-" + System.currentTimeMillis() }
            .lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        return ThemeSpec(
            id = id,
            name = o.optString("name").ifBlank { "Моя тема" },
            author = o.optString("author"),
            description = o.optString("description"),
            background = col(c, "background", base.background),
            text = col(c, "text", base.text),
            textDim = col(c, "textDim", base.textDim),
            textFaint = col(c, "textFaint", base.textFaint),
            accentMode = when (c.optString("accentMode").lowercase()) {
                "fixed" -> AccentMode.FIXED
                "cover" -> AccentMode.COVER
                "system" -> AccentMode.SYSTEM
                else -> base.accentMode
            },
            accent = col(c, "accent", base.accent),
            onAccent = col(c, "onAccent", base.onAccent),
            card = col(s, "card", base.card),
            cardStrong = col(s, "cardStrong", base.cardStrong),
            border = col(s, "border", base.border),
            sheetTop = col(s, "sheetTop", base.sheetTop),
            sheetBottom = col(s, "sheetBottom", base.sheetBottom),
            panelTint = col(s, "panelTint", base.panelTint),
            blur = num(g, "blur", base.blur).coerceIn(0f, 80f),
            highlight = num(g, "highlight", base.highlight).coerceIn(0f, 1f),
            noise = num(g, "noise", base.noise).coerceIn(0f, 0.3f),
            backdrop = mode(b.optString("mode"), base.backdrop),
            backdropStrength = num(b, "strength", base.backdropStrength).coerceIn(0f, 1f),
            gradient = b.optJSONArray("gradient")?.let { a ->
                (0 until a.length()).mapNotNull { runCatching { parseColor(a.getString(it)) }.getOrNull() }
            }?.takeIf { it.isNotEmpty() } ?: base.gradient,
            playerBackdrop = mode(b.optString("playerMode"), base.playerBackdrop),
            playerDim = num(b, "playerDim", base.playerDim).coerceIn(0f, 1f),
            beatWaves = bool(e, "beatWaves", base.beatWaves),
            rays = bool(e, "rays", base.rays),
            coverBreath = bool(e, "coverBreath", base.coverBreath),
            motion = num(e, "motion", base.motion).coerceIn(0f, 1f),
        )
    }

    fun write(t: ThemeSpec): String {
        fun hex(c: Color): String = "#%08X".format(c.toArgb())
        return JSONObject()
            .put("lumenTheme", FORMAT)
            .put("id", t.id)
            .put("name", t.name)
            .put("author", t.author)
            .put("description", t.description)
            .put(
                "colors",
                JSONObject()
                    .put("background", hex(t.background))
                    .put("text", hex(t.text))
                    .put("textDim", hex(t.textDim))
                    .put("textFaint", hex(t.textFaint))
                    .put("accentMode", t.accentMode.name.lowercase())
                    .put("accent", hex(t.accent))
                    .put("onAccent", hex(t.onAccent)),
            )
            .put(
                "surfaces",
                JSONObject()
                    .put("card", hex(t.card))
                    .put("cardStrong", hex(t.cardStrong))
                    .put("border", hex(t.border))
                    .put("sheetTop", hex(t.sheetTop))
                    .put("sheetBottom", hex(t.sheetBottom))
                    .put("panelTint", hex(t.panelTint)),
            )
            .put(
                "glass",
                JSONObject().put("blur", t.blur.toDouble()).put("highlight", t.highlight.toDouble()).put("noise", t.noise.toDouble()),
            )
            .put(
                "backdrop",
                JSONObject()
                    .put("mode", t.backdrop.name.lowercase())
                    .put("strength", t.backdropStrength.toDouble())
                    .put("gradient", JSONArray(t.gradient.map { hex(it) }))
                    .put("playerMode", t.playerBackdrop.name.lowercase())
                    .put("playerDim", t.playerDim.toDouble()),
            )
            .put(
                "effects",
                JSONObject()
                    .put("beatWaves", t.beatWaves)
                    .put("rays", t.rays)
                    .put("coverBreath", t.coverBreath)
                    .put("motion", t.motion.toDouble()),
            )
            .toString(2)
    }

    /** #RGB, #RRGGBB или #AARRGGBB. */
    fun parseColor(s: String): Color {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length == 6) h = "FF$h"
        require(h.length == 8) { "Цвет должен быть #RRGGBB или #AARRGGBB" }
        return Color(h.toLong(16).toInt())
    }
}


/** Системный акцент Material You (Android 12+), иначе null. */
fun systemAccent(ctx: android.content.Context): Color? =
    if (android.os.Build.VERSION.SDK_INT >= 31) Color(ctx.getColor(android.R.color.system_accent1_200)) else null

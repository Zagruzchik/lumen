package app.lumen.player.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    var folders: Set<String>
        get() = sp.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("folders", v).apply()

    var skipShort: Boolean
        get() = sp.getBoolean("skipShort", true)
        set(v) = sp.edit().putBoolean("skipShort", v).apply()

    var onlineLyrics: Boolean
        get() = sp.getBoolean("onlineLyrics", true)
        set(v) = sp.edit().putBoolean("onlineLyrics", v).apply()

    var showLyrics: Boolean
        get() = sp.getBoolean("showLyrics", false)
        set(v) = sp.edit().putBoolean("showLyrics", v).apply()

    /** Папки, которые сканер уже видел: автоисключение срабатывает только при первом обнаружении. */
    var seenFolders: Set<String>
        get() = sp.getStringSet("seenFolders", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("seenFolders", v).apply()

    var eqEnabled: Boolean
        get() = sp.getBoolean("eqEnabled", false)
        set(v) = sp.edit().putBoolean("eqEnabled", v).apply()

    /** Уровни полос эквалайзера в миллибелах через запятую. */
    var eqLevels: List<Int>
        get() = sp.getString("eqLevels", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        set(v) = sp.edit().putString("eqLevels", v.joinToString(",")).apply()

    var eqPreset: Int
        get() = sp.getInt("eqPreset", -1)
        set(v) = sp.edit().putInt("eqPreset", v).apply()

    var bassStrength: Int
        get() = sp.getInt("bassStrength", 0)
        set(v) = sp.edit().putInt("bassStrength", v).apply()

    /** Сколько раз запускалась музыка (старт после паузы дольше минуты или с нуля). */
    var playStarts: Int
        get() = sp.getInt("playStarts", 0)
        set(v) = sp.edit().putInt("playStarts", v).apply()

    var beatFlashes: Boolean
        get() = sp.getBoolean("beatFlashes", true)
        set(v) = sp.edit().putBoolean("beatFlashes", v).apply()

    /** Сила вспышек под бит, 0..1. */
    /** Кроссфейд между треками, мс (0 — выключен). */
    /** Когда очередь кончилась — продолжать похожими треками. */
    var smartQueue: Boolean
        get() = sp.getBoolean("smartQueue", true)
        set(v) = sp.edit().putBoolean("smartQueue", v).apply()

    /** Выравнивание громкости между треками. */
    var normalize: Boolean
        get() = sp.getBoolean("normalize", true)
        set(v) = sp.edit().putBoolean("normalize", v).apply()

    /** Плавное затухание при паузе и нарастание при старте. */
    var fadePause: Boolean
        get() = sp.getBoolean("fadePause", true)
        set(v) = sp.edit().putBoolean("fadePause", v).apply()

    /** Продолжать длинные треки (от 20 минут) с места остановки. */
    var resumeLong: Boolean
        get() = sp.getBoolean("resumeLong", true)
        set(v) = sp.edit().putBoolean("resumeLong", v).apply()

    /** Продолжать, когда подключились Bluetooth-наушники. */
    var btResume: Boolean
        get() = sp.getBoolean("btResume", false)
        set(v) = sp.edit().putBoolean("btResume", v).apply()

    /** Свой экран блокировки с текстом песни. */
    var lockScreen: Boolean
        get() = sp.getBoolean("lockScreen", false)
        set(v) = sp.edit().putBoolean("lockScreen", v).apply()

    /** Показывать бейджи формата (FLAC/MP3). */
    var showFormat: Boolean
        get() = sp.getBoolean("showFormat", true)
        set(v) = sp.edit().putBoolean("showFormat", v).apply()

    /** Проверять обновления при запуске. */
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    /** Мягкость кроссфейда: 0 — переход резче, ближе к концу; 1 — максимально плавный. */
    var crossfadeSoft: Float
        get() = sp.getFloat("crossfadeSoft", 0.75f)
        set(v) = sp.edit().putFloat("crossfadeSoft", v).apply()

    var crossfadeMs: Int
        get() = sp.getInt("crossfadeMs", 0)
        set(v) = sp.edit().putInt("crossfadeMs", v).apply()

    var beatIntensity: Float
        get() = sp.getFloat("beatIntensity", 0.5f)
        set(v) = sp.edit().putFloat("beatIntensity", v).apply()

    /** Рекомендации, которые пользователь скрыл. */
    /** Треки, включённые из поиска (новые сверху), через перевод строки. */
    var searchHistory: List<String>
        get() = sp.getString("searchHistory", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("searchHistory", v.joinToString("\n")).apply()

    var hiddenRecs: Set<String>
        get() = sp.getStringSet("hiddenRecs", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hiddenRecs", v).apply()

    var queue: List<String>
        get() = sp.getString("queue", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("queue", v.joinToString("\n")).apply()

    var queueIndex: Int
        get() = sp.getInt("queueIndex", 0)
        set(v) = sp.edit().putInt("queueIndex", v).apply()

    var queuePosition: Long
        get() = sp.getLong("queuePosition", 0L)
        set(v) = sp.edit().putLong("queuePosition", v).apply()

    /** Группы дубликатов, которые пользователь отметил «это не дубликаты». */
    var dupIgnored: Set<String>
        get() = sp.getStringSet("dupIgnored", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("dupIgnored", v).apply()
}

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

    var queue: List<String>
        get() = sp.getString("queue", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("queue", v.joinToString("\n")).apply()

    var queueIndex: Int
        get() = sp.getInt("queueIndex", 0)
        set(v) = sp.edit().putInt("queueIndex", v).apply()

    var queuePosition: Long
        get() = sp.getLong("queuePosition", 0L)
        set(v) = sp.edit().putLong("queuePosition", v).apply()
}

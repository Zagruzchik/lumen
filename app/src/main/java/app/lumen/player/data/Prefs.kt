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

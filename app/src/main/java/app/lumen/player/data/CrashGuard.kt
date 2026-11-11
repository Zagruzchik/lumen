package app.lumen.player.data

import android.content.Context
import android.os.SystemClock

/**
 * Страховка от «вечного» вылета: если Lumen упал вскоре после запуска, при следующем запуске
 * не восстанавливаем сохранённую очередь (из-за которой падение могло повторяться),
 * показываем отчёт об ошибке и сразу ищем обновление с исправлением.
 */
object CrashGuard {
    private const val FILE = "lumen_crash"
    private var startedAt = 0L

    /** Прошлый запуск закончился вылетом вскоре после старта — запускаемся в безопасном режиме. */
    var safeMode = false
        private set

    fun install(context: Context) {
        startedAt = SystemClock.elapsedRealtime()
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val at = sp.getLong("at", 0L)
        safeMode = sp.getBoolean("quick", false) && System.currentTimeMillis() - at < 30 * 60_000L
        // Безопасный режим — на один запуск.
        if (safeMode) sp.edit().putBoolean("quick", false).apply()

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val quick = SystemClock.elapsedRealtime() - startedAt < 90_000L
                val ver = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
                sp.edit()
                    .putLong("at", System.currentTimeMillis())
                    .putBoolean("quick", quick)
                    .putBoolean("shown", false)
                    .putString("version", ver)
                    .putString("thread", t.name)
                    .putString("trace", android.util.Log.getStackTraceString(e).take(12_000))
                    .commit()
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Отчёт о последнем вылете, который ещё не показывали (или null). */
    fun pendingReport(context: Context): String? {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (sp.getBoolean("shown", true)) return null
        val trace = sp.getString("trace", null) ?: return null
        val at = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(sp.getLong("at", 0L)))
        return buildString {
            append("Lumen ").append(sp.getString("version", "")).append(" · ").append(at).append('\n')
            append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
            append("Поток: ").append(sp.getString("thread", "")).append("\n\n")
            append(trace)
        }
    }

    fun markShown(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("shown", true).apply()
    }
}

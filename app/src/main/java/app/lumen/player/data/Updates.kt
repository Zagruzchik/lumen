package app.lumen.player.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Опубликованная версия из Supabase (таблица lumen_releases). */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
    val mandatory: Boolean,
)

/**
 * Обновления по воздуху: CI после каждой сборки кладёт APK в Supabase Storage
 * и запись в lumen_releases. Приложение сверяет номер версии, качает APK и
 * ставит его через системный установщик (подпись та же — ставится поверх, данные сохраняются).
 */
class UpdateRepository(private val context: Context) {
    private val base = "https://mplorochmulzbxyiihdq.supabase.co"
    private val key = "sb_publishable_e7dBxeLArf9KSHHQnq2hKw_PEm131rz"

    val currentCode: Int by lazy {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }
    val currentName: String by lazy {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }

    /** Есть ли среди версий новее установленной обязательная (срочный патч). */
    private fun hasMandatoryAbove(): Boolean = runCatching {
        val c = URL("$base/rest/v1/lumen_releases?select=version_code&mandatory=eq.true&version_code=gt.$currentCode&limit=1")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("apikey", key)
        c.setRequestProperty("Authorization", "Bearer $key")
        try {
            c.responseCode in 200..299 && JSONArray(c.inputStream.bufferedReader().readText()).length() > 0
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /** Самая свежая опубликованная версия (или null, если нет сети/записей). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = URL("$base/rest/v1/lumen_releases?select=*&order=version_code.desc&limit=1").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("apikey", key)
        c.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (c.responseCode !in 200..299) return@withContext null
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            if (arr.length() == 0) return@withContext null
            val o = arr.getJSONObject(0)
            Release(
                versionCode = o.getInt("version_code"),
                versionName = o.optString("version_name"),
                notes = o.optString("notes"),
                apkUrl = o.getString("apk_url"),
                sizeBytes = o.optLong("size_bytes"),
                sha256 = o.optString("sha256").takeIf { it.isNotBlank() && it != "null" },
                mandatory = o.optBoolean("mandatory") ||
                    o.optString("notes").contains("[обязательное]", ignoreCase = true) ||
                    hasMandatoryAbove(),
            )
        } finally {
            c.disconnect()
        }
    }

    /** Заметки к конкретной версии — для экрана «Что нового» после обновления. */
    suspend fun notesFor(code: Int): String? = withContext(Dispatchers.IO) {
        val c = URL("$base/rest/v1/lumen_releases?select=notes&version_code=eq.$code&limit=1").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("apikey", key)
        c.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (c.responseCode !in 200..299) return@withContext null
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            if (arr.length() == 0) null else arr.getJSONObject(0).optString("notes").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun dir() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Скачивает APK с прогрессом 0..1. Уже скачанный и проверенный файл повторно не качается. */
    suspend fun download(r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val out = File(dir(), "lumen-${r.versionCode}.apk")
        if (out.exists() && r.sha256 != null && sha256(out) == r.sha256) {
            onProgress(1f)
            return@withContext out
        }
        dir().listFiles()?.forEach { it.delete() }
        val tmp = File(dir(), "lumen-${r.versionCode}.part")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        try {
            if (c.responseCode !in 200..299) error("Сервер ответил ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.sizeBytes
            var done = 0L
            var lastReport = 0L
            c.inputStream.use { inp ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 96 * 1024) {
                            lastReport = done
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (r.sha256 != null && sha256(tmp) != r.sha256) {
            tmp.delete()
            error("Файл повреждён при загрузке — попробуй ещё раз")
        }
        tmp.renameTo(out)
        onProgress(1f)
        out
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Передаёт APK системному установщику; он сам спросит подтверждение. */
    suspend fun install(file: File) = withContext(Dispatchers.IO) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { inp ->
                s.openWrite("lumen.apk", 0, file.length()).use { o ->
                    inp.copyTo(o, 64 * 1024)
                    s.fsync(o)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val sender = PendingIntent.getBroadcast(context, id, intent, flags)
            s.commit(sender.intentSender)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Ответ установщика: если нужно подтверждение пользователя — показываем системное окно. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateEvents.failed.tryEmit(msg ?: "Установка не удалась")
            }
        }
    }
}

object UpdateEvents {
    val failed = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
}

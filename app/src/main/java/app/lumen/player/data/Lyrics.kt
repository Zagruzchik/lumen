package app.lumen.player.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

sealed interface LyricsResult {
    data object Idle : LyricsResult
    data object Loading : LyricsResult
    data class Found(val lines: List<LyricLine>, val synced: Boolean, val source: String) : LyricsResult
    data object NotFound : LyricsResult
    data object Instrumental : LyricsResult
    data object Disabled : LyricsResult
    data object Offline : LyricsResult
}

object LrcParser {
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        text.lineSequence().forEach { raw ->
            val matches = tag.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val body = raw.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                out += LyricLine(min * 60_000 + sec * 1000 + frac, body)
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    fun plain(text: String): List<LyricLine> =
        text.lines().map { LyricLine(-1, it.trim()) }
}

class LyricsRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val prefs: Prefs,
) {
    private val userAgent = "Lumen/0.2 (https://github.com/Zagruzchik/lumen)"
    private val dir: File by lazy { File(context.filesDir, "lyrics").apply { mkdirs() } }
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Файл с текстом в памяти приложения — доступен офлайн. */
    fun fileFor(t: TrackEntity): File {
        val safe = "${t.artist} - ${t.title}".replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(80).trim()
        val hash = Integer.toHexString(t.uri.hashCode())
        return File(dir, "$safe [$hash].lrc")
    }

    fun hasSaved(t: TrackEntity): Boolean = fileFor(t).exists()

    private fun parseAny(text: String): Pair<List<LyricLine>, Boolean> {
        val synced = LrcParser.parse(text)
        if (synced.isNotEmpty()) return synced to true
        val plain = text.lines().filterNot { it.trim().startsWith("[") && it.trim().endsWith("]") }
        return LrcParser.plain(plain.joinToString("\n").trim()) to false
    }

    private fun header(t: TrackEntity): String = buildString {
        append("[ar:${t.artist}]\n")
        append("[ti:${t.title}]\n")
        if (t.album.isNotBlank()) append("[al:${t.album}]\n")
        if (t.durationMs > 0) append("[length:${t.durationMs / 60000}:${"%02d".format((t.durationMs / 1000) % 60)}]\n")
        append("[re:Lumen]\n")
    }

    /** Сохраняем текст файлом: в памяти приложения и, если можно, рядом с треком. */
    private fun saveFile(t: TrackEntity, text: String, synced: Boolean) {
        val content = header(t) + "\n" + text.trim() + "\n"
        runCatching { fileFor(t).writeText(content) }
        writeSibling(t, content)
    }

    private fun writeSibling(t: TrackEntity, content: String) {
        if (t.source != SOURCE_FOLDER) return
        runCatching {
            val single = DocumentFile.fromSingleUri(context, Uri.parse(t.uri)) ?: return
            val base = single.name?.substringBeforeLast('.') ?: return
            val folder = DocumentFile.fromTreeUri(context, Uri.parse(t.folder)) ?: return
            if (!folder.canWrite() || folder.findFile("$base.lrc") != null) return
            val f = folder.createFile("application/octet-stream", "$base.lrc") ?: return
            context.contentResolver.openOutputStream(f.uri)?.use { it.write(content.toByteArray()) }
        }
    }

    private fun found(text: String, source: String): LyricsResult? {
        val (lines, synced) = parseAny(text)
        if (lines.isEmpty() || lines.all { it.text.isBlank() }) return null
        return LyricsResult.Found(lines, synced, source)
    }

    suspend fun load(t: TrackEntity, force: Boolean = false): LyricsResult {
        val lock = locks.getOrPut(t.uri) { kotlinx.coroutines.sync.Mutex() }
        return lock.withLock { withContext(Dispatchers.IO) { loadLocked(t, force) } }
    }

    private suspend fun loadLocked(t: TrackEntity, force: Boolean): LyricsResult {
        // 1. .lrc рядом с треком
        localLrc(t)?.let { text ->
            found(text, ".lrc")?.let { return it }
        }
        // 2. сохранённый файл (работает офлайн)
        val saved = fileFor(t)
        if (!force && saved.exists()) {
            runCatching { saved.readText() }.getOrNull()?.let { text ->
                val src = dao.lyrics(t.uri)?.source ?: "LRCLIB"
                found(text, src)?.let { return it }
            }
        }
        // 3. запись в базе (старые версии хранили текст только там) — переносим в файл
        val cached = dao.lyrics(t.uri)
        if (cached != null && !force) {
            val fresh = System.currentTimeMillis() - cached.fetchedAt < 30L * 24 * 3600 * 1000
            when {
                cached.instrumental -> return LyricsResult.Instrumental
                !cached.synced.isNullOrBlank() -> {
                    saveFile(t, cached.synced, true)
                    return LyricsResult.Found(LrcParser.parse(cached.synced), true, cached.source)
                }
                !cached.plain.isNullOrBlank() -> {
                    saveFile(t, cached.plain, false)
                    return LyricsResult.Found(LrcParser.plain(cached.plain), false, cached.source)
                }
                fresh -> return LyricsResult.NotFound
            }
        }
        // 4. интернет
        if (!prefs.onlineLyrics) return LyricsResult.Disabled
        val remote = try {
            fetchLrclib(t)
        } catch (e: Exception) {
            return LyricsResult.Offline
        }
        if (remote == null) {
            dao.putLyrics(LyricsEntity(t.uri, null, null, false, "LRCLIB", System.currentTimeMillis()))
            return LyricsResult.NotFound
        }
        dao.putLyrics(remote.copy(key = t.uri))
        return when {
            remote.instrumental -> LyricsResult.Instrumental
            !remote.synced.isNullOrBlank() -> {
                saveFile(t, remote.synced, true)
                LyricsResult.Found(LrcParser.parse(remote.synced), true, "LRCLIB")
            }
            !remote.plain.isNullOrBlank() -> {
                saveFile(t, remote.plain, false)
                LyricsResult.Found(LrcParser.plain(remote.plain), false, "LRCLIB")
            }
            else -> LyricsResult.NotFound
        }
    }

    /** Нужно ли ещё что-то качать для этого трека (для массовой загрузки). */
    suspend fun needsFetch(t: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        if (fileFor(t).exists()) return@withContext false
        val c = dao.lyrics(t.uri) ?: return@withContext true
        if (c.instrumental) return@withContext false
        if (!c.synced.isNullOrBlank() || !c.plain.isNullOrBlank()) return@withContext true
        System.currentTimeMillis() - c.fetchedAt >= 30L * 24 * 3600 * 1000
    }

    /** .lrc рядом с файлом: в папке, добавленной вручную, или по прямому пути (если доступен). */
    private fun localLrc(t: TrackEntity): String? {
        if (t.source == SOURCE_FOLDER) {
            return runCatching {
                val single = DocumentFile.fromSingleUri(context, Uri.parse(t.uri)) ?: return null
                val base = single.name?.substringBeforeLast('.') ?: return null
                val folder = DocumentFile.fromTreeUri(context, Uri.parse(t.folder)) ?: return null
                val lrc = folder.findFile("$base.lrc") ?: return null
                context.contentResolver.openInputStream(lrc.uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
        if (t.path.isNotEmpty()) {
            return runCatching {
                val f = File(t.path.substringBeforeLast('.') + ".lrc")
                if (f.canRead()) f.readText() else null
            }.getOrNull()
        }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetchLrclib(t: TrackEntity): LyricsEntity? {
        val seconds = t.durationMs / 1000
        val hasArtist = t.artist != UNKNOWN_ARTIST
        if (hasArtist) {
            val url = "https://lrclib.net/api/get?artist_name=${enc(t.artist)}&track_name=${enc(t.title)}" +
                "&album_name=${enc(t.album)}&duration=$seconds"
            val (code, body) = get(url)
            if (code == 200 && body != null) return toEntity(JSONObject(body))
        }
        val searchUrl = if (hasArtist) {
            "https://lrclib.net/api/search?track_name=${enc(t.title)}&artist_name=${enc(t.artist)}"
        } else {
            "https://lrclib.net/api/search?q=${enc(t.title)}"
        }
        val (code, body) = get(searchUrl)
        if (code != 200 || body == null) return null
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = o.optDouble("duration", 0.0).toLong()
            var score = 0
            if (!str(o, "syncedLyrics").isNullOrBlank()) score += 10
            if (!str(o, "plainLyrics").isNullOrBlank()) score += 2
            if (seconds > 0 && d > 0) score -= abs(d - seconds).toInt().coerceAtMost(30)
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        return best?.let { toEntity(it) }
    }

    private fun toEntity(o: JSONObject) = LyricsEntity(
        key = "",
        synced = str(o, "syncedLyrics"),
        plain = str(o, "plainLyrics"),
        instrumental = o.optBoolean("instrumental", false),
        source = "LRCLIB",
        fetchedAt = System.currentTimeMillis(),
    )

    private fun str(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    private fun get(url: String): Pair<Int, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", userAgent)
        try {
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
            return code to body
        } finally {
            c.disconnect()
        }
    }
}

data class BulkProgress(
    val done: Int,
    val total: Int,
    val found: Int,
    val running: Boolean,
    val offline: Boolean = false,
)

/** Массовая загрузка текстов для всей библиотеки — чтобы всё было доступно офлайн. */
class LyricsBulkDownloader(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val dao: LibraryDao,
    private val repo: LyricsRepository,
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<BulkProgress?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<BulkProgress?> = _state
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = BulkProgress(0, 0, 0, true)
            val todo = dao.visibleTracksOnce().filter { repo.needsFetch(it) }
            var found = 0
            _state.value = BulkProgress(0, todo.size, 0, true)
            for ((i, t) in todo.withIndex()) {
                val r = runCatching { repo.load(t) }.getOrNull()
                if (r is LyricsResult.Offline) {
                    _state.value = BulkProgress(i, todo.size, found, running = false, offline = true)
                    return@launch
                }
                if (r is LyricsResult.Found || r is LyricsResult.Instrumental) found++
                _state.value = BulkProgress(i + 1, todo.size, found, true)
                kotlinx.coroutines.delay(120)
            }
            _state.value = BulkProgress(todo.size, todo.size, found, false)
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = _state.value?.copy(running = false)
    }
}

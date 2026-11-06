package app.lumen.player.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
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
    private val userAgent = "Lumen/0.1.0 (https://github.com/Zagruzchik/lumen-player)"

    suspend fun load(t: TrackEntity, force: Boolean = false): LyricsResult = withContext(Dispatchers.IO) {
        val cached = dao.lyrics(t.uri)
        if (cached != null && !force) {
            val fresh = System.currentTimeMillis() - cached.fetchedAt < 3L * 24 * 3600 * 1000
            when {
                cached.instrumental -> return@withContext LyricsResult.Instrumental
                !cached.synced.isNullOrBlank() ->
                    return@withContext LyricsResult.Found(LrcParser.parse(cached.synced), true, cached.source)
                !cached.plain.isNullOrBlank() ->
                    return@withContext LyricsResult.Found(LrcParser.plain(cached.plain), false, cached.source)
                fresh -> return@withContext LyricsResult.NotFound
            }
        }

        localLrc(t)?.let { text ->
            val lines = LrcParser.parse(text)
            if (lines.isNotEmpty()) {
                dao.putLyrics(LyricsEntity(t.uri, text, null, false, ".lrc", System.currentTimeMillis()))
                return@withContext LyricsResult.Found(lines, true, ".lrc")
            }
        }

        if (!prefs.onlineLyrics) return@withContext LyricsResult.Disabled

        val remote = try {
            fetchLrclib(t)
        } catch (e: Exception) {
            return@withContext LyricsResult.Offline
        }
        if (remote == null) {
            dao.putLyrics(LyricsEntity(t.uri, null, null, false, "LRCLIB", System.currentTimeMillis()))
            return@withContext LyricsResult.NotFound
        }
        dao.putLyrics(remote.copy(key = t.uri))
        when {
            remote.instrumental -> LyricsResult.Instrumental
            !remote.synced.isNullOrBlank() -> LyricsResult.Found(LrcParser.parse(remote.synced), true, "LRCLIB")
            !remote.plain.isNullOrBlank() -> LyricsResult.Found(LrcParser.plain(remote.plain), false, "LRCLIB")
            else -> LyricsResult.NotFound
        }
    }

    /** .lrc рядом с файлом: в папке, добавленной вручную, или по прямому пути (если доступен). */
    private fun localLrc(t: TrackEntity): String? {
        if (t.source == SOURCE_FOLDER) {
            return runCatching {
                val single = DocumentFile.fromSingleUri(context, Uri.parse(t.uri)) ?: return null
                val base = single.name?.substringBeforeLast('.') ?: return null
                val dir = DocumentFile.fromTreeUri(context, Uri.parse(t.folder)) ?: return null
                val lrc = dir.findFile("$base.lrc") ?: return null
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

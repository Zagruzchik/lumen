package app.lumen.player.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

const val SOURCE_ONLINE = 2
const val ONLINE_SCHEME = "lumen-online://"

/** Трек из сети (ответ music-api): ещё не в базе, просто результат поиска. */
data class OnlineTrack(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Double?,
    val thumbnail: String?,
    val pageUrl: String,
)

data class OnlinePlaylist(val title: String, val items: List<OnlineTrack>)

data class ServerFile(val path: String, val size: Long, val modified: Long, val url: String)

class OnlineError(message: String, val code: Int = 0) : IOException(message)

/**
 * Онлайн-треки внутри Lumen.
 *
 * URI вида lumen-online://soundcloud/<id> — постоянный идентификатор трека в базе (лайки, плейлисты,
 * история, статистика). Перед проигрыванием он превращается в адрес /stream на сервере music-api,
 * который отдаёт свежую прямую ссылку (302) — поэтому протухшие ссылки CDN никогда не мешают.
 */
object Online {
    @Volatile private var server: String = ""
    @Volatile private var key: String = ""
    @Volatile var viaServer: Boolean = false
        private set

    /** Обложки онлайн-треков: uri → адрес картинки (для мгновенного показа без похода в базу). */
    val thumbs = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun configure(p: Prefs) {
        server = p.onlineServer.trim().trimEnd('/')
        key = p.onlineKey.trim()
        viaServer = p.onlineViaServer
    }

    val configured: Boolean get() = server.isNotBlank() && key.isNotBlank()

    fun isOnline(uri: String?) = uri?.startsWith(ONLINE_SCHEME) == true

    fun uriOf(source: String, id: String) = ONLINE_SCHEME + source + "/" + URLEncoder.encode(id, "UTF-8")

    /** (источник, id) из URI. */
    fun parse(uri: String): Pair<String, String>? {
        if (!isOnline(uri)) return null
        val rest = uri.removePrefix(ONLINE_SCHEME)
        val src = rest.substringBefore('/')
        val id = URLDecoder.decode(rest.substringAfter('/', ""), "UTF-8")
        return if (src.isBlank() || id.isBlank()) null else src to id
    }

    fun sourceLabel(source: String) = when (source) {
        "soundcloud" -> "SoundCloud"
        "youtube" -> "YouTube"
        else -> source.replaceFirstChar { it.uppercase() }
    }

    fun labelOf(uri: String) = parse(uri)?.first?.let(::sourceLabel) ?: "Онлайн"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Адрес потока для плеера. SoundCloud отдаёт HLS — его либо играет ExoPlayer, либо сервер перегоняет в MP3. */
    fun streamUrl(uri: String, forceProxy: Boolean = false): String? {
        val (src, id) = parse(uri) ?: return null
        if (server.isBlank()) return null
        val proxy = forceProxy || viaServer
        return "$server/stream/$src/${enc(id)}?key=${enc(key)}" + if (proxy) "&proxy=1" else ""
    }

    /** HLS ли это (нужно ExoPlayer, чтобы выбрать правильный разборщик). */
    fun isHls(uri: String, forceProxy: Boolean = false): Boolean =
        !(forceProxy || viaServer) && parse(uri)?.first == "soundcloud"

    // ---------- метаданные: чистим названия с YouTube ----------

    private val junk = Regex(
        "\\s*[\\[(](official\\s*(music\\s*)?(video|audio|lyric(s)?\\s*video|visuali[sz]er)|lyric(s)?(\\s*video)?|audio|hd|hq|4k|" +
            "music\\s*video|клип|премьера[^)\\]]*|official|video|mv|m/v)[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    /** «Artist - Topic» → «Artist»; «Канал» + «Artist - Title» → Artist / Title; без «(Official Video)». */
    fun cleanNames(source: String, title: String, uploader: String): Pair<String, String> {
        var t = title.replace(junk, "").trim()
        var a = uploader.removeSuffix(" - Topic").removeSuffix("VEVO").removeSuffix(" Official").trim()
        val dash = Regex("\\s+[-–—]\\s+").find(t)
        if (dash != null && source == "youtube") {
            val left = t.substring(0, dash.range.first).trim()
            val right = t.substring(dash.range.last + 1).trim()
            if (left.isNotBlank() && right.isNotBlank()) {
                a = left
                t = right
            }
        }
        t = t.removeSurrounding("\"").trim()
        return (t.ifBlank { title }) to a
    }

    fun toEntity(o: OnlineTrack): TrackEntity {
        val (title, artist) = cleanNames(o.source, o.title, o.artist)
        val uri = uriOf(o.source, o.id)
        o.thumbnail?.let { thumbs[uri] = it }
        return TrackEntity(
            uri = uri,
            title = title,
            artist = artist.ifBlank { "Неизвестный исполнитель" },
            album = sourceLabel(o.source),
            albumArtist = artist,
            albumKey = "online:" + o.source,
            durationMs = ((o.durationSec ?: 0.0) * 1000).toLong(),
            trackNo = 0,
            discNo = 0,
            year = 0,
            mime = "audio/online",
            // В path храним обложку и страницу трека: у онлайн-трека нет пути к файлу.
            path = (o.thumbnail ?: "") + "\n" + o.pageUrl,
            folder = "online:" + o.source,
            folderName = sourceLabel(o.source),
            dateAdded = System.currentTimeMillis(),
            size = 0,
            bitrate = 0,
            source = SOURCE_ONLINE,
        )
    }

    fun thumbOf(t: TrackEntity): String? = t.path.substringBefore('\n').takeIf { it.startsWith("http") }
    fun pageOf(t: TrackEntity): String? = t.path.substringAfter('\n', "").takeIf { it.startsWith("http") }

    // ---------- HTTP ----------

    private fun call(path: String, timeoutMs: Int = 45_000): String {
        if (!configured) throw OnlineError("Сервер не настроен")
        val c = URL(server + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("X-Api-Key", key)
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) throw OnlineError("Неверный ключ доступа", 401)
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw OnlineError(humanError(msg.ifBlank { "Сервер ответил $code" }), code)
            }
            return text
        } catch (e: OnlineError) {
            throw e
        } catch (e: java.net.ConnectException) {
            throw OnlineError("Сервер не отвечает — запущен ли music-api?")
        } catch (e: java.net.SocketTimeoutException) {
            throw OnlineError("Сервер думает слишком долго — попробуй ещё раз")
        } catch (e: IOException) {
            throw OnlineError("Нет связи с сервером: ${e.message ?: "ошибка сети"}")
        } finally {
            c.disconnect()
        }
    }

    private fun humanError(m: String): String = when {
        "not a bot" in m.lowercase() || "sign in to confirm" in m.lowercase() -> "YouTube просит подтвердить, что ты не робот. Смени сеть или обнови yt-dlp на сервере"
        "geo" in m.lowercase() -> "Трек недоступен в твоей стране"
        "private" in m.lowercase() -> "Трек закрыт автором"
        "removed" in m.lowercase() || "unavailable" in m.lowercase() || "404" in m -> "Трек удалён или недоступен"
        else -> m
    }

    private fun track(o: JSONObject, fallbackSource: String? = null) = OnlineTrack(
        source = o.optString("source").ifBlank { fallbackSource.orEmpty() },
        id = o.optString("id"),
        title = o.optString("title").ifBlank { "Без названия" },
        artist = o.optString("artist"),
        durationSec = if (o.isNull("duration")) null else o.optDouble("duration"),
        thumbnail = o.optString("thumbnail").takeIf { it.startsWith("http") },
        pageUrl = o.optString("page_url"),
    )

    /** Проверка: сервер живой и ключ подходит. Возвращает список источников. */
    suspend fun check(): List<String> = withContext(Dispatchers.IO) {
        val o = JSONObject(call("/health", 8_000))
        call("/library", 8_000) // проверка ключа
        o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    }

    suspend fun search(query: String, source: String, limit: Int = 25): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val o = JSONObject(call("/search?q=${enc(query)}&source=$source&limit=$limit"))
        val a = o.optJSONArray("items") ?: JSONArray()
        (0 until a.length()).map { track(a.getJSONObject(it), source) }.filter { it.id.isNotBlank() }
    }

    /** Разобрать ссылку: один трек или плейлист. */
    suspend fun open(url: String): Pair<OnlineTrack?, OnlinePlaylist?> = withContext(Dispatchers.IO) {
        val single = runCatching { JSONObject(call("/resolve?url=${enc(url)}", 60_000)) }
        val o = single.getOrNull()
        if (o != null && o.optString("source").isNotBlank() && o.optString("id").isNotBlank()) {
            return@withContext track(o) to null
        }
        val pl = JSONObject(call("/playlist?url=${enc(url)}&limit=200", 90_000))
        val a = pl.optJSONArray("items") ?: JSONArray()
        val items = (0 until a.length()).map { track(a.getJSONObject(it)) }.filter { it.id.isNotBlank() && it.source.isNotBlank() }
        if (items.isEmpty()) throw (single.exceptionOrNull() as? OnlineError ?: OnlineError("По ссылке ничего не нашлось"))
        null to OnlinePlaylist(pl.optString("title").ifBlank { "Плейлист" }, items)
    }

    /** Разогреть ссылку на сервере заранее, чтобы следующий трек стартовал без паузы. */
    fun warmUp(uri: String) {
        val (src, id) = parse(uri) ?: return
        runCatching { call("/track/$src/${enc(id)}", 60_000) }
    }

    suspend fun serverLibrary(): List<ServerFile> = withContext(Dispatchers.IO) {
        val o = JSONObject(call("/library", 15_000))
        val a = o.optJSONArray("items") ?: JSONArray()
        (0 until a.length()).map {
            val f = a.getJSONObject(it)
            ServerFile(f.optString("path"), f.optLong("size"), f.optLong("modified"), f.optString("url"))
        }
    }

    // ---------- скачивание в медиатеку телефона ----------

    private fun mimeFor(ext: String) = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "opus", "ogg", "oga" -> "audio/ogg"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().take(120)

    /**
     * Сервер скачивает трек (yt-dlp), а мы копируем файл в «Музыка/Lumen».
     * Возвращает content:// URI нового файла в медиатеке.
     */
    suspend fun download(context: Context, t: TrackEntity, mp3: Boolean, onProgress: (Float) -> Unit): Uri = withContext(Dispatchers.IO) {
        val (src, id) = parse(t.uri) ?: throw OnlineError("Это не онлайн-трек")
        if (!configured) throw OnlineError("Сервер не настроен")
        val c = URL("$server/download/$src/${enc(id)}?fmt=${if (mp3) "mp3" else "orig"}").openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 180_000 // сервер сначала сам скачивает трек
        c.setRequestProperty("X-Api-Key", key)
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val text = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw OnlineError(humanError(msg.ifBlank { "Сервер ответил $code" }), code)
            }
            val disp = c.getHeaderField("Content-Disposition").orEmpty()
            val serverName = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(disp)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            val ext = serverName?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 } ?: if (mp3) "mp3" else "m4a"
            val name = safe("${t.artist} - ${t.title}") + "." + ext
            val total = c.contentLengthLong
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(ext))
                put(MediaStore.Audio.Media.TITLE, t.title)
                put(MediaStore.Audio.Media.ARTIST, t.artist)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lumen")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val target = resolver.insert(collection, values) ?: throw OnlineError("Android не дал создать файл в «Музыке»")
            try {
                resolver.openOutputStream(target)?.use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                } ?: throw OnlineError("Не удалось записать файл")
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(target, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                }
            } catch (e: Exception) {
                runCatching { resolver.delete(target, null, null) }
                throw e
            }
            // Сканер Lumen видит файлы как content://media/external/audio/media/<id>.
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(target))
        } catch (e: OnlineError) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw OnlineError("Сервер слишком долго скачивает трек — попробуй ещё раз")
        } catch (e: IOException) {
            throw OnlineError("Не удалось скачать: ${e.message ?: "ошибка сети"}")
        } finally {
            c.disconnect()
        }
    }

    /** Насколько найденный трек похож на то, что искали (для «Для тебя» и плейлистов из Deezer). */
    fun matchScore(o: OnlineTrack, artist: String, title: String): Double {
        val (t, a) = cleanNames(o.source, o.title, o.artist)
        val nt = app.lumen.player.ui.normalize(t)
        val na = app.lumen.player.ui.normalize(a + " " + o.title)
        val wantT = app.lumen.player.ui.normalize(title).split(' ').filter { it.length > 1 }
        val wantA = app.lumen.player.ui.normalize(artist).split(' ').filter { it.length > 1 }
        val tHit = if (wantT.isEmpty()) 0.0 else wantT.count { nt.contains(it) || na.contains(it) }.toDouble() / wantT.size
        val aHit = if (wantA.isEmpty()) 0.0 else wantA.count { na.contains(it) }.toDouble() / wantA.size
        var s = tHit * 2 + aHit
        val low = o.title.lowercase()
        // Каверы, ремиксы, ускоренные версии — только если их и искали.
        listOf("cover", "remix", "sped up", "slowed", "nightcore", "karaoke", "instrumental", "8d", "reverb").forEach { w ->
            if (w in low && w !in title.lowercase()) s -= 0.8
        }
        val d = o.durationSec ?: 0.0
        if (d in 1.0..60.0 || d > 1200) s -= 1.0
        return s
    }
}

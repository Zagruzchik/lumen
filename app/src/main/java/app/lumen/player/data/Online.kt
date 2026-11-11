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
    /** Встроенный движок (NewPipe Extractor) или свой сервер music-api. */
    @Volatile var builtin: Boolean = true
        private set
    @Volatile private var preferOpus: Boolean = false
    @Volatile private var original: Boolean = true

    /** Обложки онлайн-треков: uri → адрес картинки (для мгновенного показа без похода в базу). */
    val thumbs = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun configure(p: Prefs) {
        server = p.onlineServer.trim().trimEnd('/')
        key = p.onlineKey.trim()
        builtin = p.onlineMode != "server"
        viaServer = !builtin && p.onlineViaServer
        preferOpus = p.onlineOpus
        original = p.onlineOriginal
    }

    val configured: Boolean get() = builtin || (server.isNotBlank() && key.isNotBlank())

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

    /** id в пути запроса к серверу: «/» оставляем как есть (у SoundCloud id — это путь страницы). */
    private fun encPath(s: String) = enc(s).replace("%2F", "/")

    /** Адрес потока для плеера. SoundCloud отдаёт HLS — его либо играет ExoPlayer, либо сервер перегоняет в MP3. */
    fun streamUrl(uri: String, forceProxy: Boolean = false): String? {
        val (src, id) = parse(uri) ?: return null
        if (builtin || server.isBlank()) return null
        val proxy = forceProxy || viaServer
        return "$server/stream/$src/${encPath(id)}?key=${enc(key)}" + if (proxy) "&proxy=1" else ""
    }

    /** HLS ли это (нужно ExoPlayer, чтобы выбрать правильный разборщик). */
    fun isHls(uri: String, forceProxy: Boolean = false): Boolean =
        if (builtin) parse(uri)?.first == "soundcloud"
        else !(forceProxy || viaServer) && parse(uri)?.first == "soundcloud"

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
        if (builtin) {
            NewPipeEngine.search("lumen", "soundcloud", 1)
            return@withContext listOf("soundcloud", "youtube", "bandcamp")
        }
        val o = JSONObject(call("/health", 8_000))
        call("/library", 8_000) // проверка ключа
        o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    }

    suspend fun search(query: String, source: String, limit: Int = 25): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (builtin) return@withContext NewPipeEngine.search(query, source, limit)
        val o = JSONObject(call("/search?q=${enc(query)}&source=$source&limit=$limit"))
        val a = o.optJSONArray("items") ?: JSONArray()
        (0 until a.length()).map { track(a.getJSONObject(it), source) }.filter { it.id.isNotBlank() }
    }

    /** Подборка по запросу: готовые плейлисты YouTube Music, потом SoundCloud, в крайнем случае — поиск треков. */
    suspend fun mix(query: String, source: String, max: Int): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (builtin) {
            for (src in listOf("youtube", "soundcloud")) {
                val r = runCatching { NewPipeEngine.playlistMix(query, src, max) }.getOrNull().orEmpty()
                if (r.isNotEmpty()) return@withContext r
            }
        }
        search(query, source, max)
    }

    /** Разобрать ссылку: один трек или плейлист. */
    suspend fun open(url: String): Pair<OnlineTrack?, OnlinePlaylist?> = withContext(Dispatchers.IO) {
        if (builtin) return@withContext NewPipeEngine.open(url)
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
        if (builtin) {
            runCatching { NewPipeEngine.resolve(uri) }
            return
        }
        val (src, id) = parse(uri) ?: return
        runCatching { call("/track/$src/${encPath(id)}", 60_000) }
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
    /** Что получилось скачать: файл в медиатеке и его формат (для подсказки). */
    data class Downloaded(val uri: Uri, val format: String)

    suspend fun download(context: Context, t: TrackEntity, mp3: Boolean, onProgress: (Float) -> Unit): Downloaded {
        if (builtin) return downloadBuiltin(context, t, onProgress)
        val u = downloadServer(context, t, mp3, onProgress)
        return Downloaded(u, if (mp3) "MP3" else "оригинал")
    }

    /** WebM (Opus) → OGG (Opus): тот же звук, другой контейнер. Android 10+. */
    private fun remuxToOgg(src: java.io.File, dst: java.io.File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val ex = android.media.MediaExtractor()
        var mux: android.media.MediaMuxer? = null
        return try {
            ex.setDataSource(src.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == android.media.MediaFormat.MIMETYPE_AUDIO_OPUS
            } ?: return false
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val m = android.media.MediaMuxer(dst.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            mux = m
            val out = m.addTrack(fmt)
            m.start()
            val size = if (fmt.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = java.nio.ByteBuffer.allocate(maxOf(size, 256 * 1024))
            val info = android.media.MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, 0)
                m.writeSampleData(out, buf, info)
                samples++
                ex.advance()
            }
            m.stop()
            samples > 0 && dst.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { mux?.release() }
            ex.release()
        }
    }

    /** Новый файл в «Музыка/Lumen»; writer пишет содержимое. Возвращает URI в том виде, как его видит сканер. */
    private fun saveToMusic(context: Context, t: TrackEntity, ext: String, writer: (java.io.OutputStream) -> Unit): Uri {
        if (Build.VERSION.SDK_INT < 29) throw OnlineError("Скачивание работает на Android 10 и новее")
        val name = safe("${t.artist} - ${t.title}") + "." + ext
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(ext))
            put(MediaStore.Audio.Media.TITLE, t.title)
            put(MediaStore.Audio.Media.ARTIST, t.artist)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lumen")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw OnlineError("Android не дал создать файл в «Музыке»")
        try {
            resolver.openOutputStream(target)?.use(writer) ?: throw OnlineError("Не удалось записать файл")
            resolver.update(target, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(target))
    }

    /** Открыть HTTP-ответ (с диапазоном байт). */
    private fun httpOpen(url: String, headers: Map<String, String>, from: Long = 0, to: Long = -1): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 30_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (from > 0 || to >= 0) c.setRequestProperty("Range", "bytes=$from-" + if (to >= 0) "$to" else "")
        return c
    }

    /**
     * Скачивание файлом кусками по 4 МБ: так CDN YouTube отдаёт быстро и без «тормозов».
     * Если сервер не понимает диапазоны — просто читаем всё подряд.
     */
    private fun fetchRanged(url: String, headers: Map<String, String>, out: java.io.OutputStream, onProgress: (Float) -> Unit) {
        val chunk = 4L * 1024 * 1024
        var pos = 0L
        var total = -1L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val c = httpOpen(url, headers, pos, pos + chunk - 1)
            try {
                val code = c.responseCode
                if (code == 416) return
                if (code !in 200..299) throw OnlineError("Источник ответил $code — попробуй ещё раз")
                if (code == 206) {
                    c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { total = it }
                } else {
                    total = c.contentLengthLong
                }
                var got = 0L
                c.inputStream.use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        pos += n
                        if (total > 0) onProgress((pos.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                if (code == 200 || got == 0L || (total > 0 && pos >= total) || got < chunk) return
            } finally {
                c.disconnect()
            }
        }
    }

    /** HLS (SoundCloud): склеиваем сегменты в один файл — для MP3 это обычный MP3. */
    private fun fetchHls(url: String, headers: Map<String, String>, out: java.io.OutputStream, onProgress: (Float) -> Unit) {
        fun text(u: String): String {
            val c = httpOpen(u, headers)
            try {
                if (c.responseCode !in 200..299) throw OnlineError("Источник ответил ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        var base = url
        var body = text(url)
        // Мастер-плейлист — берём первый вариант.
        if ("#EXT-X-STREAM-INF" in body) {
            val v = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: throw OnlineError("Пустой плейлист")
            base = URL(URL(url), v.trim()).toString()
            body = text(base)
        }
        val init = Regex("#EXT-X-MAP:.*URI=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val segs = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segs.isEmpty()) throw OnlineError("Пустой поток")
        val all = listOfNotNull(init) + segs
        val buf = ByteArray(64 * 1024)
        all.forEachIndexed { i, seg ->
            val c = httpOpen(URL(URL(base), seg).toString(), headers)
            try {
                if (c.responseCode !in 200..299) throw OnlineError("Сегмент не скачался (${c.responseCode})")
                c.inputStream.use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } finally {
                c.disconnect()
            }
            onProgress((i + 1f) / all.size)
        }
    }

    private suspend fun downloadBuiltin(context: Context, t: TrackEntity, onProgress: (Float) -> Unit): Downloaded = withContext(Dispatchers.IO) {
        try {
            // 1. SoundCloud: оригинальный файл автора, если он разрешил скачивание (бывает FLAC/WAV).
            if (original && parse(t.uri)?.first == "soundcloud") {
                val orig = runCatching { NewPipeEngine.soundcloudOriginal(t.uri) }.getOrNull()
                if (orig != null) {
                    val probe = httpOpen(orig, mapOf("User-Agent" to NewPipeEngine.DESKTOP_UA))
                    val disp = try { probe.responseCode; probe.getHeaderField("Content-Disposition").orEmpty() } finally { probe.disconnect() }
                    val fname = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE).find(disp)?.groupValues?.get(1)
                        ?.let { URLDecoder.decode(it, "UTF-8") }
                    val ext = (fname ?: Uri.parse(orig).lastPathSegment.orEmpty()).substringAfterLast('.', "").lowercase()
                        .takeIf { it in setOf("flac", "wav", "aiff", "aif", "mp3", "m4a", "aac", "ogg", "opus", "alac") } ?: "mp3"
                    val uri = saveToMusic(context, t, ext) { out -> fetchRanged(orig, mapOf("User-Agent" to NewPipeEngine.DESKTOP_UA), out, onProgress) }
                    return@withContext Downloaded(uri, "${ext.uppercase()} · оригинал автора")
                }
            }
            // 2. Лучший поток, удобный для файла.
            var st = NewPipeEngine.downloadStream(t.uri, preferOpus)
            // Opus в WebM Android в «Музыку» не пускает — перекладываем звук в OGG без перекодирования.
            if (!st.hls && (st.ext == "webm" || st.ext == "opus")) {
                val tmp = java.io.File(context.cacheDir, "dl_${System.nanoTime()}.webm")
                val ogg = java.io.File(context.cacheDir, "dl_${System.nanoTime()}.ogg")
                try {
                    tmp.outputStream().use { out -> fetchRanged(st.url, st.headers, out) { p -> onProgress(p * 0.95f) } }
                    if (remuxToOgg(tmp, ogg)) {
                        val uri = saveToMusic(context, t, "ogg") { out -> ogg.inputStream().use { it.copyTo(out, 256 * 1024) } }
                        onProgress(1f)
                        val kbps = if (st.bitrate > 0) " · ${st.bitrate} кбит/с" else ""
                        return@withContext Downloaded(uri, "OPUS$kbps")
                    }
                } finally {
                    tmp.delete()
                    ogg.delete()
                }
                // Не вышло — берём M4A (AAC), его Android принимает всегда.
                st = NewPipeEngine.downloadStreamOf(t.uri, "m4a") ?: throw OnlineError("Не удалось сохранить: нет подходящего формата")
            }
            val ext = when {
                st.hls && st.ext == "m4a" -> "m4a"
                st.hls -> "mp3"
                st.ext == "webm" || st.ext == "opus" -> "webm"
                else -> st.ext.ifBlank { "m4a" }
            }
            val uri = saveToMusic(context, t, ext) { out ->
                if (st.hls) fetchHls(st.url, st.headers, out, onProgress) else fetchRanged(st.url, st.headers, out, onProgress)
            }
            val kbps = if (st.bitrate > 0) " · ${st.bitrate} кбит/с" else ""
            Downloaded(uri, ext.uppercase().replace("WEBM", "OPUS") + kbps)
        } catch (e: OnlineError) {
            throw e
        } catch (e: IOException) {
            throw OnlineError("Не удалось скачать: ${e.message ?: "ошибка сети"}")
        } catch (e: Throwable) {
            throw OnlineError(NewPipeEngine.friendly(e))
        }
    }

    private suspend fun downloadServer(context: Context, t: TrackEntity, mp3: Boolean, onProgress: (Float) -> Unit): Uri = withContext(Dispatchers.IO) {
        val (src, id) = parse(t.uri) ?: throw OnlineError("Это не онлайн-трек")
        if (!configured) throw OnlineError("Сервер не настроен")
        val c = URL("$server/download/$src/${encPath(id)}?fmt=${if (mp3) "mp3" else "orig"}").openConnection() as HttpURLConnection
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

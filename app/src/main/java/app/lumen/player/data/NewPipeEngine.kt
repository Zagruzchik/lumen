package app.lumen.player.data

import android.net.Uri
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.soundcloud.SoundcloudParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Запросы библиотеки NewPipe Extractor — обычным HttpURLConnection, без лишних зависимостей. */
private class LumenDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val c = URL(request.url()).openConnection() as HttpURLConnection
        c.requestMethod = request.httpMethod()
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", NewPipeEngine.DESKTOP_UA)
        request.headers().forEach { (k, values) ->
            if (values.isNotEmpty()) c.setRequestProperty(k, values.joinToString(", "))
        }
        val body = request.dataToSend()
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body) }
        }
        try {
            val code = c.responseCode
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val headers = c.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value }
            return Response(code, c.responseMessage ?: "", headers, text, c.url.toString())
        } finally {
            c.disconnect()
        }
    }
}

/** Готовый к проигрыванию поток. */
data class ResolvedStream(
    val url: String,
    val hls: Boolean,
    val headers: Map<String, String>,
    val ext: String,
    val bitrate: Int,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Встроенный онлайн-движок: поиск и потоки SoundCloud, YouTube (Music) и Bandcamp прямо в приложении,
 * через NewPipe Extractor. Ни сервера, ни Termux не нужно.
 */
object NewPipeEngine {
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    @Volatile private var ready = false

    @Synchronized
    fun ensureInit() {
        if (ready) return
        val loc = Locale.getDefault()
        val country = loc.country.ifBlank { "KZ" }
        NewPipe.init(LumenDownloader(), Localization(loc.language.ifBlank { "ru" }, country), ContentCountry(country))
        ready = true
    }

    fun service(source: String): StreamingService = when (source) {
        "youtube" -> ServiceList.YouTube
        "bandcamp" -> ServiceList.Bandcamp
        else -> ServiceList.SoundCloud
    }

    private fun sourceOf(s: StreamingService): String = when (s.serviceId) {
        ServiceList.YouTube.serviceId -> "youtube"
        ServiceList.Bandcamp.serviceId -> "bandcamp"
        else -> "soundcloud"
    }

    /** Ссылка трека → наш id. YouTube — id видео, остальные — путь страницы. */
    private fun idOf(source: String, url: String): String {
        if (source == "youtube") {
            return runCatching { ServiceList.YouTube.streamLHFactory.getId(url) }.getOrNull()
                ?: Uri.parse(url).getQueryParameter("v") ?: url
        }
        val u = Uri.parse(url)
        return if (source == "soundcloud") (u.path ?: "").trim('/') else url
    }

    /** Наш id → ссылка, которую понимает NewPipe. */
    fun urlOf(source: String, id: String): String = when (source) {
        "youtube" -> "https://www.youtube.com/watch?v=$id"
        "soundcloud" -> if (id.all { it.isDigit() }) "https://api.soundcloud.com/tracks/$id" else "https://soundcloud.com/$id"
        else -> id
    }

    private fun bestThumb(item: InfoItem): String? {
        val list = item.thumbnails
        if (list.isEmpty()) return null
        val sized = list.filter { it.height in 200..800 }
        return (sized.maxByOrNull { it.height } ?: list.maxByOrNull { it.height } ?: list.first()).url
    }

    private fun toTrack(source: String, item: StreamInfoItem): OnlineTrack = OnlineTrack(
        source = source,
        id = idOf(source, item.url),
        title = item.name,
        artist = item.uploaderName.orEmpty(),
        durationSec = item.duration.takeIf { it > 0 }?.toDouble(),
        thumbnail = bestThumb(item),
        pageUrl = item.url,
    )

    /** Поиск. Для YouTube — сначала каталог YouTube Music (чистые названия и исполнители). */
    fun search(query: String, source: String, limit: Int): List<OnlineTrack> {
        ensureInit()
        val s = service(source)
        val filters = when (source) {
            "youtube" -> listOf("music_songs", "videos")
            "soundcloud" -> listOf("tracks", "")
            else -> listOf("")
        }
        var lastError: Throwable? = null
        for (f in filters) {
            try {
                val qh = s.searchQHFactory.fromQuery(query, if (f.isEmpty()) emptyList() else listOf(f), "")
                val info = SearchInfo.getInfo(s, qh)
                val items = info.relatedItems.filterIsInstance<StreamInfoItem>()
                    .filter { it.duration != 0L }
                    .map { toTrack(source, it) }
                    .filter { it.id.isNotBlank() }
                if (items.isNotEmpty()) return items.take(limit)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        if (lastError != null) throw OnlineError(friendly(lastError))
        return emptyList()
    }

    fun friendly(e: Throwable): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is ReCaptchaException -> "Сервис просит капчу — попробуй позже или смени сеть"
            "sign in" in m || "bot" in m -> "YouTube просит подтвердить, что ты не робот. Попробуй позже или через другую сеть"
            "geo" in m || "country" in m -> "Трек недоступен в твоей стране"
            "private" in m -> "Трек закрыт автором"
            "age" in m -> "Трек с ограничением по возрасту"
            "unavailable" in m || "not available" in m || "404" in m -> "Трек удалён или недоступен"
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "Нет интернета"
            else -> e.message?.take(160) ?: "Ошибка сети"
        }
    }

    // ---------- потоки ----------

    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun invalidate(uri: String) { cache.remove(uri) }

    private fun ttl(source: String) = if (source == "youtube") 2 * 3600_000L else 15 * 60_000L

    /** Заголовки для CDN YouTube: у каждого клиента — свой User-Agent, иначе 403. */
    private fun headersFor(url: String): Map<String, String> = when {
        YoutubeParsingHelper.isAndroidStreamingUrl(url) -> mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(null))
        YoutubeParsingHelper.isIosStreamingUrl(url) -> mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(null))
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
        url.contains("googlevideo.com") -> mapOf(
            "User-Agent" to DESKTOP_UA,
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/",
        )
        else -> mapOf("User-Agent" to DESKTOP_UA)
    }

    private fun usable(a: AudioStream) = a.isUrl && a.content.isNotBlank() &&
        (a.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || a.deliveryMethod == DeliveryMethod.HLS)

    /**
     * Лучший поток для проигрывания. forDownload — выбираем то, что удобно сохранить файлом
     * (M4A у YouTube, MP3 у SoundCloud).
     */
    fun streams(uri: String): Pair<StreamInfo, List<AudioStream>> {
        ensureInit()
        val (src, id) = Online.parse(uri) ?: throw OnlineError("Неверный онлайн-трек")
        val info = try {
            StreamInfo.getInfo(service(src), urlOf(src, id))
        } catch (e: Throwable) {
            throw OnlineError(friendly(e))
        }
        val list = info.audioStreams.filter(::usable)
        if (list.isEmpty()) throw OnlineError("У трека нет доступного звука")
        return info to list
    }

    private fun score(a: AudioStream, forDownload: Boolean, preferOpus: Boolean): Int {
        val fmt = a.format?.suffix.orEmpty()
        val progressive = a.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        var s = a.averageBitrate.coerceAtLeast(0)
        if (progressive) s += 1000
        if (forDownload) {
            when (fmt) {
                "m4a" -> s += if (preferOpus) 0 else 400
                "webm", "opus" -> s += if (preferOpus) 400 else 0
                "mp3" -> s += 300
            }
        }
        return s
    }

    fun resolve(uri: String): ResolvedStream {
        val (src, _) = Online.parse(uri) ?: throw OnlineError("Неверный онлайн-трек")
        cache[uri]?.let { if (System.currentTimeMillis() - it.at < ttl(src)) return it }
        val lock = locks.getOrPut(uri) { Any() }
        synchronized(lock) {
            cache[uri]?.let { if (System.currentTimeMillis() - it.at < ttl(src)) return it }
            val (_, list) = streams(uri)
            val best = list.maxBy { score(it, forDownload = false, preferOpus = true) }
            val r = ResolvedStream(
                url = best.content,
                hls = best.deliveryMethod == DeliveryMethod.HLS,
                headers = headersFor(best.content),
                ext = best.format?.suffix ?: "m4a",
                bitrate = best.averageBitrate,
            )
            cache[uri] = r
            return r
        }
    }

    /** Поток, который удобно скачать файлом. */
    /** Лучший поток в нужном формате (например, m4a, если Opus не получилось сохранить). */
    fun downloadStreamOf(uri: String, suffix: String): ResolvedStream? {
        val (_, list) = streams(uri)
        val best = list.filter { it.format?.suffix == suffix && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate } ?: return null
        return ResolvedStream(best.content, false, headersFor(best.content), suffix, best.averageBitrate)
    }

    fun downloadStream(uri: String, preferOpus: Boolean): ResolvedStream {
        val (_, list) = streams(uri)
        val best = list.maxBy { score(it, forDownload = true, preferOpus = preferOpus) }
        return ResolvedStream(best.content, best.deliveryMethod == DeliveryMethod.HLS, headersFor(best.content), best.format?.suffix ?: "m4a", best.averageBitrate)
    }

    // ---------- ссылки и плейлисты ----------

    fun open(url: String): Pair<OnlineTrack?, OnlinePlaylist?> {
        ensureInit()
        val s = try {
            NewPipe.getServiceByUrl(url)
        } catch (e: Throwable) {
            throw OnlineError("Эту ссылку Lumen не понимает — нужен SoundCloud, YouTube или Bandcamp")
        }
        val source = sourceOf(s)
        try {
            return when (s.getLinkTypeByUrl(url)) {
                StreamingService.LinkType.STREAM -> {
                    val info = StreamInfo.getInfo(s, url)
                    val thumb = info.thumbnails.filter { it.height in 200..800 }.maxByOrNull { it.height }?.url
                        ?: info.thumbnails.maxByOrNull { it.height }?.url
                    OnlineTrack(
                        source = source,
                        id = idOf(source, info.url),
                        title = info.name,
                        artist = info.uploaderName.orEmpty(),
                        durationSec = info.duration.takeIf { it > 0 }?.toDouble(),
                        thumbnail = thumb,
                        pageUrl = info.url,
                    ) to null
                }
                StreamingService.LinkType.PLAYLIST -> {
                    val info = PlaylistInfo.getInfo(s, url)
                    val items = ArrayList<StreamInfoItem>(info.relatedItems)
                    var page = info.nextPage
                    while (page != null && items.size < 500) {
                        val more = PlaylistInfo.getMoreItems(s, url, page)
                        items += more.items
                        page = if (more.hasNextPage()) more.nextPage else null
                    }
                    null to OnlinePlaylist(info.name.ifBlank { "Плейлист" }, items.map { toTrack(source, it) }.filter { it.id.isNotBlank() })
                }
                else -> throw OnlineError("Это не трек и не плейлист")
            }
        } catch (e: OnlineError) {
            throw e
        } catch (e: Throwable) {
            throw OnlineError(friendly(e))
        }
    }

    // ---------- подборки: готовые плейлисты под настроение ----------

    /**
     * Ищет плейлисты по запросу (YouTube Music / SoundCloud) и берёт треки из первого подходящего.
     * Так «Для бега» — это настоящая подборка для бега, а не песни со словом «бег» в названии.
     */
    fun playlistMix(query: String, source: String, maxItems: Int): List<OnlineTrack> {
        ensureInit()
        val s = service(source)
        val filter = when (source) {
            "youtube" -> "music_playlists"
            "soundcloud" -> "playlists"
            else -> return emptyList()
        }
        val found = runCatching {
            SearchInfo.getInfo(s, s.searchQHFactory.fromQuery(query, listOf(filter), "")).relatedItems
                .filterIsInstance<PlaylistInfoItem>()
                .filter { it.streamCount < 0 || it.streamCount >= 8 }
        }.getOrElse { throw OnlineError(friendly(it)) }
        for (pl in found.take(3)) {
            val url = pl.url.replace("music.youtube.com", "www.youtube.com")
            val items = runCatching {
                val info = PlaylistInfo.getInfo(s, url)
                val all = ArrayList<StreamInfoItem>(info.relatedItems)
                if (all.size < maxItems && info.hasNextPage()) {
                    runCatching { all += PlaylistInfo.getMoreItems(s, url, info.nextPage).items }
                }
                all
            }.getOrNull().orEmpty()
                .filter { it.duration == -1L || it.duration in 60..900 }
                .map { toTrack(source, it) }
                .filter { it.id.isNotBlank() }
            if (items.size >= 8) return items.take(maxItems)
        }
        return emptyList()
    }

    // ---------- SoundCloud: оригинальный файл автора (бывает FLAC/WAV) ----------

    /** Ссылка на оригинал, если автор разрешил скачивание, иначе null. */
    fun soundcloudOriginal(uri: String): String? {
        ensureInit()
        val (src, id) = Online.parse(uri) ?: return null
        if (src != "soundcloud") return null
        val cid = SoundcloudParsingHelper.clientId()
        val dl = NewPipe.getDownloader()
        val track = if (id.all { it.isDigit() }) {
            JSONObject(dl.get("https://api-v2.soundcloud.com/tracks/$id?client_id=$cid").responseBody())
        } else {
            val page = Uri.encode("https://soundcloud.com/$id")
            JSONObject(dl.get("https://api-v2.soundcloud.com/resolve?url=$page&client_id=$cid").responseBody())
        }
        if (!track.optBoolean("downloadable") || !track.optBoolean("has_downloads_left", true)) return null
        val tid = track.optLong("id").takeIf { it > 0 } ?: return null
        val r = dl.get("https://api-v2.soundcloud.com/tracks/$tid/download?client_id=$cid")
        if (r.responseCode() !in 200..299) return null
        return runCatching { JSONObject(r.responseBody()).optString("redirectUri") }.getOrNull()?.takeIf { it.startsWith("http") }
    }
}

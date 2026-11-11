package app.lumen.player.data

import app.lumen.player.ui.normalize
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Что удалось узнать о треке в каталогах: альбом, год, номер, жанр, квадратная обложка. */
data class TrackMeta(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val genre: String,
    val cover: String?,
)

/**
 * Поиск настоящего альбома для онлайн-трека: сначала iTunes (там же жанр и год), потом Deezer.
 * Совпадение проверяется по исполнителю, названию и длительности — чтобы не подставить чужой альбом.
 */
object MetaLookup {
    // ConcurrentHashMap не хранит null, поэтому «не нашли» помним отдельно.
    private val cache = ConcurrentHashMap<String, TrackMeta>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    private fun http(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Lumen/1.0 (Android music player)")
        return try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun words(s: String) = normalize(s.substringBefore(" (").substringBefore(" [").substringBefore(" feat"))
        .split(' ').filter { it.length > 1 }

    /** Насколько найденное похоже на искомое: 0..3. */
    private fun score(wantArtist: String, wantTitle: String, wantMs: Long, artist: String, title: String, ms: Long): Double {
        val wt = words(wantTitle)
        val wa = words(wantArtist)
        val t = normalize(title)
        val a = normalize(artist)
        val tHit = if (wt.isEmpty()) 0.0 else wt.count { t.contains(it) }.toDouble() / wt.size
        val aHit = if (wa.isEmpty()) 0.5 else wa.count { a.contains(it) }.toDouble() / wa.size
        var s = tHit * 1.5 + aHit
        if (wantMs > 0 && ms > 0) {
            val d = kotlin.math.abs(wantMs - ms)
            s += when {
                d < 3000 -> 0.5
                d < 8000 -> 0.2
                d > 30_000 -> -1.0
                else -> 0.0
            }
        }
        return s
    }

    private fun cleanAlbum(a: String) = a.replace(Regex("\\s*-\\s*(Single|EP)$", RegexOption.IGNORE_CASE), "").trim()

    private fun itunes(artist: String, title: String, ms: Long): TrackMeta? {
        val body = http("https://itunes.apple.com/search?term=${enc("$artist $title")}&entity=song&limit=8") ?: return null
        val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return null
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sc = score(artist, title, ms, o.optString("artistName"), o.optString("trackName"), o.optLong("trackTimeMillis"))
            if (sc > bestScore) { bestScore = sc; best = o }
        }
        val o = best ?: return null
        if (bestScore < 1.9) return null
        return TrackMeta(
            title = o.optString("trackName"),
            artist = o.optString("artistName"),
            album = cleanAlbum(o.optString("collectionName")),
            albumArtist = o.optString("collectionArtistName").ifBlank { o.optString("artistName") },
            year = o.optString("releaseDate").take(4).toIntOrNull() ?: 0,
            trackNo = o.optInt("trackNumber"),
            discNo = o.optInt("discNumber", 1),
            genre = o.optString("primaryGenreName"),
            cover = o.optString("artworkUrl100").takeIf { it.startsWith("http") }?.replace("100x100bb", "600x600bb"),
        )
    }

    private fun deezer(artist: String, title: String, ms: Long): TrackMeta? {
        val body = http("https://api.deezer.com/search?q=${enc("artist:\"$artist\" track:\"$title\"")}&limit=8")
            ?: return null
        val arr = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return null
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sc = score(artist, title, ms, o.optJSONObject("artist")?.optString("name").orEmpty(), o.optString("title"), o.optLong("duration") * 1000)
            if (sc > bestScore) { bestScore = sc; best = o }
        }
        val o = best ?: return null
        if (bestScore < 1.9) return null
        val al = o.optJSONObject("album")
        return TrackMeta(
            title = o.optString("title"),
            artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
            album = cleanAlbum(al?.optString("title").orEmpty()),
            albumArtist = o.optJSONObject("artist")?.optString("name").orEmpty(),
            year = 0,
            trackNo = 0,
            discNo = 1,
            genre = "",
            cover = al?.optString("cover_xl")?.takeIf { it.startsWith("http") },
        )
    }

    fun find(artist: String, title: String, durationMs: Long): TrackMeta? {
        val key = normalize("$artist|$title")
        cache[key]?.let { return it }
        if (key in misses) return null
        val r = runCatching { itunes(artist, title, durationMs) }.getOrNull()
            ?: runCatching { deezer(artist, title, durationMs) }.getOrNull()
        if (r != null) cache[key] = r else misses += key
        return r
    }

    /** Онлайн-трек с настоящим альбомом, годом, жанром и квадратной обложкой. */
    fun enrich(t: TrackEntity): TrackEntity? {
        val m = find(t.artist, t.title, t.durationMs) ?: return null
        if (m.album.isBlank()) return null
        val page = Online.pageOf(t).orEmpty()
        val cover = m.cover ?: Online.thumbOf(t)
        cover?.let { Online.thumbs[t.uri] = it }
        return t.copy(
            album = m.album,
            albumArtist = m.albumArtist.ifBlank { t.artist },
            year = if (m.year > 0) m.year else t.year,
            trackNo = m.trackNo,
            discNo = m.discNo,
            path = (cover ?: "") + "\n" + page,
            genre = m.genre.ifBlank { t.genre },
        )
    }

    /** Онлайн-трек ещё с «альбомом»-источником (SoundCloud/YouTube) — значит, альбом не искали. */
    fun needsEnrich(t: TrackEntity) = Online.isOnline(t.uri) && (t.album.isBlank() || t.album == Online.labelOf(t.uri))
}

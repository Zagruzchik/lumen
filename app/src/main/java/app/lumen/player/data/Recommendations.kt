package app.lumen.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Плитка рекомендации: трек, альбом или исполнитель, которых нет в библиотеке. */
data class RecItem(
    val kind: Int,          // KIND_TRACK / KIND_ALBUM / KIND_ARTIST
    val title: String,
    val artist: String,
    val cover: String,
    val reason: String,
) {
    /** Что искать при нажатии. */
    val query: String
        get() = when (kind) {
            KIND_ARTIST -> title
            else -> "$artist — $title"
        }
    val key: String get() = "$kind|${title.lowercase()}|${artist.lowercase()}"

    companion object {
        const val KIND_TRACK = 0
        const val KIND_ALBUM = 1
        const val KIND_ARTIST = 2
    }
}

data class RecSection(val title: String, val subtitle: String, val style: Int, val items: List<RecItem>) {
    companion object {
        const val STYLE_TILES = 0
        const val STYLE_BIG = 1
        const val STYLE_ARTISTS = 2
    }
}

data class RecsState(
    val sections: List<RecSection> = emptyList(),
    val loading: Boolean = false,
    val updatedAt: Long = 0,
    val offline: Boolean = false,
)

/**
 * Рекомендации на основе открытого API Deezer (без ключа).
 * Опорные исполнители — из твоей статистики, любимого и библиотеки.
 * Всё, что уже есть в библиотеке, отфильтровывается. Результат кэшируется в файл.
 */
class RecommendationsRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val prefs: Prefs,
) {
    private val cacheFile = File(context.filesDir, "recs.json")
    private val api = "https://api.deezer.com"

    fun cached(): RecsState? = runCatching {
        if (!cacheFile.exists()) return null
        val root = JSONObject(cacheFile.readText())
        val hidden = prefs.hiddenRecs
        val hiddenArtists = prefs.hiddenArtists
        val sections = parseSections(root.getJSONArray("sections")).map { sec ->
            sec.copy(items = sec.items.filter { it.key !in hidden && artistKey(artistOf(it)) !in hiddenArtists })
        }.filter { it.items.isNotEmpty() }
        RecsState(sections = sections, updatedAt = root.optLong("updatedAt"))
    }.getOrNull()

    /** Ключ исполнителя для «не показывать исполнителя». */
    fun artistKey(name: String): String = norm(name)

    private fun artistOf(item: RecItem) = if (item.kind == RecItem.KIND_ARTIST) item.title else item.artist

    suspend fun build(onProgress: (List<RecSection>) -> Unit): RecsState = withContext(Dispatchers.IO) {
        val local = dao.allTracksOnce()
        val localTracks = local.mapTo(HashSet()) { key(it.artist, it.title) }
        val localAlbums = local.mapTo(HashSet()) { key(it.albumArtist, it.album) }
        val localArtists = local.flatMapTo(HashSet()) { listOf(norm(it.artist), norm(it.albumArtist)) }
        val hidden = prefs.hiddenRecs
        val hiddenArtists = prefs.hiddenArtists
        val seen = HashSet<String>()
        val perArtist = HashMap<String, Int>()

        fun fresh(item: RecItem): Boolean {
            if (item.key in hidden) return false
            if (artistKey(artistOf(item)) in hiddenArtists) return false
            if (!seen.add(item.key)) return false
            return when (item.kind) {
                RecItem.KIND_TRACK -> key(item.artist, item.title) !in localTracks
                RecItem.KIND_ALBUM -> key(item.artist, item.title) !in localAlbums
                else -> norm(item.title) !in localArtists
            }
        }

        /** Не больше [cap] вещей одного исполнителя: лента не превращается в один альбом. */
        fun room(artist: String, cap: Int, counts: HashMap<String, Int> = perArtist): Boolean {
            val k = norm(artist)
            val n = counts[k] ?: 0
            if (n >= cap) return false
            counts[k] = n + 1
            return true
        }

        // Сетевые запросы идут параллельно, но не больше четырёх сразу.
        val gate = Semaphore(4)
        suspend fun <T> net(block: () -> T): T = gate.withPermit { withContext(Dispatchers.IO) { block() } }
        suspend fun many(paths: List<String>): List<JSONArray> = coroutineScope {
            paths.map { p -> async { net { getArray(p) } } }.awaitAll()
        }

        // Опорные исполнители: по времени прослушивания, затем любимое, затем самые большие в библиотеке.
        val seeds = LinkedHashSet<String>()
        dao.topArtists(8).forEach { seeds += it.name }
        local.groupingBy { it.artist }.eachCount().entries.sortedByDescending { it.value }.take(10).forEach { seeds += it.key }
        val seedList = seeds.filter { it != UNKNOWN_ARTIST && it.isNotBlank() && artistKey(it) !in hiddenArtists }.take(8)

        val sections = ArrayList<RecSection>()
        fun publish() = onProgress(sections.toList())

        val seedIds = LinkedHashMap<String, Long>()
        coroutineScope {
            seedList.map { s -> async { s to net { searchArtistId(s) } } }.awaitAll()
        }.forEach { (s, id) -> if (id != null) seedIds[s] = id }

        // 1. «Если нравится X» — хиты похожих исполнителей.
        val top3 = seedIds.entries.take(3).toList()
        val relatedOfTop = many(top3.map { "/artist/${it.value}/related?limit=8" })
        for ((idx, entry) in top3.withIndex()) {
            val name = entry.key
            val related = relatedOfTop[idx]
            val candidates = (0 until related.length()).map { related.getJSONObject(it) }
                .filter { norm(it.optString("name")) !in localArtists && artistKey(it.optString("name")) !in hiddenArtists }
                .take(10)
            val tops = many(candidates.map { "/artist/${it.optLong("id")}/top?limit=3" })
            val items = ArrayList<RecItem>()
            for (top in tops) {
                for (j in 0 until top.length()) {
                    val item = trackItem(top.getJSONObject(j), "похоже на $name")
                    if (fresh(item) && room(item.artist, 2)) {
                        items += item
                        break
                    }
                }
            }
            if (items.isNotEmpty()) {
                sections += RecSection("Если нравится $name", "Хиты похожих исполнителей", RecSection.STYLE_TILES, items.take(10))
                publish()
            }
        }

        // 2. Похожие исполнители. Кого называют несколько твоих любимых — тот выше в списке.
        run {
            val top5 = seedIds.entries.take(5).toList()
            val relatedOf = many(top5.map { "/artist/${it.value}/related?limit=6" })
            val votes = LinkedHashMap<String, Pair<JSONObject, MutableList<String>>>()
            relatedOf.forEachIndexed { idx, arr ->
                for (i in 0 until arr.length()) {
                    val ra = arr.getJSONObject(i)
                    val k = norm(ra.optString("name"))
                    if (k.isEmpty()) continue
                    votes.getOrPut(k) { ra to mutableListOf() }.second += top5[idx].key
                }
            }
            val artists = votes.values
                .sortedByDescending { it.second.size }
                .mapNotNull { (ra, from) ->
                    val reason = if (from.size > 1) "как ${from[0]} и ещё ${from.size - 1}" else "как ${from[0]}"
                    RecItem(
                        RecItem.KIND_ARTIST,
                        ra.optString("name"),
                        "",
                        ra.optString("picture_xl").ifBlank { ra.optString("picture_big") },
                        reason,
                    ).takeIf { fresh(it) }
                }
            if (artists.isNotEmpty()) {
                sections += RecSection("Исполнители для тебя", "Похожи на тех, кого ты слушаешь", RecSection.STYLE_ARTISTS, artists.take(16))
                publish()
            }
        }

        // 3. Новые релизы твоих исполнителей: не больше двух альбомов одного исполнителя.
        run {
            val yearAgo = java.time.LocalDate.now().minusDays(400).toString()
            val top8 = seedIds.entries.take(8).toList()
            val albumsOf = many(top8.map { "/artist/${it.value}/albums?limit=40" })
            val albums = ArrayList<Pair<String, RecItem>>()
            top8.forEachIndexed { idx, entry ->
                val arr = albumsOf[idx]
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val date = a.optString("release_date")
                    if (date < yearAgo) continue
                    val item = RecItem(
                        RecItem.KIND_ALBUM,
                        a.optString("title"),
                        entry.key,
                        a.optString("cover_xl").ifBlank { a.optString("cover_big") },
                        "${typeName(a.optString("record_type"))} · ${date.take(4)}",
                    )
                    if (fresh(item)) albums += date to item
                }
            }
            val albumCount = HashMap<String, Int>()
            val picked = albums.sortedByDescending { it.first }.map { it.second }
                .filter { room(it.artist, 2, albumCount) }
                .take(14)
            if (picked.isNotEmpty()) {
                sections += RecSection("Новое у твоих исполнителей", "Свежие релизы, которых нет в библиотеке", RecSection.STYLE_BIG, picked)
                publish()
            }
        }

        // 4. Хиты любимых исполнителей, которых у тебя нет.
        run {
            val top6 = seedIds.entries.take(6).toList()
            val topsOf = many(top6.map { "/artist/${it.value}/top?limit=10" })
            val items = ArrayList<RecItem>()
            top6.forEachIndexed { idx, entry ->
                val top = topsOf[idx]
                var added = 0
                for (j in 0 until top.length()) {
                    if (added >= 2) break
                    val item = trackItem(top.getJSONObject(j), "хит ${entry.key}", fallbackArtist = entry.key)
                    if (fresh(item) && room(item.artist, 3)) {
                        items += item
                        added++
                    }
                }
            }
            if (items.isNotEmpty()) {
                sections += RecSection("Хиты, которых нет у тебя", "Самое популярное у твоих исполнителей", RecSection.STYLE_TILES, items.take(16))
                publish()
            }
        }

        // 5. Сейчас в тренде: в чарте не больше двух треков одного исполнителя.
        run {
            val chart = getArray("/chart/0/tracks?limit=40")
            val items = ArrayList<RecItem>()
            for (i in 0 until chart.length()) {
                val item = trackItem(chart.getJSONObject(i), "#${i + 1} в чарте")
                if (fresh(item) && room(item.artist, 2)) items += item
            }
            if (items.isNotEmpty()) {
                sections += RecSection("Сейчас в тренде", "Мировой чарт Deezer", RecSection.STYLE_TILES, items.take(20))
                publish()
            }
        }

        val state = RecsState(sections = sections, updatedAt = System.currentTimeMillis())
        if (sections.isNotEmpty()) save(state)
        state
    }

    private fun trackItem(t: JSONObject, reason: String, fallbackArtist: String = "") = RecItem(
        RecItem.KIND_TRACK,
        t.optString("title_short").ifBlank { t.optString("title") },
        t.optJSONObject("artist")?.optString("name")?.ifBlank { null } ?: fallbackArtist,
        t.optJSONObject("album")?.optString("cover_xl").orEmpty()
            .ifBlank { t.optJSONObject("album")?.optString("cover_big").orEmpty() },
        reason,
    )

    private fun typeName(t: String) = when (t) {
        "single" -> "Сингл"
        "ep" -> "EP"
        "compile" -> "Сборник"
        else -> "Альбом"
    }

    /** Варианты обложек из Deezer: сначала по альбому, потом по треку. */
    suspend fun findCovers(artist: String, title: String, album: String): List<String> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()
        if (album.isNotBlank()) {
            val a = getArray("/search/album?q=${URLEncoder.encode("$artist $album", "UTF-8")}&limit=6")
            for (i in 0 until a.length()) a.getJSONObject(i).optString("cover_xl").takeIf { it.isNotBlank() }?.let { out += it }
        }
        val t = getArray("/search?q=${URLEncoder.encode("$artist $title", "UTF-8")}&limit=8")
        for (i in 0 until t.length()) t.getJSONObject(i).optJSONObject("album")?.optString("cover_xl")?.takeIf { it.isNotBlank() }?.let { out += it }
        out.take(9)
    }

    /** Поиск треков в Deezer; то, что уже есть в библиотеке, отбрасывается. */
    suspend fun searchTracks(q: String): List<RecItem> = withContext(Dispatchers.IO) {
        val local = dao.allTracksOnce().mapTo(HashSet()) { key(it.artist, it.title) }
        val arr = getArray("/search?q=${URLEncoder.encode(q, "UTF-8")}&limit=30")
        val seen = HashSet<String>()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            val k = key(artist, title)
            if (title.isBlank() || k in local || !seen.add(k)) return@mapNotNull null
            val al = o.optJSONObject("album")
            RecItem(
                kind = RecItem.KIND_TRACK,
                title = title,
                artist = artist,
                cover = al?.optString("cover_medium").orEmpty(),
                reason = al?.optString("title").orEmpty(),
            )
        }
    }

    private fun searchArtistId(name: String): Long? {
        val arr = getArray("/search/artist?q=${URLEncoder.encode(name, "UTF-8")}&limit=3")
        if (arr.length() == 0) return null
        val target = norm(name)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (norm(o.optString("name")) == target) return o.optLong("id")
        }
        return arr.getJSONObject(0).optLong("id")
    }

    private fun getArray(path: String): JSONArray {
        val c = URL(api + path).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Lumen/0.6 (Android music player)")
        try {
            if (c.responseCode !in 200..299) return JSONArray()
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (o.has("error")) throw IllegalStateException(o.optJSONObject("error")?.optString("message"))
            return o.optJSONArray("data") ?: JSONArray()
        } finally {
            c.disconnect()
        }
    }

    private fun save(state: RecsState) {
        runCatching {
            val arr = JSONArray()
            state.sections.forEach { s ->
                val items = JSONArray()
                s.items.forEach { i ->
                    items.put(
                        JSONObject().put("k", i.kind).put("t", i.title).put("a", i.artist).put("c", i.cover).put("r", i.reason)
                    )
                }
                arr.put(JSONObject().put("title", s.title).put("sub", s.subtitle).put("style", s.style).put("items", items))
            }
            cacheFile.writeText(JSONObject().put("updatedAt", state.updatedAt).put("sections", arr).toString())
        }
    }

    private fun parseSections(arr: JSONArray): List<RecSection> = (0 until arr.length()).map { i ->
        val s = arr.getJSONObject(i)
        val items = s.getJSONArray("items")
        RecSection(
            s.optString("title"),
            s.optString("sub"),
            s.optInt("style"),
            (0 until items.length()).map { j ->
                val o = items.getJSONObject(j)
                RecItem(o.optInt("k"), o.optString("t"), o.optString("a"), o.optString("c"), o.optString("r"))
            },
        )
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }
    private fun key(artist: String, title: String) = norm(artist) + "|" + norm(title.substringBefore(" (").substringBefore(" ["))
}

/** Загрузка картинок из сети с кэшем в памяти и на диске. */
object RemoteImages {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val gate = Semaphore(4)

    fun peek(url: String) = mem.get(url)

    suspend fun load(context: Context, url: String, maxPx: Int = 512): Bitmap? {
        if (url.isBlank()) return null
        mem.get(url)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "rimg").apply { mkdirs() }
                val f = File(dir, md5(url))
                val bytes = if (f.exists()) f.readBytes() else runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 10000
                    try {
                        c.inputStream.use { it.readBytes() }.also { f.writeBytes(it) }
                    } finally {
                        c.disconnect()
                    }
                }.getOrNull() ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.also { mem.put(url, it) }
            }
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

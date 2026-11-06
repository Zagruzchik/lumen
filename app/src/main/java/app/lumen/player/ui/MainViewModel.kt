package app.lumen.player.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.palette.graphics.Palette
import app.lumen.player.data.AlbumRow
import app.lumen.player.data.Artwork
import app.lumen.player.data.FavoriteEntity
import app.lumen.player.data.LyricsResult
import app.lumen.player.data.TrackEntity
import app.lumen.player.graph
import app.lumen.player.ui.theme.CoverColors
import app.lumen.player.ui.theme.DefaultCoverColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

data class AudioQuality(
    val format: String,
    val sampleRate: Int,
    val bits: Int,
    val bitrate: Int,
    val lossless: Boolean,
    val hiRes: Boolean,
)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val dao = g.dao
    val conn = g.player
    val player = conn.state
    val scanning = g.scanner.scanning
    val sleepEndsAt = g.sleepTimer.endsAt

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val tracks = dao.tracks().state(emptyList())
    val albums = dao.albums().state(emptyList())
    val recentAlbums = dao.recentAlbums().state(emptyList())
    val artists = dao.artists().state(emptyList())
    val folders = dao.folders().state(emptyList())
    val frequent = dao.frequent().state(emptyList())
    val favorites = dao.favorites().state(emptyList())
    val lastPlayed = dao.lastPlayed().state(null)
    val favoriteUris = dao.favoriteUris().map { it.toHashSet() as Set<String> }.state(emptySet())

    private val trackIndex = tracks.map { list -> list.associateBy { it.uri } }
        .flowOn(Dispatchers.Default).state(emptyMap())

    val currentTrack: StateFlow<TrackEntity?> =
        combine(player, trackIndex) { p, idx -> p.currentId?.let { idx[it] } }.state(null)

    val query = MutableStateFlow("")
    private val searchKeys = tracks.map { list ->
        list.map { it to normalize("${it.title} ${it.artist} ${it.album}") }
    }.flowOn(Dispatchers.Default).state(emptyList())

    val searchResults: StateFlow<List<TrackEntity>> =
        combine(query.debounce(120), searchKeys) { q, keys ->
            val n = normalize(q.trim())
            if (n.isEmpty()) emptyList()
            else keys.asSequence().filter { it.second.contains(n) }.map { it.first }.take(200).toList()
        }.flowOn(Dispatchers.Default).state(emptyList())

    val coverColors = MutableStateFlow(DefaultCoverColors)
    val quality = MutableStateFlow<AudioQuality?>(null)
    val lyrics = MutableStateFlow<LyricsResult>(LyricsResult.Idle)
    private var lyricsJob: Job? = null
    var lyricsVisible = false
        private set

    val folderList = MutableStateFlow(g.prefs.folders.toList())
    val skipShort = MutableStateFlow(g.prefs.skipShort)
    val onlineLyrics = MutableStateFlow(g.prefs.onlineLyrics)
    val hasPermission = MutableStateFlow(false)
    val openPlayerRequest = MutableStateFlow(0)
    val showLyrics = MutableStateFlow(g.prefs.showLyrics)

    fun toggleShowLyrics() {
        val v = !showLyrics.value
        showLyrics.value = v
        g.prefs.showLyrics = v
    }

    init {
        viewModelScope.launch {
            conn.connect()
            restoreQueue()
        }
        viewModelScope.launch {
            player.map { it.currentId }.distinctUntilChanged().collect { id -> onTrackChanged(id) }
        }
        viewModelScope.launch {
            player.map { it.currentId to it.queue.size }.distinctUntilChanged().collect { saveQueue() }
        }
    }

    fun onPermission(granted: Boolean) {
        val was = hasPermission.value
        hasPermission.value = granted
        if (granted && !was) {
            g.rescan()
            g.watchMediaStore()
        }
    }

    // ---------- воспроизведение ----------

    fun play(list: List<TrackEntity>, index: Int) = conn.playTracks(list, index)
    fun shuffle(list: List<TrackEntity>) = conn.playTracks(list, 0, shuffle = true)
    fun playNext(t: TrackEntity) = conn.playNext(t)
    fun enqueue(t: TrackEntity) = conn.enqueue(t)
    fun toggle() = conn.toggle()
    fun next() = conn.next()
    fun previous() = conn.previous()
    fun seek(f: Float) = conn.seekFraction(f)
    fun seekTo(ms: Long) = conn.seekTo(ms)
    fun toggleShuffle() = conn.toggleShuffle()
    fun cycleRepeat() = conn.cycleRepeat()
    fun skipTo(i: Int) = conn.skipTo(i)
    fun removeAt(i: Int) = conn.removeAt(i)
    fun moveItem(from: Int, to: Int) = conn.moveItem(from, to)
    fun position(): Long = conn.position
    fun setSleep(minutes: Int?) = g.sleepTimer.set(minutes)

    /** «Продолжить»: если очередь есть — играем, иначе запускаем последний трек с его альбомом. */
    fun resume() {
        if (player.value.queue.isNotEmpty()) {
            conn.toggle()
            return
        }
        val last = lastPlayed.value ?: return
        val album = tracks.value.filter { it.albumKey == last.albumKey }
            .sortedWith(compareBy({ it.discNo }, { it.trackNo }))
        val idx = album.indexOfFirst { it.uri == last.uri }.coerceAtLeast(0)
        conn.playTracks(album.ifEmpty { listOf(last) }, idx)
    }

    fun playExternal(uri: Uri) {
        viewModelScope.launch {
            conn.connect()
            val md = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(getApplication<Application>(), uri)
                    MediaMetadata.Builder()
                        .setTitle(
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                ?: uri.lastPathSegment?.substringAfterLast('/')
                        )
                        .setArtist(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                        .setAlbumTitle(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                        .setArtworkUri(uri)
                        .build()
                } catch (e: Exception) {
                    MediaMetadata.Builder().setTitle(uri.lastPathSegment ?: "Файл").build()
                } finally {
                    runCatching { r.release() }
                }
            }
            conn.playItem(
                MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).setMediaMetadata(md).build()
            )
            openPlayerRequest.value++
        }
    }

    fun requestOpenPlayer() {
        openPlayerRequest.value++
    }

    fun toggleFavorite(uri: String) {
        viewModelScope.launch {
            if (uri in favoriteUris.value) dao.removeFavorite(uri)
            else dao.addFavorite(FavoriteEntity(uri, System.currentTimeMillis()))
        }
    }

    private suspend fun restoreQueue() {
        val c = conn.controller ?: return
        if (c.mediaItemCount > 0) return
        val uris = g.prefs.queue
        if (uris.isEmpty()) return
        val found = uris.chunked(500).flatMap { dao.byUris(it) }.associateBy { it.uri }
        val ordered = uris.mapNotNull { found[it] }
        if (ordered.isEmpty()) return
        val savedCurrent = uris.getOrNull(g.prefs.queueIndex)
        val idx = ordered.indexOfFirst { it.uri == savedCurrent }.coerceAtLeast(0)
        conn.restore(ordered, idx, g.prefs.queuePosition)
    }

    fun saveQueue() {
        val c = conn.controller ?: return
        val q = player.value.queue
        if (q.isEmpty()) return
        g.prefs.queue = q.take(5000).map { it.mediaId }
        g.prefs.queueIndex = c.currentMediaItemIndex
        g.prefs.queuePosition = c.currentPosition
    }

    // ---------- обложка, цвета, качество ----------

    private fun onTrackChanged(id: String?) {
        lyricsJob?.cancel()
        lyrics.value = LyricsResult.Idle
        if (id == null) {
            coverColors.value = DefaultCoverColors
            quality.value = null
            return
        }
        viewModelScope.launch {
            val bmp = Artwork.load(getApplication<Application>(), id, 128)
            coverColors.value = if (bmp != null) {
                withContext(Dispatchers.Default) { colorsFrom(bmp) }
            } else {
                DefaultCoverColors
            }
        }
        viewModelScope.launch {
            val t = dao.byUri(id)
            quality.value = withContext(Dispatchers.IO) { readQuality(id, t) }
        }
        // Текст ищем сразу при старте трека — неважно, открыт он или нет.
        loadLyrics()
        prefetchNextLyrics()
    }

    /** Заранее готовим текст следующего трека, чтобы он был и без сети. */
    private fun prefetchNextLyrics() {
        val c = conn.controller ?: return
        val next = c.nextMediaItemIndex
        if (next < 0 || next >= c.mediaItemCount) return
        val id = c.getMediaItemAt(next).mediaId
        g.appScope.launch {
            val t = dao.byUri(id) ?: return@launch
            if (g.lyrics.needsFetch(t)) runCatching { g.lyrics.load(t) }
        }
    }

    private fun colorsFrom(bmp: Bitmap): CoverColors {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val base = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb
            ?: return DefaultCoverColors
        val a = p.vibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: base
        val b = p.darkVibrantSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: a
        val c = p.lightVibrantSwatch?.rgb ?: p.mutedSwatch?.rgb ?: a
        return CoverColors(
            accent = Color(tuneAccent(base)),
            a = Color(a),
            b = Color(b),
            c = Color(c),
        )
    }

    private fun tuneAccent(rgb: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = max(hsl[1], 0.55f)
        hsl[2] = hsl[2].coerceIn(0.6f, 0.72f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun readQuality(uri: String, t: TrackEntity?): AudioQuality {
        var sr = 0
        var bits = 0
        var mime = t?.mime.orEmpty()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(getApplication<Application>(), Uri.parse(uri))
            if (mime.isEmpty()) mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            if (Build.VERSION.SDK_INT >= 31) {
                sr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                bits = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
        } finally {
            runCatching { r.release() }
        }
        val label = formatLabel(mime, uri)
        val lossless = label in setOf("FLAC", "WAV", "ALAC", "WV")
        return AudioQuality(
            format = label,
            sampleRate = sr,
            bits = bits,
            bitrate = t?.bitrate ?: 0,
            lossless = lossless,
            hiRes = lossless && (sr > 48_000 || bits > 16),
        )
    }

    // ---------- тексты ----------

    fun setLyricsVisible(v: Boolean) {
        lyricsVisible = v
        if (v && lyrics.value == LyricsResult.Idle) loadLyrics()
    }

    fun loadLyrics(force: Boolean = false) {
        val id = player.value.currentId ?: return
        lyricsJob?.cancel()
        lyrics.value = LyricsResult.Loading
        lyricsJob = viewModelScope.launch {
            val t = dao.byUri(id)
            lyrics.value = if (t == null) LyricsResult.NotFound else g.lyrics.load(t, force)
        }
    }

    // ---------- настройки ----------

    fun rescan() = g.rescan()

    fun addFolder(uri: String) {
        val set = g.prefs.folders + uri
        g.prefs.folders = set
        folderList.value = set.toList()
        g.rescan()
    }

    fun removeFolder(uri: String) {
        val set = g.prefs.folders - uri
        g.prefs.folders = set
        folderList.value = set.toList()
        g.rescan()
    }

    fun setSkipShort(v: Boolean) {
        g.prefs.skipShort = v
        skipShort.value = v
        g.rescan()
    }

    val lyricsBulk = g.lyricsBulk.state
    fun startLyricsDownload() = g.lyricsBulk.start()
    fun stopLyricsDownload() = g.lyricsBulk.stop()

    fun setOnlineLyrics(v: Boolean) {
        g.prefs.onlineLyrics = v
        onlineLyrics.value = v
    }

    fun albumOf(t: TrackEntity): AlbumRow? = albums.value.firstOrNull { it.albumKey == t.albumKey }

    // ---------- исключённые папки ----------

    val allFolders = dao.allFolders().state(emptyList())
    val excludedFolders = dao.excludedFolders().map { it.toHashSet() as Set<String> }.state(emptySet())

    fun setFolderHidden(folder: String, hidden: Boolean) {
        viewModelScope.launch {
            if (hidden) dao.addExcluded(listOf(app.lumen.player.data.ExcludedFolder(folder)))
            else dao.removeExcluded(folder)
        }
    }

    // ---------- эквалайзер ----------

    val eq = g.eq.state
    fun eqSetEnabled(on: Boolean) = g.eq.setEnabled(on)
    fun eqSetBand(i: Int, level: Int) = g.eq.setBand(i, level)
    fun eqPreset(i: Int) = g.eq.usePreset(i)
    fun eqReset() = g.eq.reset()
    fun eqSetBass(s: Int) = g.eq.setBass(s)

    // ---------- перенос медиатеки на ПК ----------

    /** Экспорт всей медиатеки в JSON: треки с путями и тегами, любимое, статистика, исключения, настройки. */
    fun exportLibrary(target: Uri, onDone: (Boolean, Int) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tracksAll = dao.visibleTracksOnce()
                    val favs = dao.allFavorites().associateBy { it.uri }
                    val plays = dao.allPlays().associateBy { it.uri }
                    val root = org.json.JSONObject()
                    root.put("app", "Lumen")
                    root.put("format", 1)
                    root.put("appVersion", "0.2.0")
                    root.put("exportedAt", System.currentTimeMillis())
                    root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    val arr = org.json.JSONArray()
                    tracksAll.forEach { t ->
                        val o = org.json.JSONObject()
                        o.put("path", t.path)
                        o.put("uri", t.uri)
                        o.put("title", t.title)
                        o.put("artist", t.artist)
                        o.put("album", t.album)
                        o.put("albumArtist", t.albumArtist)
                        o.put("trackNo", t.trackNo)
                        o.put("discNo", t.discNo)
                        o.put("year", t.year)
                        o.put("durationMs", t.durationMs)
                        o.put("mime", t.mime)
                        o.put("size", t.size)
                        o.put("bitrate", t.bitrate)
                        o.put("folder", t.folderName)
                        o.put("dateAdded", t.dateAdded)
                        favs[t.uri]?.let { o.put("favoriteAt", it.addedAt) }
                        plays[t.uri]?.let {
                            o.put("playCount", it.count)
                            o.put("lastPlayed", it.lastPlayed)
                        }
                        arr.put(o)
                    }
                    root.put("tracks", arr)
                    root.put("excludedFolders", org.json.JSONArray(dao.excludedOnce()))
                    root.put(
                        "settings",
                        org.json.JSONObject()
                            .put("onlineLyrics", g.prefs.onlineLyrics)
                            .put("skipShort", g.prefs.skipShort)
                            .put("eqEnabled", g.prefs.eqEnabled)
                            .put("eqLevels", org.json.JSONArray(g.prefs.eqLevels))
                            .put("bassStrength", g.prefs.bassStrength),
                    )
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                    tracksAll.size
                }
            }
            onDone(result.isSuccess, result.getOrDefault(0))
        }
    }

    fun album(key: String) = dao.album(key)
    fun albumTracks(key: String) = dao.albumTracks(key)
    fun artistTracks(name: String) = dao.artistTracks(name)
    fun albumsOfArtist(name: String) = dao.albumsOfArtist(name)
    fun folderTracks(folder: String) = dao.folderTracks(folder)
}

private val TRANSLIT = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
    'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m",
    'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
    'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
    'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    'ә' to "a", 'ғ' to "g", 'қ' to "k", 'ң' to "n", 'ө' to "o", 'ұ' to "u", 'ү' to "u", 'һ' to "h", 'і' to "i",
)

/** Нормализация для поиска: «Кино» найдётся по «kino», «Ё» = «Е», регистр не важен. */
fun normalize(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s.lowercase()) {
        val t = TRANSLIT[ch]
        when {
            t != null -> sb.append(t)
            ch.isLetterOrDigit() || ch == ' ' -> sb.append(ch)
        }
    }
    return sb.toString()
        .replace("kh", "h").replace("x", "ks").replace("w", "v").replace("j", "i").replace("y", "i")
}

fun formatLabel(mime: String, uri: String = ""): String {
    val m = mime.lowercase()
    return when {
        "flac" in m -> "FLAC"
        "mpeg" in m || "mp3" in m -> "MP3"
        "alac" in m -> "ALAC"
        "mp4" in m || "m4a" in m || "aac" in m -> "AAC"
        "opus" in m -> "OPUS"
        "ogg" in m || "vorbis" in m -> "OGG"
        "wav" in m -> "WAV"
        "wavpack" in m -> "WV"
        else -> uri.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..4 } ?: "AUDIO"
    }
}

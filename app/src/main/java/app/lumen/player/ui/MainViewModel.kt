package app.lumen.player.ui

import app.lumen.player.playback.LumenAlarm
import app.lumen.player.playback.AlarmConfig
import app.lumen.player.data.SmartRules
import app.lumen.player.data.SmartEngine
import app.lumen.player.data.SmartContext
import app.lumen.player.data.SmartPlaylistEntity
import app.lumen.player.data.DislikeEntity
import kotlinx.coroutines.flow.mapLatest
import android.provider.MediaStore
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
import app.lumen.player.data.Release
import app.lumen.player.data.Artists
import app.lumen.player.ui.components.QuickMenuState
import app.lumen.player.data.UpdateEvents
import app.lumen.player.data.AlbumRow
import app.lumen.player.data.Artwork
import app.lumen.player.data.FavoriteEntity
import app.lumen.player.data.LyricsResult
import app.lumen.player.data.RecItem
import app.lumen.player.data.RecsState
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
import kotlinx.coroutines.flow.first
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

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    // Исполнители считаются по каждому участнику: «A & B» — это и A, и B.
    val artists = tracks.map { Artists.rows(it) }.flowOn(Dispatchers.Default).state(emptyList())
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

    fun onPermission(granted: Boolean) {
        val was = hasPermission.value
        hasPermission.value = granted
        if (granted && !was) {
            g.rescan()
            g.watchMediaStore()
        }
    }

    // ---------- воспроизведение ----------

    fun play(list: List<TrackEntity>, index: Int) {
        // Нажатие на трек, который уже играет, — пауза/продолжение, а не перезапуск.
        val t = list.getOrNull(index)
        if (t != null && t.uri == player.value.currentId) {
            conn.toggle()
            return
        }
        lovedMode.value = 0
        conn.playTracks(list, index)
    }

    fun shuffle(list: List<TrackEntity>) {
        lovedMode.value = 0
        val dis = dislikedUris.value
        val liked = if (dis.isEmpty()) list else list.filter { it.uri !in dis }.ifEmpty { list }
        conn.playTracks(liked, 0, shuffle = true)
    }
    fun playNext(t: TrackEntity) = conn.playNext(t)
    fun enqueue(t: TrackEntity) = conn.enqueue(t)
    fun toggle() = conn.toggle()
    fun next() = conn.next()
    fun previous() = conn.previous()
    fun nextItem() = conn.nextItem()
    fun previousItem() = conn.previousItem()
    fun seek(f: Float) = conn.seekFraction(f)
    fun seekTo(ms: Long) = conn.seekTo(ms)
    fun toggleShuffle() = conn.toggleShuffle()
    fun cycleRepeat() = conn.cycleRepeat()
    fun skipTo(i: Int) {
        if (i == player.value.index && player.value.currentId != null) conn.toggle() else conn.skipTo(i)
    }
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
        if (uris.isEmpty()) {
            prepareDefaultSession()
            return
        }
        val found = uris.chunked(500).flatMap { dao.byUris(it) }.associateBy { it.uri }
        val ordered = uris.mapNotNull { found[it] }
        if (ordered.isEmpty()) {
            prepareDefaultSession()
            return
        }
        val savedCurrent = uris.getOrNull(g.prefs.queueIndex)
        val idx = ordered.indexOfFirst { it.uri == savedCurrent }.coerceAtLeast(0)
        conn.restore(ordered, idx, g.prefs.queuePosition)
    }

    /**
     * Первый запуск или пустая история: сразу готовим «Самое любимое» на паузе,
     * чтобы мини-плеер и музыкальная сессия (уведомление, экран блокировки) были с самого начала.
     */
    private suspend fun prepareDefaultSession() {
        val c = conn.controller ?: return
        val withTracks = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            tracks.first { it.isNotEmpty() }
        } ?: return
        if (c.mediaItemCount > 0 || withTracks.isEmpty()) return
        val list = dao.mostLoved()
        if (list.isEmpty()) return
        lovedMode.value = 1
        conn.restore(list, 0, 0L)
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
        if (ab.value.a >= 0 || ab.value.b >= 0) ab.value = AbState()
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
        startBeats(id)
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

    /** Ход экспорта/импорта: подпись и прогресс 0..1 (null — ничего не идёт). */
    val transfer = MutableStateFlow<Pair<String, Float>?>(null)

    /** Сколько места займут сами треки в архиве. */
    fun musicBytes(): Long = tracks.value.sumOf { it.size.coerceAtLeast(0L) }

    /**
     * Экспорт всего в один ZIP: library.json (треки с путями и тегами, любимое, статистика,
     * история, исключённые папки, настройки), сохранённые тексты песен и — по желанию — сами треки.
     */
    fun exportLibrary(target: Uri, withMusic: Boolean, onDone: (Boolean, Int) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tracksAll = dao.visibleTracksOnce()
                    val favs = dao.allFavorites().associateBy { it.uri }
                    val plays = dao.allPlays().associateBy { it.uri }
                    val root = org.json.JSONObject()
                    root.put("app", "Lumen")
                    root.put("format", 1)
                    root.put("appVersion", g.updates.currentName)
                    root.put("format", 2)
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
                    val index = HashMap<String, Int>()
                    tracksAll.forEachIndexed { i, t -> index[t.uri] = i }
                    val ls = org.json.JSONArray()
                    dao.allListens().forEach { l ->
                        val ti = index[l.uri] ?: return@forEach
                        ls.put(
                            org.json.JSONObject()
                                .put("t", ti)
                                .put("s", l.startedAt)
                                .put("l", l.listenedMs)
                                .put("d", l.trackDurationMs)
                                .put("c", l.completed)
                        )
                    }
                    root.put("listens", ls)
                    // Плейлисты (по индексам треков) и свои теги.
                    val plTracks = dao.allPlaylistTracks().groupBy { it.playlistId }
                    val pls = org.json.JSONArray()
                    dao.allPlaylists().forEach { pl ->
                        val idx = org.json.JSONArray()
                        plTracks[pl.id].orEmpty().sortedBy { it.position }.forEach { pt -> index[pt.uri]?.let { idx.put(it) } }
                        pls.put(org.json.JSONObject().put("name", pl.name).put("createdAt", pl.createdAt).put("tracks", idx))
                    }
                    root.put("playlists", pls)
                    val ovs = org.json.JSONArray()
                    dao.allOverrides().forEach { o ->
                        val ti = index[o.uri] ?: return@forEach
                        ovs.put(
                            org.json.JSONObject().put("t", ti)
                                .put("title", o.title ?: org.json.JSONObject.NULL)
                                .put("artist", o.artist ?: org.json.JSONObject.NULL)
                                .put("album", o.album ?: org.json.JSONObject.NULL)
                                .put("albumArtist", o.albumArtist ?: org.json.JSONObject.NULL)
                                .put("year", o.year ?: org.json.JSONObject.NULL)
                        )
                    }
                    root.put("overrides", ovs)
                    root.put("excludedFolders", org.json.JSONArray(dao.excludedOnce()))
                    root.put(
                        "trims",
                        org.json.JSONArray().apply {
                            dao.allTrims().forEach { tr -> index[tr.uri]?.let { put(org.json.JSONObject().put("t", it).put("s", tr.startMs).put("e", tr.endMs)) } }
                        },
                    )
                    root.put("dislikes", org.json.JSONArray().apply { dao.allDislikes().forEach { d -> index[d.uri]?.let { put(it) } } })
                    root.put(
                        "smartPlaylists",
                        org.json.JSONArray().apply {
                            dao.allSmartPlaylists().forEach { sp ->
                                put(org.json.JSONObject().put("name", sp.name).put("rules", sp.rules).put("createdAt", sp.createdAt))
                            }
                        },
                    )
                    root.put(
                        "settings",
                        org.json.JSONObject()
                            .put("onlineLyrics", g.prefs.onlineLyrics)
                            .put("skipShort", g.prefs.skipShort)
                            .put("eqEnabled", g.prefs.eqEnabled)
                            .put("eqLevels", org.json.JSONArray(g.prefs.eqLevels))
                            .put("bassStrength", g.prefs.bassStrength)
                            .put("beatFlashes", g.prefs.beatFlashes)
                            .put("beatIntensity", g.prefs.beatIntensity.toDouble())
                            .put("crossfadeMs", g.prefs.crossfadeMs)
                            .put("eqPreset", g.prefs.eqPreset)
                            .put("showLyrics", g.prefs.showLyrics)
                            .put("autoUpdate", g.prefs.autoUpdate)
                            .put("playStarts", g.prefs.playStarts),
                    )
                    writeBackupZip(target, root, arr, tracksAll, withMusic)
                    tracksAll.size
                }
            }
            transfer.value = null
            onDone(result.isSuccess, result.getOrDefault(0))
        }
    }

    private fun writeBackupZip(
        target: Uri,
        root: org.json.JSONObject,
        arr: org.json.JSONArray,
        tracksAll: List<TrackEntity>,
        withMusic: Boolean,
    ) {
        val app = getApplication<Application>()
        val cr = app.contentResolver
        // Имена файлов треков внутри архива: music/<папка>/<файл>, без повторов.
        val names = HashSet<String>()
        val files = arrayOfNulls<String>(tracksAll.size)
        if (withMusic) {
            tracksAll.forEachIndexed { i, t ->
                val folder = safeName(t.folderName.ifBlank { "Музыка" })
                val base = safeName(t.path.substringAfterLast('/').ifBlank { "${t.artist} - ${t.title}" })
                var name = "music/$folder/$base"
                var k = 2
                while (!names.add(name.lowercase())) {
                    name = "music/$folder/${base.substringBeforeLast('.')} ($k).${base.substringAfterLast('.', "mp3")}"
                    k++
                }
                files[i] = name
                arr.getJSONObject(i).put("file", name)
            }
        }
        root.put("withMusic", withMusic)
        val lyricsDir = java.io.File(app.filesDir, "lyrics")
        val lyricFiles = lyricsDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val totalBytes = (if (withMusic) tracksAll.sumOf { it.size.coerceAtLeast(0L) } else 0L) +
            lyricFiles.sumOf { it.length() } + 1
        var done = 0L
        fun report(label: String) {
            transfer.value = label to (done.toFloat() / totalBytes).coerceIn(0f, 1f)
        }
        report("Собираю архив…")
        val out = cr.openOutputStream(target) ?: error("no stream")
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out, 256 * 1024)).use { zip ->
            zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
            zip.putNextEntry(java.util.zip.ZipEntry("library.json"))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            lyricFiles.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry("lyrics/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                done += f.length()
            }
            report("Тексты песен сохранены")
            if (withMusic) {
                // Аудио уже сжато — пишем без повторного сжатия, так в разы быстрее.
                zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                val buf = ByteArray(256 * 1024)
                tracksAll.forEachIndexed { i, t ->
                    val name = files[i] ?: return@forEachIndexed
                    val inp = runCatching { cr.openInputStream(Uri.parse(t.uri)) }.getOrNull() ?: return@forEachIndexed
                    inp.use {
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        var lastReport = 0L
                        while (true) {
                            val n = it.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 2_000_000) {
                                lastReport = done
                                report("Треки: ${i + 1} из ${tracksAll.size}")
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        transfer.value = "Готово" to 1f
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "_" }

    /**
     * Читает файл бэкапа. ZIP: тексты песен кладутся к себе, треки, которых нет на телефоне,
     * сохраняются в «Музыка/Lumen», после чего медиатека пересканируется. Возвращает library.json.
     * Старый формат (просто JSON) тоже понимаем.
     */
    private suspend fun readBackup(source: Uri): String {
        val app = getApplication<Application>()
        val cr = app.contentResolver
        val head = cr.openInputStream(source)?.use { s -> ByteArray(2).also { s.read(it) } } ?: error("no stream")
        if (!(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) {
            return cr.openInputStream(source)?.bufferedReader()?.use { it.readText() } ?: error("no stream")
        }
        val total = runCatching {
            cr.openAssetFileDescriptor(source, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L
        var json: String? = null
        var lib: org.json.JSONObject? = null
        val haveKeys: HashSet<String> =
            dao.allTracksOnce().mapTo(HashSet()) { "${normalize(it.title)}|${normalize(it.artist)}|${it.durationMs / 2000}" }
        val byFile = HashMap<String, org.json.JSONObject>()
        var savedMusic = 0
        val lyricsDir = java.io.File(app.filesDir, "lyrics").apply { mkdirs() }
        val counting = object : java.io.FilterInputStream(cr.openInputStream(source) ?: error("no stream")) {
            var read = 0L
            override fun read(): Int = super.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        transfer.value = "Распаковываю архив…" to 0f
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(counting, 256 * 1024)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    e.isDirectory || name.contains("..") -> Unit
                    name == "library.json" -> {
                        json = zip.readBytes().toString(Charsets.UTF_8)
                        lib = runCatching { org.json.JSONObject(json!!) }.getOrNull()
                        lib?.optJSONArray("tracks")?.let { a ->
                            for (i in 0 until a.length()) {
                                val o = a.getJSONObject(i)
                                o.optString("file").takeIf { it.isNotBlank() }?.let { byFile[it] = o }
                            }
                        }
                    }
                    name.startsWith("lyrics/") -> {
                        val f = java.io.File(lyricsDir, name.removePrefix("lyrics/").substringAfterLast('/'))
                        if (!f.exists()) f.outputStream().use { zip.copyTo(it) }
                    }
                    name.startsWith("music/") && Build.VERSION.SDK_INT >= 29 -> {
                        val o = byFile[name]
                        val key = o?.let { "${normalize(it.optString("title"))}|${normalize(it.optString("artist"))}|${it.optLong("durationMs") / 2000}" }
                        if (key == null || key !in haveKeys) {
                            val folder = name.removePrefix("music/").substringBeforeLast('/', "")
                            val file = name.substringAfterLast('/')
                            val mime = o?.optString("mime")?.takeIf { it.startsWith("audio/") }
                                ?: android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(file.substringAfterLast('.', "").lowercase()) ?: "audio/mpeg"
                            if (saveToMusic(file, mime, folder, zip)) savedMusic++
                        }
                    }
                }
                zip.closeEntry()
                if (total > 0) {
                    transfer.value = (if (savedMusic > 0) "Переношу треки: $savedMusic" else "Распаковываю архив…") to
                        (counting.read.toFloat() / total).coerceIn(0f, 0.95f)
                }
            }
        }
        if (savedMusic > 0) {
            transfer.value = "Добавляю треки в медиатеку…" to 0.97f
            kotlinx.coroutines.delay(1500) // даём MediaStore проиндексировать новые файлы
            g.scanner.scan()
        }
        return json ?: error("not a Lumen backup")
    }

    /** Сохраняет трек в «Музыка/Lumen/<папка>» через MediaStore (Android 10+). */
    private fun saveToMusic(file: String, mime: String, folder: String, input: java.io.InputStream): Boolean {
        val cr = getApplication<Application>().contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, file)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mime)
            put(
                android.provider.MediaStore.Audio.Media.RELATIVE_PATH,
                "Music/Lumen" + (if (folder.isNotBlank()) "/$folder" else "") + "/",
            )
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val coll = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { cr.insert(coll, values) }.getOrNull() ?: return false
        return try {
            cr.openOutputStream(uri)?.use { input.copyTo(it, 256 * 1024) } ?: error("no out")
            cr.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (t: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            false
        }
    }

    // ---------- вспышки под бит ----------

    val beats = MutableStateFlow<app.lumen.player.playback.Beats?>(null)
    val beatFlashes = MutableStateFlow(g.prefs.beatFlashes)
    private var beatsJob: Job? = null
    private var beatsPrefetch: Job? = null

    val crossfadeSec = MutableStateFlow(g.prefs.crossfadeMs / 1000)
    val crossfadeSoft = MutableStateFlow(g.prefs.crossfadeSoft)
    fun setCrossfadeSoft(v: Float) {
        g.prefs.crossfadeSoft = v.coerceIn(0f, 1f)
        crossfadeSoft.value = g.prefs.crossfadeSoft
    }
    /** Открытая поверх всего карточка для трека: «в плейлист» или «изменить теги». */
    val sheetTrack = MutableStateFlow<SheetTrack?>(null)
    val newPlaylistPrompt = MutableStateFlow(false)
    val trackMenu = MutableStateFlow<app.lumen.player.ui.components.TrackMenuRequest?>(null)
    val infoTrack = MutableStateFlow<TrackEntity?>(null)
    val showFormat = MutableStateFlow(g.prefs.showFormat)
    fun setShowFormat(v: Boolean) { g.prefs.showFormat = v; showFormat.value = v }

    suspend fun trackInfo(t: TrackEntity): app.lumen.player.ui.components.TrackInfo = withContext(Dispatchers.IO) {
        val q = readQuality(t.uri, t)
        var channels = 0
        runCatching {
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(getApplication(), Uri.parse(t.uri), null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                        break
                    }
                }
            } finally {
                ex.release()
            }
        }
        val st = dao.trackListenStats(t.uri)
        app.lumen.player.ui.components.TrackInfo(
            path = t.path,
            sizeBytes = t.size,
            format = q.format,
            bitrate = t.bitrate,
            sampleRate = q.sampleRate,
            bits = q.bits,
            channels = channels,
            plays = st.plays,
            listenedMs = st.listenedMs,
            firstPlayed = st.firstAt,
            lastPlayed = st.lastAt,
            dateAdded = t.dateAdded,
        )
    }
    /** Трек, который положим в новый плейлист сразу после создания. */
    var pendingPlaylistUri: String? = null

    /** Меню плейлистов на удержании лайка. */
    val quickMenu = QuickMenuState()
    val quickToast = MutableStateFlow<String?>(null)

    /** Последние плейлисты для быстрого меню (до 6). */
    fun quickLists() = playlists.value.take(6)

    fun quickPick(i: Int) {
        val uri = quickMenu.uri ?: return
        if (i == app.lumen.player.ui.components.QuickMenuState.NEW) {
            pendingPlaylistUri = uri
            newPlaylistPrompt.value = true
            return
        }
        val pl = quickLists().getOrNull(i) ?: return
        addToPlaylist(pl.id, listOf(uri))
        quickToast.value = "Добавлено в «${pl.name}»"
        viewModelScope.launch {
            kotlinx.coroutines.delay(1800)
            if (quickToast.value?.contains(pl.name) == true) quickToast.value = null
        }
    }

    // ---------- «не нравится» ----------

    val dislikedUris = dao.dislikedUris().map { it.toHashSet() as Set<String> }.state(emptySet())
    val dislikedTracks = dao.dislikedTracks().state(emptyList())

    fun toast(msg: String) {
        quickToast.value = msg
        viewModelScope.launch {
            kotlinx.coroutines.delay(2200)
            if (quickToast.value == msg) quickToast.value = null
        }
    }

    fun toggleDislike(t: TrackEntity) {
        viewModelScope.launch {
            if (t.uri in dislikedUris.value) {
                dao.removeDislike(t.uri)
                toast("Трек вернулся в подборки")
            } else {
                dao.addDislike(DislikeEntity(t.uri, System.currentTimeMillis()))
                dao.removeFavorite(t.uri)
                if (player.value.currentId == t.uri && player.value.queue.size > 1) conn.next()
                toast("Больше не попадётся в подборках")
            }
        }
    }

    // ---------- умные плейлисты ----------

    private suspend fun smartContext() = withContext(Dispatchers.IO) {
        SmartContext(
            plays = dao.allPlays().associateBy { it.uri },
            favorites = dao.allFavorites().mapTo(HashSet()) { it.uri },
            disliked = dao.allDislikes().mapTo(HashSet()) { it.uri },
            format = { formatLabel(it.mime, it.uri) },
        )
    }

    private fun daySeed(id: Long) = id * 31 + System.currentTimeMillis() / 86_400_000L

    /** Список умных плейлистов с количеством треков и обложками для коллажа. */
    val smartRows: StateFlow<List<SmartRow>> =
        combine(dao.smartPlaylists(), tracks, favoriteUris, dislikedUris) { pls, all, _, _ -> pls to all }
            .debounce(250)
            .mapLatest { (pls, all) ->
                if (pls.isEmpty()) return@mapLatest emptyList()
                val ctx = smartContext()
                withContext(Dispatchers.Default) {
                    pls.map { p ->
                        val rules = SmartRules.parse(p.rules)
                        val list = SmartEngine.evaluate(rules, all, ctx, daySeed(p.id))
                        SmartRow(p.id, p.name, rules, list.size, list.sumOf { it.durationMs }, list.take(4).map { it.uri })
                    }
                }
            }
            .state(emptyList())

    fun smartPlaylist(id: Long) = dao.smartPlaylist(id)

    fun smartTracks(id: Long): Flow<List<TrackEntity>> =
        combine(dao.smartPlaylist(id), tracks, favoriteUris, dislikedUris) { p, all, _, _ -> p to all }
            .debounce(200)
            .mapLatest { (p, all) ->
                if (p == null) return@mapLatest emptyList()
                val ctx = smartContext()
                withContext(Dispatchers.Default) {
                    SmartEngine.evaluate(SmartRules.parse(p.rules), all, ctx, daySeed(p.id))
                }
            }

    suspend fun smartPreview(rules: SmartRules): List<TrackEntity> {
        val ctx = smartContext()
        return withContext(Dispatchers.Default) { SmartEngine.evaluate(rules, tracks.value, ctx, 7) }
    }

    suspend fun smartOnce(id: Long) = withContext(Dispatchers.IO) { dao.smartPlaylistOnce(id) }

    fun saveSmart(id: Long, name: String, rules: SmartRules, onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val old = if (id > 0) dao.smartPlaylistOnce(id) else null
            val newId = dao.putSmartPlaylist(
                SmartPlaylistEntity(
                    id = old?.id ?: 0,
                    name = name.trim().ifBlank { "Умный плейлист" },
                    rules = rules.toJson(),
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            onSaved(if (old != null) old.id else newId)
        }
    }

    fun deleteSmart(id: Long) = viewModelScope.launch { dao.deleteSmartPlaylist(id) }

    // ---------- дубликаты ----------

    suspend fun findDuplicates(): List<DupGroup> = withContext(Dispatchers.Default) {
        val all = withContext(Dispatchers.IO) { dao.visibleTracksOnce() }
        val ignored = g.prefs.dupIgnored
        val noise = Regex("[(\\[][^)\\]]*(remaster|explicit|clean|bonus|album version|original mix)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
        fun key(t: TrackEntity) = normalize(t.title.replace(noise, "")).replace(" ", "") + "|" +
            normalize(Artists.of(t).firstOrNull() ?: t.artist).replace(" ", "")
        val out = ArrayList<DupGroup>()
        all.groupBy(::key).values.filter { it.size > 1 }.forEach { same ->
            // Внутри одинаковых названий делим по длительности: разница больше 3 секунд — это разные версии.
            val sorted = same.sortedBy { it.durationMs }
            var cur = arrayListOf(sorted[0])
            fun flush() {
                if (cur.size > 1) {
                    val id = cur.map { it.uri }.sorted().joinToString("\n").hashCode().toString()
                    if (id !in ignored) {
                        val ranked = cur.sortedByDescending { qualityScore(it) }
                        out += DupGroup(id, ranked, ranked.first().uri)
                    }
                }
            }
            for (t in sorted.drop(1)) {
                if (t.durationMs - cur.last().durationMs <= 3000) cur.add(t) else { flush(); cur = arrayListOf(t) }
            }
            flush()
        }
        out.sortedBy { it.tracks.first().artist.lowercase() + it.tracks.first().title.lowercase() }
    }

    private fun qualityScore(t: TrackEntity): Double {
        val f = formatLabel(t.mime, t.uri)
        val lossless = f in setOf("FLAC", "ALAC", "WAV", "WV")
        val br = if (t.bitrate > 0) t.bitrate.toDouble() else if (t.durationMs > 0) t.size * 8000.0 / t.durationMs else 0.0
        return (if (lossless) 1e7 else 0.0) + br
    }

    fun ignoreDuplicate(id: String) {
        g.prefs.dupIgnored = g.prefs.dupIgnored + id
    }

    /** Системный запрос на удаление файлов из медиатеки (Android 11+): пользователь подтверждает сам. */
    fun deleteRequest(uris: List<String>): android.content.IntentSender? {
        if (Build.VERSION.SDK_INT < 30) return null
        val media = uris.filter { it.startsWith("content://media/") }.map { Uri.parse(it) }
        if (media.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, media).intentSender }.getOrNull()
    }

    /** То, что можно удалить без системного окна: файлы из своих папок и старые Android. */
    suspend fun deleteDirect(uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        uris.filter { u ->
            val uri = Uri.parse(u)
            runCatching {
                if (u.startsWith("content://media/")) {
                    Build.VERSION.SDK_INT < 30 && getApplication<Application>().contentResolver.delete(uri, null, null) > 0
                } else {
                    android.provider.DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri)
                }
            }.getOrDefault(false)
        }
    }

    /** После удаления: всё (лайки, прослушивания, плейлисты) переходит к оставшемуся файлу. */
    fun onDuplicatesDeleted(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { pairs.forEach { (from, to) -> dao.mergeInto(from, to) } }
            toast("Удалено: ${pluralRuVm(pairs.size, "файл", "файла", "файлов")}")
            g.rescan()
        }
    }

    private fun pluralRuVm(n: Int, a: String, b: String, c: String) = app.lumen.player.ui.components.pluralRu(n, a, b, c)

    // ---------- плейлисты ----------

    val playlists = dao.playlists().state(emptyList())
    fun playlist(id: Long) = dao.playlist(id)
    fun playlistTracks(id: Long) = dao.playlistTracks(id)
    fun playlistCovers(id: Long) = dao.playlistCovers(id)
    suspend fun playlistsWith(uri: String) = dao.playlistsWith(uri).toSet()

    fun createPlaylist(name: String, uris: List<String> = emptyList(), onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = dao.insertPlaylist(app.lumen.player.data.PlaylistEntity(name = name.trim().ifBlank { "Новый плейлист" }, createdAt = now, updatedAt = now))
            if (uris.isNotEmpty()) dao.addToPlaylist(id, uris)
            onCreated(id)
        }
    }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) dao.renamePlaylist(id, name.trim(), System.currentTimeMillis())
    }
    fun deletePlaylist(id: Long) = viewModelScope.launch { dao.deletePlaylist(id) }
    fun addToPlaylist(id: Long, uris: List<String>) = viewModelScope.launch { dao.addToPlaylist(id, uris) }
    fun removeFromPlaylist(id: Long, uri: String) = viewModelScope.launch {
        dao.removeFromPlaylist(id, uri)
        dao.touchPlaylist(id, System.currentTimeMillis())
    }
    fun reorderPlaylist(id: Long, uris: List<String>) = viewModelScope.launch { dao.reorderPlaylist(id, uris) }

    // ---------- «Твой месяц в музыке» ----------

    suspend fun wrapped(monthsAgo: Int): WrappedData = withContext(Dispatchers.IO) {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.MONTH, -monthsAgo)
        val from = cal.timeInMillis
        val monthName = java.text.SimpleDateFormat("LLLL", java.util.Locale("ru")).format(cal.time)
        cal.add(java.util.Calendar.MONTH, 1)
        val to = cal.timeInMillis
        val times = dao.listenTimesIn(from, to)
        val totalMs = times.sumOf { it.listenedMs }
        val byHour = LongArray(24)
        val byDay = LongArray(7)
        val days = HashSet<Int>()
        val c2 = java.util.Calendar.getInstance()
        times.forEach { lt ->
            c2.timeInMillis = lt.startedAt
            byHour[c2.get(java.util.Calendar.HOUR_OF_DAY)] += lt.listenedMs
            byDay[(c2.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7] += lt.listenedMs
            days += c2.get(java.util.Calendar.DAY_OF_YEAR)
        }
        // Самая длинная серия дней подряд с музыкой.
        var best = 0; var run = 0; var prev = -10
        days.sorted().forEach { d -> run = if (d == prev + 1) run + 1 else 1; best = maxOf(best, run); prev = d }
        WrappedData(
            monthName = monthName,
            totalMs = totalMs,
            plays = times.size,
            topArtists = dao.topArtistsIn(from, to, 5),
            topTracks = dao.topTracksIn(from, to, 5),
            topAlbum = dao.topAlbumsIn(from, to, 1).firstOrNull(),
            newTracks = dao.newTracksIn(from, to),
            peakHour = byHour.indices.maxByOrNull { byHour[it] } ?: 20,
            byHour = byHour.toList(),
            topWeekday = byDay.indices.maxByOrNull { byDay[it] } ?: 0,
            activeDays = days.size,
            streak = best,
        )
    }

    // ---------- ярлыки с иконки ----------

    /** Долгий тап по значку: «Продолжить», «Самое любимое», «Перемешать всё». */
    fun runShortcut(action: String) {
        viewModelScope.launch {
            runCatching { conn.connect() }
            when (action) {
                "LOVED" -> {
                    kotlinx.coroutines.withTimeoutOrNull(15_000) { tracks.first { it.isNotEmpty() } }
                    lovedMode.value = 0
                    playLoved(true)
                }
                "SHUFFLE_ALL" -> {
                    val list = kotlinx.coroutines.withTimeoutOrNull(15_000) { tracks.first { it.isNotEmpty() } } ?: return@launch
                    shuffle(list)
                }
                "CONTINUE" -> {
                    // Очередь восстанавливается при запуске — ждём её и продолжаем.
                    kotlinx.coroutines.withTimeoutOrNull(8_000) { player.first { it.currentId != null } }
                    if (!player.value.isPlaying) conn.toggle()
                    requestOpenPlayer()
                }
            }
        }
    }

    // ---------- похожее ----------

    /** Трек и за ним 30 похожих из библиотеки. */
    fun playSimilar(t: TrackEntity) {
        viewModelScope.launch {
            val more = withContext(Dispatchers.IO) { app.lumen.player.data.Similar.pick(dao, listOf(t), emptySet(), 30) }
            lovedMode.value = 0
            conn.playTracks(listOf(t) + more, 0)
        }
    }

    // ---------- свои теги и обложки ----------

    suspend fun overrideOf(uri: String) = dao.override(uri)

    fun saveTags(t: TrackEntity, title: String, artist: String, album: String, albumArtist: String, year: Int?) {
        viewModelScope.launch {
            fun pick(new: String, old: String) = new.trim().takeIf { it.isNotEmpty() && it != old }
            val prev = dao.override(t.uri)
            val o = app.lumen.player.data.TrackOverride(
                uri = t.uri,
                title = pick(title, t.title) ?: prev?.title?.takeIf { title.trim() == it },
                artist = pick(artist, t.artist) ?: prev?.artist?.takeIf { artist.trim() == it },
                album = pick(album, t.album) ?: prev?.album?.takeIf { album.trim() == it },
                albumArtist = pick(albumArtist, t.albumArtist) ?: prev?.albumArtist?.takeIf { albumArtist.trim() == it },
                year = year?.takeIf { it != t.year } ?: prev?.year?.takeIf { it == year },
            )
            dao.putOverride(o)
            dao.insertTracks(listOf(app.lumen.player.data.applyOverride(t, o)))
        }
    }

    /** Обложка альбома из Deezer по исполнителю и названию (несколько вариантов). */
    suspend fun findCovers(t: TrackEntity): List<String> = withContext(Dispatchers.IO) {
        runCatching { g.recs.findCovers(t.artist, t.title, t.album) }.getOrDefault(emptyList())
    }

    fun setCover(t: TrackEntity, url: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = url?.let { java.net.URL(it).openStream().use { s -> s.readBytes() } }
                    // Обложка ставится на весь альбом: так выглядит аккуратнее.
                    val targets = if (t.album.isNotBlank()) dao.albumTracksOnce(t.albumKey).map { it.uri }.ifEmpty { listOf(t.uri) } else listOf(t.uri)
                    targets.forEach { app.lumen.player.data.Artwork.setCustom(getApplication(), it, bytes) }
                }.isSuccess
            }
            onDone(ok)
        }
    }

    fun setCoverFromGallery(t: TrackEntity, image: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ctxApp = getApplication<Application>()
                    val src = ctxApp.contentResolver.openInputStream(image)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: error("no image")
                    val side = minOf(src.width, src.height)
                    val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    val scaled = android.graphics.Bitmap.createScaledBitmap(sq, minOf(side, 1000), minOf(side, 1000), true)
                    val bytes = java.io.ByteArrayOutputStream().also { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                    val targets = if (t.album.isNotBlank()) dao.albumTracksOnce(t.albumKey).map { it.uri }.ifEmpty { listOf(t.uri) } else listOf(t.uri)
                    targets.forEach { app.lumen.player.data.Artwork.setCustom(ctxApp, it, bytes) }
                }.isSuccess
            }
            onDone(ok)
        }
    }

    // ---------- настройки звука и поведения ----------

    val smartQueue = MutableStateFlow(g.prefs.smartQueue)
    val normalize = MutableStateFlow(g.prefs.normalize)
    val fadePause = MutableStateFlow(g.prefs.fadePause)
    val resumeLong = MutableStateFlow(g.prefs.resumeLong)
    val btResume = MutableStateFlow(g.prefs.btResume)
    val lockScreen = MutableStateFlow(g.prefs.lockScreen)
    fun setSmartQueue(v: Boolean) { g.prefs.smartQueue = v; smartQueue.value = v }
    fun setNormalize(v: Boolean) {
        g.prefs.normalize = v
        normalize.value = v
        val svc = app.lumen.player.playback.PlaybackService.instance
        player.value.currentId?.let { id -> svc?.applyLoudness(id, ramp = true) }
    }
    fun setFadePause(v: Boolean) { g.prefs.fadePause = v; fadePause.value = v }
    fun setResumeLong(v: Boolean) { g.prefs.resumeLong = v; resumeLong.value = v }
    fun setBtResume(v: Boolean) { g.prefs.btResume = v; btResume.value = v }
    fun setLockScreen(v: Boolean) { g.prefs.lockScreen = v; lockScreen.value = v }

    // ---------- скорость (удержание в плеере) ----------

    fun setSpeed(s: Float) = conn.setSpeed(s)
    fun seekBy(ms: Long) = conn.seekBy(ms)

    // ---------- скорость, тон, повтор A–B, обрезка ----------

    val speed = MutableStateFlow(g.prefs.speed)
    val pitch = MutableStateFlow(g.prefs.pitch)
    val soundSheet = MutableStateFlow(false)

    fun setPlayback(s: Float, p: Float) {
        val sp = s.coerceIn(0.25f, 3f)
        val pi = p.coerceIn(0.5f, 2f)
        speed.value = sp
        pitch.value = pi
        g.prefs.speed = sp
        g.prefs.pitch = pi
        conn.setPlayback(sp, pi)
    }

    fun holdFast(on: Boolean) = conn.holdFast(on, speed.value, pitch.value)

    data class AbState(val a: Long = -1L, val b: Long = -1L) {
        val active get() = a >= 0 && b > a
    }

    val ab = MutableStateFlow(AbState())

    fun setAbPoint(isA: Boolean) {
        val pos = conn.position
        val cur = ab.value
        val next = if (isA) AbState(pos, if (cur.b > pos) cur.b else -1L) else AbState(if (cur.a in 0 until pos) cur.a else 0L, pos)
        ab.value = next
        if (next.active) conn.setAb(next.a, next.b)
    }

    fun clearAb() {
        ab.value = AbState()
        conn.setAb(-1L, -1L)
    }

    val trimTrack = MutableStateFlow<TrackEntity?>(null)

    // ---------- машина, будильник, ночные часы ----------

    val carMode = MutableStateFlow(false)
    val alarm = MutableStateFlow(LumenAlarm.load(g.prefs))

    fun setAlarm(c: AlarmConfig) {
        val wasOn = alarm.value.enabled
        alarm.value = c
        LumenAlarm.save(g.prefs, c)
        LumenAlarm.schedule(getApplication<Application>(), g.prefs)
        if (c.enabled && !wasOn) toast(app.lumen.player.ui.screens.alarmSubtitle(c))
    }

    val nightClock = MutableStateFlow(g.prefs.nightClock)
    fun setNightClock(v: Boolean) { g.prefs.nightClock = v; nightClock.value = v }

    // ---------- цитаты и караоке ----------

    val quoteReq = MutableStateFlow<app.lumen.player.ui.components.QuoteRequest?>(null)
    fun quote(lines: List<String>) {
        val t = currentTrack.value ?: return
        if (lines.isNotEmpty()) quoteReq.value = app.lumen.player.ui.components.QuoteRequest(t, lines)
    }

    val karaoke = MutableStateFlow(g.prefs.karaoke)
    fun setKaraoke(v: Boolean) { g.prefs.karaoke = v; karaoke.value = v }
    val trims = dao.trims().map { l -> l.associateBy { it.uri } }.state(emptyMap())

    fun saveTrim(t: TrackEntity, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            val s0 = startMs.coerceIn(0L, t.durationMs)
            val e0 = if (endMs >= t.durationMs - 500) 0L else endMs
            if (s0 <= 0L && e0 <= 0L) dao.deleteTrim(t.uri)
            else dao.putTrim(app.lumen.player.data.TrimEntity(t.uri, s0, e0))
            toast(if (s0 <= 0L && e0 <= 0L) "Обрезка убрана" else "Трек обрезан — играет без лишнего")
        }
    }

    // ---------- AutoEq ----------

    val autoEq = g.autoEq.state
    private var autoEqCatalog: List<app.lumen.player.playback.AutoEqEntry>? = null
    val autoEqResults = MutableStateFlow<List<app.lumen.player.playback.AutoEqEntry>>(emptyList())
    val autoEqBusy = MutableStateFlow(false)
    val autoEqError = MutableStateFlow<String?>(null)
    private var autoEqJob: Job? = null

    fun refreshAutoEqDevice() = g.autoEq.refreshDevice()
    fun setAutoEqEnabled(on: Boolean) = g.autoEq.setEnabled(on)
    fun clearAutoEq() = g.autoEq.setProfile(null)

    fun searchAutoEq(q: String) {
        autoEqJob?.cancel()
        autoEqJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            val n = normalize(q.trim())
            if (n.length < 2) {
                autoEqResults.value = emptyList()
                return@launch
            }
            val cat = autoEqCatalog ?: run {
                autoEqBusy.value = true
                try {
                    app.lumen.player.playback.AutoEqEngine.catalog(getApplication<Application>()).also { autoEqCatalog = it }
                } catch (e: Exception) {
                    autoEqError.value = "Не удалось загрузить каталог — проверь интернет"
                    null
                } finally {
                    autoEqBusy.value = false
                }
            } ?: return@launch
            autoEqError.value = null
            val words = n.split(' ').filter { it.isNotBlank() }
            autoEqResults.value = withContext(Dispatchers.Default) {
                cat.filter { e -> val k = normalize(e.name); words.all { k.contains(it) } }
                    .sortedBy { it.name.length }
                    .take(40)
            }
        }
    }

    fun pickAutoEq(e: app.lumen.player.playback.AutoEqEntry) {
        viewModelScope.launch {
            autoEqBusy.value = true
            try {
                val p = app.lumen.player.playback.AutoEqEngine.download(e)
                g.autoEq.setProfile(p)
                autoEqError.value = null
                toast("AutoEq: ${e.name} для «${autoEq.value.deviceName}»")
            } catch (t: Exception) {
                autoEqError.value = "Не удалось скачать профиль — попробуй ещё раз"
            } finally {
                autoEqBusy.value = false
            }
        }
    }

    // ---------- обновления по воздуху ----------

    val updateState = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    /** Показывать ли карточку обновления поверх приложения. */
    val updateSheet = MutableStateFlow(false)
    val autoUpdate = MutableStateFlow(g.prefs.autoUpdate)
    val versionName get() = g.updates.currentName
    private var updateJob: Job? = null

    fun setAutoUpdate(on: Boolean) {
        g.prefs.autoUpdate = on
        autoUpdate.value = on
    }

    fun checkUpdates(manual: Boolean) {
        val st = updateState.value
        if (st is UpdateUi.Checking || st is UpdateUi.Downloading || st is UpdateUi.Installing) return
        viewModelScope.launch {
            updateState.value = UpdateUi.Checking
            val r = runCatching { g.updates.latest() }
            val rel = r.getOrNull()
            updateState.value = when {
                r.isFailure -> UpdateUi.Error("Нет связи с сервером обновлений", null)
                rel != null && rel.versionCode > g.updates.currentCode -> {
                    updateSheet.value = true
                    UpdateUi.Available(rel)
                }
                else -> UpdateUi.UpToDate
            }
            if (!manual && updateState.value !is UpdateUi.Available) updateState.value = UpdateUi.Idle
        }
    }

    fun startUpdate() {
        val rel = when (val st = updateState.value) {
            is UpdateUi.Available -> st.release
            is UpdateUi.Error -> st.release ?: return
            is UpdateUi.NeedPermission -> st.release
            else -> return
        }
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            updateState.value = UpdateUi.Downloading(rel, 0f)
            val file = try {
                g.updates.download(rel) { p -> updateState.value = UpdateUi.Downloading(rel, p) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                updateState.value = UpdateUi.Error(e.message ?: "Не удалось скачать", rel)
                return@launch
            }
            installDownloaded(rel, file)
        }
    }

    private suspend fun installDownloaded(rel: Release, file: java.io.File) {
        if (!g.updates.canInstall()) {
            updateState.value = UpdateUi.NeedPermission(rel, file)
            return
        }
        updateState.value = UpdateUi.Installing(rel)
        runCatching { g.updates.install(file) }.onFailure {
            updateState.value = UpdateUi.Error(it.message ?: "Установка не удалась", rel)
        }
    }

    /** Вернулись из настроек «Установка неизвестных приложений». */
    /** Возврат в приложение: свои папки Android не отслеживает, поэтому заглядываем в них сами. */
    fun onAppResume() {
        if (!hasPermission.value) return
        if (g.prefs.folders.isNotEmpty() && g.scanner.lastScanAt > 0 && System.currentTimeMillis() - g.scanner.lastScanAt > 3 * 60_000L) g.rescan()
    }

    fun onResumeForUpdate() {
        val st = updateState.value
        if (st is UpdateUi.NeedPermission && g.updates.canInstall()) {
            viewModelScope.launch { installDownloaded(st.release, st.file) }
        }
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        val st = updateState.value
        val rel = (st as? UpdateUi.Downloading)?.release
        updateState.value = if (rel != null) UpdateUi.Available(rel) else UpdateUi.Idle
    }

    fun hideUpdateSheet() {
        updateSheet.value = false
    }

    @Suppress("unused")
    private val updateWatcher = Unit.also {
        viewModelScope.launch {
            UpdateEvents.failed.collect { msg ->
                val st = updateState.value
                val rel = (st as? UpdateUi.Installing)?.release
                updateState.value = UpdateUi.Error(msg, rel)
            }
        }
        if (g.prefs.autoUpdate) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(2500)
                checkUpdates(manual = false)
            }
        }
    }

    fun setCrossfadeSec(v: Int) {
        val c = v.coerceIn(0, 12)
        g.prefs.crossfadeMs = c * 1000
        crossfadeSec.value = c
    }

    val beatIntensity = MutableStateFlow(g.prefs.beatIntensity)
    fun setBeatIntensity(v: Float) {
        g.prefs.beatIntensity = v
        beatIntensity.value = v
    }

    fun setBeatFlashes(v: Boolean) {
        g.prefs.beatFlashes = v
        beatFlashes.value = v
    }

    private fun startBeats(id: String) {
        beatsJob?.cancel()
        beatsPrefetch?.cancel()
        beats.value = null
        beatsJob = viewModelScope.launch {
            g.beats.analyze(id).collect { beats.value = it }
            // Текущий готов — заранее считаем следующий, чтобы вспышки начались с первой секунды.
            val c = conn.controller ?: return@launch
            val next = c.nextMediaItemIndex
            if (next in 0 until c.mediaItemCount) {
                val nextId = c.getMediaItemAt(next).mediaId
                if (!g.beats.hasCache(nextId)) {
                    beatsPrefetch = g.appScope.launch { g.beats.analyze(nextId).collect { } }
                }
            }
        }
    }

    // ---------- «Самое любимое» и обратная сторона ----------

    /** true — карточка перевёрнута на «обратную сторону луны». */
    val moonSide = MutableStateFlow(false)

    /** Главная на первом «этаже» (видна кнопка) — мини-плеер крупный. */
    val homeAtTop = MutableStateFlow(true)
    fun setMoonSide(v: Boolean) {
        moonSide.value = v
    }

    /** Играет сразу, без открытия плеера: от любимых к забытым (или наоборот). */
    /** 0 — сейчас играет не «Самое любимое», 1 — от любимых к забытым, 2 — наоборот. */
    val lovedMode = MutableStateFlow(0)

    fun playLoved(mostFirst: Boolean) {
        val mode = if (mostFirst) 1 else 2
        if (lovedMode.value == mode && player.value.queue.isNotEmpty()) {
            conn.toggle()
            return
        }
        lovedMode.value = mode
        viewModelScope.launch {
            val list = if (mostFirst) dao.mostLoved() else dao.leastLoved()
            if (list.isNotEmpty()) conn.playTracks(list, 0, shuffle = false)
        }
    }

    /** Треки, которые давно (или никогда) не звучали — для раздела на главной. */
    suspend fun forgottenTracks(): List<TrackEntity> = withContext(Dispatchers.IO) { dao.leastLoved().take(8) }

    fun seekAndPlay(ms: Long) {
        conn.seekTo(ms)
        conn.play()
    }

    fun setSleepMillis(ms: Long?) = g.sleepTimer.setMillis(ms)
    fun sleepEndOfTrack() {
        val p = player.value
        if (p.durationMs <= 0) return
        g.sleepTimer.endOfTrack((p.durationMs - position()).coerceAtLeast(1_000L))
    }

    // ---------- статистика ----------

    suspend fun loadStats(): ListenStats = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val times = dao.listenTimes()
        val byHour = LongArray(24)
        val byWeekday = LongArray(7)
        val days = java.util.TreeSet<Long>()
        val c2 = java.util.Calendar.getInstance()
        for (t in times) {
            c2.timeInMillis = t.startedAt
            byHour[c2.get(java.util.Calendar.HOUR_OF_DAY)] += t.listenedMs
            // Понедельник = 0 … воскресенье = 6
            val wd = (c2.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
            byWeekday[wd] += t.listenedMs
            c2.set(java.util.Calendar.HOUR_OF_DAY, 0)
            c2.set(java.util.Calendar.MINUTE, 0)
            c2.set(java.util.Calendar.SECOND, 0)
            c2.set(java.util.Calendar.MILLISECOND, 0)
            days += c2.timeInMillis
        }
        // Серии дней подряд с музыкой.
        val dayMs = 24L * 3600 * 1000
        var longest = 0
        var run = 0
        var prev = Long.MIN_VALUE
        for (d in days) {
            run = if (prev != Long.MIN_VALUE && kotlin.math.abs(d - prev - dayMs) < 3 * 3600 * 1000) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        var current = 0
        var probe = todayStart
        if (probe !in days) probe -= dayMs
        while (days.any { kotlin.math.abs(it - probe) < 3 * 3600 * 1000 }) {
            current++
            probe -= dayMs
        }
        ListenStats(
            all = dao.listenSummary(0),
            today = dao.listenSummary(todayStart),
            week = dao.listenSummary(now - 7 * dayMs),
            month = dao.listenSummary(now - 30 * dayMs),
            playStarts = g.prefs.playStarts,
            topTracks = dao.topTracks(10),
            topArtists = dao.topArtists(8),
            byHour = byHour,
            byWeekday = byWeekday,
            activeDays = days.size,
            currentStreak = current,
            longestStreak = longest,
            firstListen = times.firstOrNull()?.startedAt,
            libraryTracks = tracks.value.size,
            favorites = dao.favoriteCountAll(),
        )
    }

    // ---------- импорт медиатеки ----------

    /** Импорт из файла экспорта: любимое, статистика, история, исключённые папки, настройки. */
    fun importLibrary(source: Uri, onDone: (ok: Boolean, matched: Int, total: Int) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val text = readBackup(source)
                    val root = org.json.JSONObject(text)
                    require(root.optString("app") == "Lumen") { "not a Lumen file" }
                    val local = dao.allTracksOnce()
                    val byPath = local.filter { it.path.isNotEmpty() }.associateBy { it.path }
                    val byUri = local.associateBy { it.uri }
                    fun keyOf(title: String, artist: String, dur: Long) =
                        "${normalize(title)}|${normalize(artist)}|${dur / 2000}"
                    val byKey = local.associateBy { keyOf(it.title, it.artist, it.durationMs) }

                    val arr = root.optJSONArray("tracks") ?: org.json.JSONArray()
                    val mapped = arrayOfNulls<String>(arr.length())
                    var matched = 0
                    val favs = ArrayList<FavoriteEntity>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val t = byPath[o.optString("path")]
                            ?: byUri[o.optString("uri")]
                            ?: byKey[keyOf(o.optString("title"), o.optString("artist"), o.optLong("durationMs"))]
                            ?: continue
                        mapped[i] = t.uri
                        matched++
                        if (o.has("favoriteAt")) favs += FavoriteEntity(t.uri, o.optLong("favoriteAt"))
                        if (o.has("playCount")) {
                            val old = dao.playStat(t.uri)
                            dao.putPlayStat(
                                app.lumen.player.data.PlayStatEntity(
                                    t.uri,
                                    maxOf(old?.count ?: 0, o.optInt("playCount")),
                                    maxOf(old?.lastPlayed ?: 0L, o.optLong("lastPlayed")),
                                )
                            )
                        }
                    }
                    favs.forEach { dao.addFavorite(it) }

                    root.optJSONArray("listens")?.let { ls ->
                        val existing = dao.allListens().map { "${it.uri}|${it.startedAt}" }.toHashSet()
                        val toInsert = ArrayList<app.lumen.player.data.ListenEntity>()
                        for (i in 0 until ls.length()) {
                            val o = ls.getJSONObject(i)
                            val uri = mapped.getOrNull(o.optInt("t", -1)) ?: continue
                            val started = o.optLong("s")
                            if ("$uri|$started" in existing) continue
                            toInsert += app.lumen.player.data.ListenEntity(
                                uri = uri,
                                startedAt = started,
                                listenedMs = o.optLong("l"),
                                trackDurationMs = o.optLong("d"),
                                completed = o.optBoolean("c"),
                            )
                        }
                        toInsert.chunked(500).forEach { dao.insertListens(it) }
                    }

                    root.optJSONArray("playlists")?.let { pls ->
                        val existing = dao.allPlaylists().map { it.name }.toHashSet()
                        for (i in 0 until pls.length()) {
                            val o = pls.getJSONObject(i)
                            val name = o.optString("name").ifBlank { "Плейлист" }
                            val ids = o.optJSONArray("tracks") ?: continue
                            val uris = (0 until ids.length()).mapNotNull { mapped.getOrNull(ids.optInt(it, -1)) }.distinct()
                            if (name in existing || uris.isEmpty()) continue
                            val now = System.currentTimeMillis()
                            val id = dao.insertPlaylist(app.lumen.player.data.PlaylistEntity(name = name, createdAt = o.optLong("createdAt", now), updatedAt = now))
                            dao.addToPlaylist(id, uris)
                        }
                    }
                    root.optJSONArray("overrides")?.let { ovs ->
                        for (i in 0 until ovs.length()) {
                            val o = ovs.getJSONObject(i)
                            val uri = mapped.getOrNull(o.optInt("t", -1)) ?: continue
                            fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
                            val ov = app.lumen.player.data.TrackOverride(uri, str("title"), str("artist"), str("album"), str("albumArtist"), if (o.isNull("year")) null else o.optInt("year"))
                            dao.putOverride(ov)
                            dao.byUri(uri)?.let { t -> dao.insertTracks(listOf(app.lumen.player.data.applyOverride(t, ov))) }
                        }
                    }
                    root.optJSONArray("trims")?.let { ts ->
                        for (i in 0 until ts.length()) {
                            val o = ts.getJSONObject(i)
                            val uri = mapped.getOrNull(o.optInt("t", -1)) ?: continue
                            dao.putTrim(app.lumen.player.data.TrimEntity(uri, o.optLong("s"), o.optLong("e")))
                        }
                    }
                    root.optJSONArray("dislikes")?.let { ds ->
                        for (i in 0 until ds.length()) {
                            val uri = mapped.getOrNull(ds.optInt(i, -1)) ?: continue
                            dao.addDislike(DislikeEntity(uri, System.currentTimeMillis()))
                        }
                    }
                    root.optJSONArray("smartPlaylists")?.let { sps ->
                        val existing = dao.allSmartPlaylists().map { it.name }.toHashSet()
                        for (i in 0 until sps.length()) {
                            val o = sps.getJSONObject(i)
                            val name = o.optString("name")
                            if (name.isBlank() || name in existing) continue
                            val now = System.currentTimeMillis()
                            dao.putSmartPlaylist(SmartPlaylistEntity(name = name, rules = o.optString("rules"), createdAt = o.optLong("createdAt", now), updatedAt = now))
                        }
                    }
                    root.optJSONArray("excludedFolders")?.let { ex ->
                        val list = (0 until ex.length()).map { app.lumen.player.data.ExcludedFolder(ex.getString(it)) }
                        if (list.isNotEmpty()) dao.addExcluded(list)
                    }
                    root.optJSONObject("settings")?.let { st ->
                        if (st.has("onlineLyrics")) g.prefs.onlineLyrics = st.optBoolean("onlineLyrics")
                        if (st.has("skipShort")) g.prefs.skipShort = st.optBoolean("skipShort")
                        if (st.has("beatFlashes")) g.prefs.beatFlashes = st.optBoolean("beatFlashes")
                        if (st.has("playStarts")) g.prefs.playStarts = maxOf(g.prefs.playStarts, st.optInt("playStarts"))
                        if (st.has("beatIntensity")) g.prefs.beatIntensity = st.optDouble("beatIntensity").toFloat()
                        if (st.has("crossfadeMs")) g.prefs.crossfadeMs = st.optInt("crossfadeMs")
                        if (st.has("showLyrics")) g.prefs.showLyrics = st.optBoolean("showLyrics")
                        if (st.has("autoUpdate")) g.prefs.autoUpdate = st.optBoolean("autoUpdate")
                    }
                    matched to arr.length()
                }
            }
            transfer.value = null
            onlineLyrics.value = g.prefs.onlineLyrics
            skipShort.value = g.prefs.skipShort
            beatFlashes.value = g.prefs.beatFlashes
            beatIntensity.value = g.prefs.beatIntensity
            crossfadeSec.value = g.prefs.crossfadeMs / 1000
            autoUpdate.value = g.prefs.autoUpdate
            showLyrics.value = g.prefs.showLyrics
            val (m, t) = res.getOrDefault(0 to 0)
            onDone(res.isSuccess, m, t)
        }
    }

    // init в конце: к этому моменту все свойства уже созданы.
    init {
        viewModelScope.launch {
            g.scanner.added.collect { n -> toast("Новое в библиотеке: ${pluralRuVm(n, "трек", "трека", "треков")}") }
        }
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

    // ---------- рекомендации ----------

    val recs = MutableStateFlow(g.recs.cached() ?: RecsState())

    // ---------- поиск в сети и история поиска ----------

    /** null — ещё не искали/нет запроса; пустой список — ничего не нашлось. */
    val onlineResults = MutableStateFlow<List<RecItem>?>(null)
    val onlineLoading = MutableStateFlow(false)
    val onlineError = MutableStateFlow(false)
    private var onlineJob: Job? = null
    @Suppress("unused")
    private val onlineWatcher = Unit.also { watchOnlineSearch() }

    private fun watchOnlineSearch() {
        viewModelScope.launch {
            query.debounce(450).distinctUntilChanged().collect { raw ->
                onlineJob?.cancel()
                val q = raw.trim()
                if (q.length < 2) {
                    onlineResults.value = null
                    onlineLoading.value = false
                    onlineError.value = false
                    return@collect
                }
                onlineJob = viewModelScope.launch {
                    onlineLoading.value = true
                    onlineError.value = false
                    val r = runCatching { g.recs.searchTracks(q) }
                    if (query.value.trim() == q) {
                        onlineResults.value = r.getOrNull() ?: emptyList()
                        onlineError.value = r.isFailure
                        onlineLoading.value = false
                    }
                }
            }
        }
    }

    private val historyUris = MutableStateFlow(g.prefs.searchHistory)
    val searchHistory: StateFlow<List<TrackEntity>> =
        combine(historyUris, trackIndex) { uris, idx -> uris.mapNotNull { idx[it] } }.state(emptyList())

    /** Включить трек из результатов поиска и запомнить его в истории. */
    fun playFromSearch(list: List<TrackEntity>, index: Int) {
        list.getOrNull(index)?.let { t ->
            val h = (listOf(t.uri) + historyUris.value.filter { it != t.uri }).take(40)
            historyUris.value = h
            g.prefs.searchHistory = h
        }
        play(list, index)
    }

    fun clearSearchHistory() {
        historyUris.value = emptyList()
        g.prefs.searchHistory = emptyList()
    }
    private var recsJob: Job? = null

    /** Обновляем, если кэш старше суток (или по кнопке). Секции появляются по мере готовности. */
    fun refreshRecs(force: Boolean = false) {
        if (recsJob?.isActive == true) return
        val cur = recs.value
        if (!force && cur.sections.isNotEmpty() && System.currentTimeMillis() - cur.updatedAt < 24L * 3600 * 1000) return
        recs.value = cur.copy(loading = true, offline = false)
        recsJob = viewModelScope.launch {
            val result = runCatching {
                g.recs.build { partial -> if (partial.isNotEmpty()) recs.value = recs.value.copy(sections = partial, loading = true) }
            }
            val built = result.getOrNull()
            recs.value = when {
                built != null && built.sections.isNotEmpty() -> built.copy(loading = false)
                else -> recs.value.copy(loading = false, offline = true)
            }
        }
    }

    fun hideRec(item: RecItem) {
        g.prefs.hiddenRecs = g.prefs.hiddenRecs + item.key
        recs.value = recs.value.copy(
            sections = recs.value.sections.map { s -> s.copy(items = s.items.filter { it.key != item.key }) }.filter { it.items.isNotEmpty() }
        )
    }

    fun album(key: String) = dao.album(key)
    fun albumTracks(key: String) = dao.albumTracks(key)
    fun artistTracks(name: String) = tracks.map { list ->
        list.filter { Artists.has(it, name) }
            .sortedWith(compareByDescending<TrackEntity> { it.year }.thenBy { it.albumKey }.thenBy { it.discNo }.thenBy { it.trackNo })
    }.flowOn(Dispatchers.Default)

    fun albumsOfArtist(name: String) = combine(albums, artistTracks(name)) { all, mine ->
        val keys = mine.mapTo(HashSet()) { it.albumKey }
        all.filter { it.albumKey in keys }.sortedByDescending { it.year }
    }.flowOn(Dispatchers.Default)
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

data class SmartRow(
    val id: Long,
    val name: String,
    val rules: SmartRules,
    val count: Int,
    val durationMs: Long,
    val covers: List<String>,
)

/** Группа одинаковых треков: первым идёт лучший по качеству. */
data class DupGroup(val id: String, val tracks: List<TrackEntity>, val best: String)

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

data class ListenStats(
    val all: app.lumen.player.data.ListenSummary,
    val today: app.lumen.player.data.ListenSummary,
    val week: app.lumen.player.data.ListenSummary,
    val month: app.lumen.player.data.ListenSummary,
    val playStarts: Int,
    val topTracks: List<app.lumen.player.data.TopTrack>,
    val topArtists: List<app.lumen.player.data.TopName>,
    val byHour: LongArray,
    val byWeekday: LongArray,
    val activeDays: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val firstListen: Long?,
    val libraryTracks: Int,
    val favorites: Int,
)

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val release: Release) : UpdateUi
    data class Downloading(val release: Release, val progress: Float) : UpdateUi
    data class NeedPermission(val release: Release, val file: java.io.File) : UpdateUi
    data class Installing(val release: Release) : UpdateUi
    data class Error(val message: String, val release: Release?) : UpdateUi
}

data class SheetTrack(val track: TrackEntity, val edit: Boolean)

data class WrappedData(
    val monthName: String,
    val totalMs: Long,
    val plays: Int,
    val topArtists: List<app.lumen.player.data.TopName>,
    val topTracks: List<app.lumen.player.data.TopTrack>,
    val topAlbum: app.lumen.player.data.TopName?,
    val newTracks: Int,
    val peakHour: Int,
    val byHour: List<Long>,
    val topWeekday: Int,
    val activeDays: Int,
    val streak: Int,
)

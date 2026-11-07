package app.lumen.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val AUDIO_EXT = setOf("flac", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "wv", "alac", "mka")

/** Папки, из которых не берём «музыку»: голосовые, записи звонков, системные звуки. */
private val SKIP_PATH_PARTS = listOf(
    "voice notes", "telegram voice", "/recordings/", "/call/", "call recordings",
    "callrecord", "sound_recorder", "/notifications/", "/ringtones/", "/alarms/",
)

const val UNKNOWN_ARTIST = "Неизвестный исполнитель"
const val UNKNOWN_ALBUM = "Неизвестный альбом"

class MediaScanner(
    private val context: Context,
    private val dao: LibraryDao,
    private val prefs: Prefs,
) {
    private val mutex = Mutex()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Сколько новых треков нашёл последний пересчёт (не первый) — для подсказки «Новое в библиотеке». */
    val added = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)
    @Volatile var lastScanAt = 0L
        private set

    suspend fun scan() = mutex.withLock {
        _scanning.value = true
        try {
            withContext(Dispatchers.IO) {
                val before = dao.allTracksOnce().mapTo(HashSet()) { it.uri }
                val ms = runCatching { scanMediaStore() }.getOrNull()
                if (ms != null) dao.replaceSource(SOURCE_MEDIASTORE, ms)
                val known = (ms ?: emptyList()).mapTo(HashSet()) { dedupeKey(it.title, it.artist, it.durationMs) }
                val saf = runCatching { scanFolders(known) }.getOrNull()
                if (saf != null) dao.replaceSource(SOURCE_FOLDER, saf)
                autoExclude(ms.orEmpty() + saf.orEmpty())
                if (before.isNotEmpty()) {
                    val fresh = (ms.orEmpty() + saf.orEmpty()).count { it.uri !in before }
                    if (fresh > 0) added.tryEmit(fresh)
                }
                lastScanAt = System.currentTimeMillis()
            }
        } finally {
            _scanning.value = false
        }
    }

    private val musicFolders = HashSet<String>()
    private val nonMusicFolders = HashSet<String>()

    /**
     * Новые папки с голосовыми, записями звонков, рингтонами и т.п. скрываются сами при первом обнаружении.
     * Пользователь может вернуть их в настройках — повторно они уже не скроются.
     */
    private suspend fun autoExclude(all: List<TrackEntity>) {
        val seen = prefs.seenFolders
        val folders = all.map { it.folder }.toSet()
        val fresh = folders - seen
        if (fresh.isEmpty()) return
        val toExclude = fresh.filter { f ->
            val lower = (f + "/").lowercase()
            SKIP_PATH_PARTS.any { lower.contains(it) } || (f in nonMusicFolders && f !in musicFolders)
        }
        if (toExclude.isNotEmpty()) dao.addExcluded(toExclude.map { ExcludedFolder(it) })
        prefs.seenFolders = seen + fresh
    }

    private fun dedupeKey(title: String, artist: String, durationMs: Long) =
        "${title.lowercase()}|${artist.lowercase()}|${durationMs / 2000}"

    private fun minDuration() = if (prefs.skipShort) 30_000L else 5_000L

    private fun scanMediaStore(): List<TrackEntity> {
        val modern = Build.VERSION.SDK_INT >= 30
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
        )
        if (modern) {
            cols += "album_artist"
            cols += "disc_number"
            cols += "bitrate"
        }
        cols += MediaStore.Audio.Media.IS_MUSIC
        // Берём все звуки, а не только «музыку»: лишние папки пользователь исключает сам в настройках.
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val out = ArrayList<TrackEntity>()
        nonMusicFolders.clear()
        musicFolders.clear()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cols.toTypedArray(),
            selection,
            arrayOf(minDuration().toString()),
            null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iAlbumArtist = if (modern) c.getColumnIndex("album_artist") else -1
            val iDisc = if (modern) c.getColumnIndex("disc_number") else -1
            val iBitrate = if (modern) c.getColumnIndex("bitrate") else -1
            val iIsMusic = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)

            while (c.moveToNext()) {
                val path = c.getString(iData).orEmpty()
                val parentPath = File(path).parent.orEmpty()
                if (c.getInt(iIsMusic) != 0) musicFolders.add(parentPath) else nonMusicFolders.add(parentPath)

                val id = c.getLong(iId)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                val file = File(path)
                val title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                val artist = cleanUnknown(c.getString(iArtist)) ?: UNKNOWN_ARTIST
                val album = cleanUnknown(c.getString(iAlbum)) ?: (file.parentFile?.name ?: UNKNOWN_ALBUM)
                val tagAlbumArtist = if (iAlbumArtist >= 0) cleanUnknown(c.getString(iAlbumArtist)) else null
                val albumArtist = tagAlbumArtist ?: artist
                val albumKey = if (tagAlbumArtist != null) {
                    "${tagAlbumArtist.lowercase()}|${album.lowercase()}"
                } else {
                    "id:${c.getLong(iAlbumId)}"
                }
                val rawTrack = c.getInt(iTrack)
                val discTag = if (iDisc >= 0) leadingInt(c.getString(iDisc)) else 0
                val disc = when {
                    discTag > 0 -> discTag
                    rawTrack >= 1000 -> rawTrack / 1000
                    else -> 1
                }
                out += TrackEntity(
                    uri = uri,
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    albumKey = albumKey,
                    durationMs = c.getLong(iDur),
                    trackNo = rawTrack % 1000,
                    discNo = disc,
                    year = c.getInt(iYear),
                    mime = c.getString(iMime).orEmpty(),
                    path = path,
                    folder = file.parent.orEmpty(),
                    folderName = file.parentFile?.name ?: "/",
                    dateAdded = c.getLong(iAdded) * 1000L,
                    size = c.getLong(iSize),
                    bitrate = if (iBitrate >= 0) c.getInt(iBitrate) else 0,
                    source = SOURCE_MEDIASTORE,
                )
            }
        }
        return out
    }

    private suspend fun scanFolders(known: Set<String>): List<TrackEntity> {
        val roots = prefs.folders
        if (roots.isEmpty()) return emptyList()
        val cached = dao.tracksOfSource(SOURCE_FOLDER).associateBy { it.uri }
        val out = ArrayList<TrackEntity>()
        for (tree in roots) {
            val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull() ?: continue
            walk(root, 0, cached, known, out)
        }
        return out
    }

    private fun walk(
        dir: DocumentFile,
        depth: Int,
        cached: Map<String, TrackEntity>,
        known: Set<String>,
        out: MutableList<TrackEntity>,
    ) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (depth < 10) walk(f, depth + 1, cached, known, out)
                continue
            }
            val name = f.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO_EXT) continue
            val uri = f.uri.toString()
            val modified = f.lastModified()
            val old = cached[uri]
            val track = if (old != null && old.dateAdded == modified) old else readFile(f, dir, name, modified)
            if (track == null) continue
            if (track.durationMs < minDuration()) continue
            if (dedupeKey(track.title, track.artist, track.durationMs) in known) continue
            out += track
        }
    }

    private fun readFile(f: DocumentFile, dir: DocumentFile, name: String, modified: Long): TrackEntity? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, f.uri)
            fun m(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val title = m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name.substringBeforeLast('.')
            val artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: UNKNOWN_ARTIST
            val album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: (dir.name ?: UNKNOWN_ALBUM)
            val albumArtist = m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val aa = albumArtist ?: artist
            TrackEntity(
                uri = f.uri.toString(),
                title = title,
                artist = artist,
                album = album,
                albumArtist = aa,
                albumKey = "${aa.lowercase()}|${album.lowercase()}",
                durationMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                trackNo = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNo = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)).coerceAtLeast(1),
                year = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_YEAR)),
                mime = m(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: f.type.orEmpty(),
                path = "",
                folder = dir.uri.toString(),
                folderName = dir.name ?: "Папка",
                dateAdded = modified,
                size = f.length(),
                bitrate = m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                source = SOURCE_FOLDER,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun cleanUnknown(s: String?): String? {
        val v = s?.trim() ?: return null
        if (v.isEmpty() || v == "<unknown>") return null
        return v
    }

    private fun leadingInt(s: String?): Int =
        s?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}

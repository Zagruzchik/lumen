package app.lumen.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.lumen.player.data.Artists
import app.lumen.player.data.LibraryDao
import app.lumen.player.data.SmartContext
import app.lumen.player.data.SmartEngine
import app.lumen.player.data.SmartRules
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.formatLabel
import app.lumen.player.ui.normalize
import kotlinx.coroutines.flow.first

/**
 * Дерево медиатеки для Android Auto и других «браузеров»: вкладки «Для тебя», «Плейлисты»,
 * «Альбомы», «Исполнители» и поиск голосом. Трек внутри списка помнит, откуда его выбрали,
 * поэтому после него играет весь альбом / плейлист, а не один трек.
 */
object AutoLibrary {
    const val ROOT = "root"
    private const val HOME = "tab:home"
    private const val PLAYLISTS = "tab:playlists"
    private const val ALBUMS = "tab:albums"
    private const val ARTISTS = "tab:artists"
    private const val SEP = '\u001F'

    fun root(): MediaItem = folder(ROOT, "Lumen")

    private fun folder(id: String, title: String, subtitle: String? = null, playable: Boolean = false, art: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(!playable)
                    .setIsPlayable(playable)
                    .setMediaType(if (playable) MediaMetadata.MEDIA_TYPE_PLAYLIST else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(art)
                    .build()
            )
            .build()

    private fun track(parent: String, t: TrackEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId("ctx:$parent$SEP${t.uri}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    suspend fun children(dao: LibraryDao, parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            folder(HOME, "Для тебя"),
            folder(PLAYLISTS, "Плейлисты"),
            folder(ALBUMS, "Альбомы"),
            folder(ARTISTS, "Исполнители"),
        )
        HOME -> listOf(
            folder("play:loved", "Самое любимое", "Сначала то, что слушаешь чаще всего", playable = true),
            folder("play:favshuffle", "Любимое вперемешку", playable = true),
            folder("play:forgotten", "Обратная сторона", "То, что давно не звучало", playable = true),
            folder("list:favorites", "Любимое"),
        )
        PLAYLISTS -> {
            val smart = dao.allSmartPlaylists().map { folder("sp:${it.id}", it.name, "Умный плейлист") }
            val pls = dao.allPlaylists().sortedByDescending { it.updatedAt }.map { folder("pl:${it.id}", it.name) }
            smart + pls
        }
        ALBUMS -> dao.albums().first().take(800).map { a ->
            folder("al:${a.albumKey}", a.album, listOf(a.albumArtist, a.year.takeIf { it > 0 }?.toString()).filterNotNull().joinToString(" · "))
        }
        ARTISTS -> dao.artists().first().take(800).map { a -> folder("ar:${a.name}", a.name) }
        else -> tracksOf(dao, parentId).map { track(parentId, it) }
    }

    /** Треки списка по его id. */
    suspend fun tracksOf(dao: LibraryDao, id: String): List<TrackEntity> = when {
        id == "list:favorites" -> dao.favorites().first()
        id.startsWith("pl:") -> dao.playlistTracks(id.removePrefix("pl:").toLongOrNull() ?: -1).first()
        id.startsWith("sp:") -> {
            val sp = dao.smartPlaylistOnce(id.removePrefix("sp:").toLongOrNull() ?: -1)
            if (sp == null) emptyList() else {
                val ctx = SmartContext(
                    plays = dao.allPlays().associateBy { it.uri },
                    favorites = dao.allFavorites().mapTo(HashSet()) { it.uri },
                    disliked = dao.allDislikes().mapTo(HashSet()) { it.uri },
                    format = { formatLabel(it.mime, it.uri) },
                )
                SmartEngine.evaluate(SmartRules.parse(sp.rules), dao.tracks().first(), ctx, sp.id)
            }
        }
        id.startsWith("al:") -> dao.albumTracks(id.removePrefix("al:")).first()
        id.startsWith("ar:") -> {
            val name = id.removePrefix("ar:")
            dao.visibleTracksOnce().filter { Artists.has(it, name) }
        }
        id.startsWith("q:") -> search(dao, id.removePrefix("q:"))
        else -> emptyList()
    }

    suspend fun search(dao: LibraryDao, query: String): List<TrackEntity> {
        val words = normalize(query.trim()).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return dao.visibleTracksOnce().filter { t ->
            val k = normalize("${t.title} ${t.artist} ${t.album}")
            words.all { k.contains(it) }
        }.take(60)
    }

    fun searchItems(query: String, list: List<TrackEntity>): List<MediaItem> = list.map { track("q:$query", it) }

    /**
     * То, что выбрали в машине, превращается в очередь: весь список и позиция выбранного трека.
     * null — это обычный трек по URI (с телефона), его не трогаем.
     */
    suspend fun expand(dao: LibraryDao, mediaId: String): Pair<List<TrackEntity>, Int>? = when {
        mediaId == "play:loved" -> dao.mostLoved() to 0
        mediaId == "play:forgotten" -> dao.leastLoved() to 0
        mediaId == "play:favshuffle" -> {
            val dis = dao.allDislikes().mapTo(HashSet()) { it.uri }
            dao.favorites().first().filter { it.uri !in dis }.shuffled() to 0
        }
        mediaId.startsWith("ctx:") -> {
            val body = mediaId.removePrefix("ctx:")
            val parent = body.substringBefore(SEP)
            val uri = body.substringAfter(SEP)
            val list = tracksOf(dao, parent)
            val idx = list.indexOfFirst { it.uri == uri }
            when {
                idx >= 0 -> list to idx
                uri.isEmpty() && list.isNotEmpty() -> list to 0
                else -> dao.byUri(uri)?.let { listOf(it) to 0 }
            }
        }
        else -> null
    }

    /** URI трека из id элемента дерева (для одиночного добавления в очередь). */
    fun uriOf(mediaId: String): String = if (mediaId.startsWith("ctx:")) mediaId.substringAfter(SEP) else mediaId
}

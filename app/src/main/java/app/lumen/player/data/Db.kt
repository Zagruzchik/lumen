package app.lumen.player.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val SOURCE_MEDIASTORE = 0
const val SOURCE_FOLDER = 1

@Entity(
    tableName = "tracks",
    indices = [Index("albumKey"), Index("albumArtist"), Index("folder")]
)
data class TrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumKey: String,
    val durationMs: Long,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val mime: String,
    val path: String,
    val folder: String,
    val folderName: String,
    val dateAdded: Long,
    val size: Long,
    val bitrate: Int,
    val source: Int,
    /** Жанр из тегов файла или из каталога (для онлайн-треков). */
    @androidx.room.ColumnInfo(defaultValue = "") val genre: String = "",
)

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val uri: String, val addedAt: Long)

@Entity(tableName = "plays")
data class PlayStatEntity(@PrimaryKey val uri: String, val count: Int, val lastPlayed: Long)

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val key: String,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
    val source: String,
    val fetchedAt: Long,
)

/** Одно прослушивание трека: сколько реально играло, дослушано ли до конца. */
@Entity(tableName = "listens", indices = [Index("uri"), Index("startedAt")])
data class ListenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val startedAt: Long,
    val listenedMs: Long,
    val trackDurationMs: Long,
    val completed: Boolean,
)

data class ListenSummary(
    val count: Int,
    val total: Long,
    val minMs: Long,
    val avgMs: Double,
    val maxMs: Long,
    val completedCount: Int,
    val uniqueTracks: Int,
)

data class TopTrack(val uri: String, val title: String, val artist: String, val totalMs: Long, val plays: Int)
data class TopName(val name: String, val totalMs: Long, val plays: Int)
data class ListenTime(val startedAt: Long, val listenedMs: Long)

// ---------- плейлисты ----------

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "uri"],
    indices = [Index("playlistId")],
)
data class PlaylistTrackEntity(val playlistId: Long, val uri: String, val position: Int)

data class PlaylistRow(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val trackCount: Int,
    val durationMs: Long,
)

/** Свои правки тегов (файлы не трогаем — правки живут в Lumen и в бэкапе). */
@Entity(tableName = "track_overrides")
data class TrackOverride(
    @PrimaryKey val uri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
)

/** Позиция, на которой остановились в длинном треке (подкасты, миксы, аудиокниги). */
@Entity(tableName = "positions")
data class PositionEntity(@PrimaryKey val uri: String, val posMs: Long, val updatedAt: Long)

/** Поправка громкости трека в дБ для выравнивания. */
@Entity(tableName = "loudness")
data class LoudnessEntity(@PrimaryKey val uri: String, val gainDb: Float)

/** «Не нравится»: такие треки не попадают в перемешивание, умную очередь, похожее и подборки. */
@Entity(tableName = "dislikes")
data class DislikeEntity(@PrimaryKey val uri: String, val addedAt: Long)

/** Умный плейлист: состав собирается сам по правилам (JSON, см. SmartRules). */
@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rules: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class GenreRow(val name: String, val count: Int)

/** Обрезка трека: начинать с startMs, заканчивать на endMs (0 — до конца). */
@Entity(tableName = "trims")
data class TrimEntity(@PrimaryKey val uri: String, val startMs: Long, val endMs: Long)

@Entity(tableName = "excluded")
data class ExcludedFolder(@PrimaryKey val folder: String)

/** Трек виден в библиотеке, если его папка не в списке исключений. Онлайн-треки в библиотеку не попадают. */
private const val VISIBLE = "folder NOT IN (SELECT folder FROM excluded) AND source != 2"
private const val VISIBLE_T = "t.folder NOT IN (SELECT folder FROM excluded)"
private const val LIKED_T = "t.uri NOT IN (SELECT uri FROM dislikes)"

data class AlbumRow(
    val albumKey: String,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val trackCount: Int,
    val durationMs: Long,
    val coverUri: String,
    val dateAdded: Long,
    val mime: String,
)

data class ArtistRow(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val coverUri: String,
)

data class FolderRow(
    val folder: String,
    val folderName: String,
    val trackCount: Int,
    val coverUri: String,
)

private const val ALBUM_SELECT = """
    SELECT albumKey, album, albumArtist, MAX(year) AS year, COUNT(*) AS trackCount,
           SUM(durationMs) AS durationMs, MIN(uri) AS coverUri, MAX(dateAdded) AS dateAdded,
           MAX(mime) AS mime
    FROM tracks WHERE $VISIBLE
"""

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM tracks WHERE $VISIBLE ORDER BY title COLLATE NOCASE")
    abstract fun tracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE $VISIBLE ORDER BY albumArtist, album, discNo, trackNo")
    abstract suspend fun visibleTracksOnce(): List<TrackEntity>

    @Query("SELECT * FROM favorites")
    abstract suspend fun allFavorites(): List<FavoriteEntity>

    @Query("SELECT * FROM plays")
    abstract suspend fun allPlays(): List<PlayStatEntity>

    @Query("SELECT folder FROM excluded")
    abstract suspend fun excludedOnce(): List<String>

    @Query("SELECT * FROM tracks WHERE source = :source")
    abstract suspend fun tracksOfSource(source: Int): List<TrackEntity>

    @Query("$ALBUM_SELECT GROUP BY albumKey ORDER BY album COLLATE NOCASE")
    abstract fun albums(): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT GROUP BY albumKey ORDER BY dateAdded DESC LIMIT 16")
    abstract fun recentAlbums(): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT AND albumArtist = :name GROUP BY albumKey ORDER BY year DESC")
    abstract fun albumsOfArtist(name: String): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT AND albumKey = :key GROUP BY albumKey")
    abstract fun album(key: String): Flow<AlbumRow?>

    @Query(
        """SELECT albumArtist AS name, COUNT(DISTINCT albumKey) AS albumCount,
           COUNT(*) AS trackCount, MIN(uri) AS coverUri
           FROM tracks WHERE $VISIBLE GROUP BY albumArtist ORDER BY albumArtist COLLATE NOCASE"""
    )
    abstract fun artists(): Flow<List<ArtistRow>>

    @Query(
        """SELECT folder, folderName, COUNT(*) AS trackCount, MIN(uri) AS coverUri
           FROM tracks WHERE $VISIBLE GROUP BY folder ORDER BY folderName COLLATE NOCASE"""
    )
    abstract fun folders(): Flow<List<FolderRow>>

    @Query("SELECT * FROM tracks WHERE albumKey = :key AND $VISIBLE ORDER BY discNo, trackNo, title COLLATE NOCASE")
    abstract fun albumTracks(key: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumKey = :key")
    abstract suspend fun albumTracksOnce(key: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE albumArtist = :name AND $VISIBLE ORDER BY year DESC, albumKey, discNo, trackNo")
    abstract fun artistTracks(name: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folder = :folder AND $VISIBLE ORDER BY trackNo, title COLLATE NOCASE")
    abstract fun folderTracks(folder: String): Flow<List<TrackEntity>>

    @Query(
        """SELECT t.* FROM tracks t INNER JOIN plays p ON p.uri = t.uri
           WHERE $VISIBLE_T ORDER BY p.count DESC, p.lastPlayed DESC LIMIT 12"""
    )
    abstract fun frequent(): Flow<List<TrackEntity>>

    @Query(
        """SELECT t.* FROM tracks t INNER JOIN plays p ON p.uri = t.uri
           WHERE $VISIBLE_T ORDER BY p.lastPlayed DESC LIMIT 1"""
    )
    abstract fun lastPlayed(): Flow<TrackEntity?>

    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON f.uri = t.uri WHERE $VISIBLE_T ORDER BY f.addedAt DESC")
    abstract fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT uri FROM favorites")
    abstract fun favoriteUris(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE uri IN (:uris)")
    abstract suspend fun byUris(uris: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE uri = :uri")
    abstract suspend fun byUri(uri: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTracks(list: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE source = :source")
    abstract suspend fun deleteSource(source: Int)

    @Transaction
    open suspend fun replaceSource(source: Int, list: List<TrackEntity>) {
        deleteSource(source)
        val ov = allOverrides().associateBy { it.uri }
        val fixed = if (ov.isEmpty()) list else list.map { t -> ov[t.uri]?.let { applyOverride(t, it) } ?: t }
        fixed.chunked(400).forEach { insertTracks(it) }
    }

    // ---------- свои теги ----------

    @Query("SELECT * FROM track_overrides")
    abstract suspend fun allOverrides(): List<TrackOverride>

    @Query("SELECT * FROM track_overrides WHERE uri = :uri")
    abstract suspend fun override(uri: String): TrackOverride?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putOverride(o: TrackOverride)

    // ---------- плейлисты ----------

    @Query(
        """SELECT p.id AS id, p.name AS name, p.updatedAt AS updatedAt,
           COUNT(t.uri) AS trackCount, COALESCE(SUM(t.durationMs), 0) AS durationMs
           FROM playlists p
           LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
           LEFT JOIN tracks t ON t.uri = pt.uri
           GROUP BY p.id ORDER BY p.updatedAt DESC"""
    )
    abstract fun playlists(): Flow<List<PlaylistRow>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun playlist(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT t.* FROM playlist_tracks pt INNER JOIN tracks t ON t.uri = pt.uri
           WHERE pt.playlistId = :id ORDER BY pt.position"""
    )
    abstract fun playlistTracks(id: Long): Flow<List<TrackEntity>>

    @Query("SELECT uri FROM playlist_tracks WHERE playlistId = :id ORDER BY position LIMIT 4")
    abstract fun playlistCovers(id: Long): Flow<List<String>>

    @Query("SELECT playlistId FROM playlist_tracks WHERE uri = :uri")
    abstract suspend fun playlistsWith(uri: String): List<Long>

    @Insert
    abstract suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    abstract suspend fun touchPlaylist(id: Long, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    abstract suspend fun clearPlaylist(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :id")
    abstract suspend fun maxPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlaylistTracks(list: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id AND uri = :uri")
    abstract suspend fun removeFromPlaylist(id: Long, uri: String)

    @Query("SELECT * FROM playlists")
    abstract suspend fun allPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position")
    abstract suspend fun allPlaylistTracks(): List<PlaylistTrackEntity>

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        clearPlaylist(id)
        deletePlaylistRow(id)
    }

    @Transaction
    open suspend fun addToPlaylist(id: Long, uris: List<String>) {
        var pos = maxPosition(id) + 1
        insertPlaylistTracks(uris.map { PlaylistTrackEntity(id, it, pos++) })
        touchPlaylist(id, System.currentTimeMillis())
    }

    /** Новый порядок целиком (после перетаскивания). */
    @Transaction
    open suspend fun reorderPlaylist(id: Long, uris: List<String>) {
        clearPlaylist(id)
        insertPlaylistTracks(uris.mapIndexed { i, u -> PlaylistTrackEntity(id, u, i) })
        touchPlaylist(id, System.currentTimeMillis())
    }

    // ---------- позиции и громкость ----------

    @Query("SELECT * FROM positions WHERE uri = :uri")
    abstract suspend fun position(uri: String): PositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putPosition(p: PositionEntity)

    @Query("DELETE FROM positions WHERE uri = :uri")
    abstract suspend fun clearPosition(uri: String)

    @Query("SELECT * FROM loudness WHERE uri = :uri")
    abstract suspend fun loudness(uri: String): LoudnessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putLoudness(l: LoudnessEntity)

    // ---------- для «Похожего» и итогов месяца ----------

    @Query(
        """SELECT l.uri AS uri, t.title AS title, t.artist AS artist,
           SUM(l.listenedMs) AS totalMs, COUNT(*) AS plays
           FROM listens l INNER JOIN tracks t ON t.uri = l.uri
           WHERE l.startedAt >= :from AND l.startedAt < :to
           GROUP BY l.uri ORDER BY totalMs DESC LIMIT :n"""
    )
    abstract suspend fun topTracksIn(from: Long, to: Long, n: Int): List<TopTrack>

    @Query(
        """SELECT t.artist AS name, SUM(l.listenedMs) AS totalMs, COUNT(*) AS plays
           FROM listens l INNER JOIN tracks t ON t.uri = l.uri
           WHERE l.startedAt >= :from AND l.startedAt < :to
           GROUP BY t.artist ORDER BY totalMs DESC LIMIT :n"""
    )
    abstract suspend fun topArtistsIn(from: Long, to: Long, n: Int): List<TopName>

    @Query(
        """SELECT t.album AS name, SUM(l.listenedMs) AS totalMs, COUNT(*) AS plays
           FROM listens l INNER JOIN tracks t ON t.uri = l.uri
           WHERE l.startedAt >= :from AND l.startedAt < :to AND t.album != ''
           GROUP BY t.albumKey ORDER BY totalMs DESC LIMIT :n"""
    )
    abstract suspend fun topAlbumsIn(from: Long, to: Long, n: Int): List<TopName>

    @Query(
        """SELECT COUNT(*) AS plays, COALESCE(SUM(listenedMs), 0) AS listenedMs,
           MIN(startedAt) AS firstAt, MAX(startedAt) AS lastAt FROM listens WHERE uri = :uri"""
    )
    abstract suspend fun trackListenStats(uri: String): TrackListenStats

    @Query("SELECT startedAt, listenedMs FROM listens WHERE startedAt >= :from AND startedAt < :to")
    abstract suspend fun listenTimesIn(from: Long, to: Long): List<ListenTime>

    /** Сколько треков впервые прозвучало в этом периоде. */
    @Query(
        """SELECT COUNT(*) FROM (SELECT uri, MIN(startedAt) AS first FROM listens GROUP BY uri)
           WHERE first >= :from AND first < :to"""
    )
    abstract suspend fun newTracksIn(from: Long, to: Long): Int

    /** Все папки, где нашлись звуки, включая исключённые. */
    @Query(
        """SELECT folder, folderName, COUNT(*) AS trackCount, MIN(uri) AS coverUri
           FROM tracks GROUP BY folder ORDER BY folderName COLLATE NOCASE"""
    )
    abstract fun allFolders(): Flow<List<FolderRow>>

    @Query("SELECT DISTINCT folder FROM tracks")
    abstract suspend fun knownFolders(): List<String>

    @Query("SELECT folder FROM excluded")
    abstract fun excludedFolders(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addExcluded(list: List<ExcludedFolder>)

    @Query("DELETE FROM excluded WHERE folder = :folder")
    abstract suspend fun removeExcluded(folder: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE uri = :uri")
    abstract suspend fun removeFavorite(uri: String)

    @Query("SELECT COUNT(*) FROM favorites WHERE uri = :uri")
    abstract suspend fun favoriteCount(uri: String): Int

    @Query("SELECT * FROM plays WHERE uri = :uri")
    abstract suspend fun playStat(uri: String): PlayStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putPlayStat(p: PlayStatEntity)

    @Transaction
    open suspend fun recordPlay(uri: String) {
        val old = playStat(uri)
        putPlayStat(PlayStatEntity(uri, (old?.count ?: 0) + 1, System.currentTimeMillis()))
    }

    // ---------- статистика ----------

    @Insert
    abstract suspend fun insertListen(l: ListenEntity)

    @Insert
    abstract suspend fun insertListens(l: List<ListenEntity>)

    @Query(
        """SELECT COUNT(*) AS count, COALESCE(SUM(listenedMs), 0) AS total,
           COALESCE(MIN(listenedMs), 0) AS minMs, COALESCE(AVG(listenedMs), 0) AS avgMs,
           COALESCE(MAX(listenedMs), 0) AS maxMs, COALESCE(SUM(completed), 0) AS completedCount,
           COUNT(DISTINCT uri) AS uniqueTracks
           FROM listens WHERE startedAt >= :since"""
    )
    abstract suspend fun listenSummary(since: Long): ListenSummary

    @Query(
        """SELECT l.uri AS uri, t.title AS title, t.artist AS artist,
           SUM(l.listenedMs) AS totalMs, COUNT(*) AS plays
           FROM listens l INNER JOIN tracks t ON t.uri = l.uri
           GROUP BY l.uri ORDER BY totalMs DESC LIMIT :n"""
    )
    abstract suspend fun topTracks(n: Int): List<TopTrack>

    @Query(
        """SELECT t.artist AS name, SUM(l.listenedMs) AS totalMs, COUNT(*) AS plays
           FROM listens l INNER JOIN tracks t ON t.uri = l.uri
           GROUP BY t.artist ORDER BY totalMs DESC LIMIT :n"""
    )
    abstract suspend fun topArtists(n: Int): List<TopName>

    @Query("SELECT startedAt, listenedMs FROM listens ORDER BY startedAt")
    abstract suspend fun listenTimes(): List<ListenTime>

    @Query("SELECT * FROM listens")
    abstract suspend fun allListens(): List<ListenEntity>

    @Query("SELECT COUNT(*) FROM favorites")
    abstract suspend fun favoriteCountAll(): Int

    @Query("SELECT * FROM tracks")
    abstract suspend fun allTracksOnce(): List<TrackEntity>

    // ---------- жанры ----------

    @Query("SELECT genre AS name, COUNT(*) AS count FROM tracks WHERE $VISIBLE AND genre != '' GROUP BY genre COLLATE NOCASE ORDER BY count DESC")
    abstract fun genres(): Flow<List<GenreRow>>

    @Query("SELECT * FROM tracks WHERE $VISIBLE AND genre = :genre COLLATE NOCASE ORDER BY artist COLLATE NOCASE, albumKey, discNo, trackNo")
    abstract fun genreTracks(genre: String): Flow<List<TrackEntity>>

    @androidx.room.Update
    abstract suspend fun updateTrack(t: TrackEntity)

    // ---------- онлайн-треки ----------

    @Query("SELECT * FROM tracks WHERE source = 2")
    abstract fun onlineTracks(): Flow<List<TrackEntity>>

    /** Не затираем уже сохранённые онлайн-треки (в них могут быть свои правки). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTracksIgnore(list: List<TrackEntity>)

    /** Онлайн-треки из истории поиска, на которые ничего не ссылается, — убираем через неделю. */
    @Query(
        """DELETE FROM tracks WHERE source = 2 AND dateAdded < :before
           AND uri NOT IN (SELECT uri FROM favorites)
           AND uri NOT IN (SELECT uri FROM playlist_tracks)
           AND uri NOT IN (SELECT uri FROM plays)
           AND uri NOT IN (SELECT uri FROM listens)
           AND uri NOT IN (SELECT uri FROM dislikes)
           AND uri NOT IN (:keep)"""
    )
    abstract suspend fun pruneOnline(before: Long, keep: List<String>)

    /** «Самое любимое»: сначала самые часто и долго слушаемые. */
    @Query(
        """SELECT t.* FROM tracks t
           LEFT JOIN plays p ON p.uri = t.uri
           LEFT JOIN (SELECT uri, SUM(listenedMs) AS ms FROM listens GROUP BY uri) l ON l.uri = t.uri
           WHERE $VISIBLE_T AND $LIKED_T AND t.source != 2
           ORDER BY COALESCE(p.count, 0) DESC, COALESCE(l.ms, 0) DESC, t.title COLLATE NOCASE"""
    )
    abstract suspend fun mostLoved(): List<TrackEntity>

    /** «Обратная сторона»: от забытых к любимым. */
    @Query(
        """SELECT t.* FROM tracks t
           LEFT JOIN plays p ON p.uri = t.uri
           LEFT JOIN (SELECT uri, SUM(listenedMs) AS ms FROM listens GROUP BY uri) l ON l.uri = t.uri
           WHERE $VISIBLE_T AND $LIKED_T AND t.source != 2
           ORDER BY COALESCE(p.count, 0) ASC, COALESCE(l.ms, 0) ASC, RANDOM()"""
    )
    abstract suspend fun leastLoved(): List<TrackEntity>

    // ---------- «не нравится» ----------

    @Query("SELECT uri FROM dislikes")
    abstract fun dislikedUris(): Flow<List<String>>

    @Query("SELECT * FROM dislikes")
    abstract suspend fun allDislikes(): List<DislikeEntity>

    @Query("SELECT t.* FROM tracks t INNER JOIN dislikes d ON d.uri = t.uri WHERE $VISIBLE_T ORDER BY d.addedAt DESC")
    abstract fun dislikedTracks(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addDislike(d: DislikeEntity)

    @Query("DELETE FROM dislikes WHERE uri = :uri")
    abstract suspend fun removeDislike(uri: String)

    // ---------- умные плейлисты ----------

    @Query("SELECT * FROM smart_playlists ORDER BY updatedAt DESC")
    abstract fun smartPlaylists(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    abstract fun smartPlaylist(id: Long): Flow<SmartPlaylistEntity?>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    abstract suspend fun smartPlaylistOnce(id: Long): SmartPlaylistEntity?

    @Query("SELECT * FROM smart_playlists")
    abstract suspend fun allSmartPlaylists(): List<SmartPlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSmartPlaylist(p: SmartPlaylistEntity): Long

    @Query("DELETE FROM smart_playlists WHERE id = :id")
    abstract suspend fun deleteSmartPlaylist(id: Long)

    // ---------- обрезка ----------

    @Query("SELECT * FROM trims")
    abstract fun trims(): Flow<List<TrimEntity>>

    @Query("SELECT * FROM trims")
    abstract suspend fun allTrims(): List<TrimEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putTrim(t: TrimEntity)

    @Query("DELETE FROM trims WHERE uri = :uri")
    abstract suspend fun deleteTrim(uri: String)

    // ---------- дубликаты: перенос истории на оставшийся файл ----------

    @Query("UPDATE listens SET uri = :to WHERE uri = :from")
    abstract suspend fun moveListens(from: String, to: String)

    @Query("UPDATE OR IGNORE playlist_tracks SET uri = :to WHERE uri = :from")
    abstract suspend fun movePlaylistTracks(from: String, to: String)

    @Query("DELETE FROM playlist_tracks WHERE uri = :uri")
    abstract suspend fun dropPlaylistTracks(uri: String)

    @Query("DELETE FROM plays WHERE uri = :uri")
    abstract suspend fun dropPlayStat(uri: String)

    @Query("DELETE FROM tracks WHERE uri = :uri")
    abstract suspend fun deleteTrack(uri: String)

    /** Удалённый дубликат отдаёт оставшемуся всё: прослушивания, сердечко, место в плейлистах. */
    @Transaction
    open suspend fun mergeInto(from: String, to: String) {
        if (from == to) return
        moveListens(from, to)
        movePlaylistTracks(from, to)
        dropPlaylistTracks(from)
        val a = playStat(from)
        if (a != null) {
            val b = playStat(to)
            putPlayStat(PlayStatEntity(to, a.count + (b?.count ?: 0), maxOf(a.lastPlayed, b?.lastPlayed ?: 0L)))
            dropPlayStat(from)
        }
        if (favoriteCount(from) > 0) {
            if (favoriteCount(to) == 0) addFavorite(FavoriteEntity(to, System.currentTimeMillis()))
            removeFavorite(from)
        }
        removeDislike(from)
        deleteTrack(from)
    }

    @Query("SELECT * FROM lyrics WHERE `key` = :key")
    abstract suspend fun lyrics(key: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putLyrics(l: LyricsEntity)
}

@Database(
    entities = [
        TrackEntity::class, FavoriteEntity::class, PlayStatEntity::class, LyricsEntity::class,
        ExcludedFolder::class, ListenEntity::class,
        PlaylistEntity::class, PlaylistTrackEntity::class, TrackOverride::class,
        PositionEntity::class, LoudnessEntity::class,
        DislikeEntity::class, SmartPlaylistEntity::class, TrimEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class LumenDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

/** 2 → 3: история прослушиваний для статистики. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `listens` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`uri` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, " +
                "`trackDurationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_listens_uri` ON `listens` (`uri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_listens_startedAt` ON `listens` (`startedAt`)")
    }
}

/** 1 → 2: таблица исключённых папок. Любимое и статистика сохраняются. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `excluded` (`folder` TEXT NOT NULL, PRIMARY KEY(`folder`))")
    }
}

/** 3 → 4: плейлисты, свои теги, позиции длинных треков, громкость. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `uri` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `uri`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `track_overrides` (`uri` TEXT NOT NULL, `title` TEXT, `artist` TEXT, " +
                "`album` TEXT, `albumArtist` TEXT, `year` INTEGER, PRIMARY KEY(`uri`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `positions` (`uri` TEXT NOT NULL, `posMs` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `loudness` (`uri` TEXT NOT NULL, `gainDb` REAL NOT NULL, PRIMARY KEY(`uri`))")
    }
}

/** 4 → 5: «не нравится» и умные плейлисты. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `dislikes` (`uri` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `smart_playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `rules` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
    }
}

/** 5 → 6: обрезка начала и конца треков. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `trims` (`uri` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, PRIMARY KEY(`uri`))")
    }
}

/** 6 → 7: жанр трека. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tracks` ADD COLUMN `genre` TEXT NOT NULL DEFAULT ''")
    }
}

/** Жанр из тега: «(17)», «17» и «Rock (17)» → нормальное название. */
fun cleanGenre(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return ""
    val id3 = mapOf(
        0 to "Blues", 1 to "Classic Rock", 2 to "Country", 3 to "Dance", 4 to "Disco", 5 to "Funk", 6 to "Grunge",
        7 to "Hip-Hop", 8 to "Jazz", 9 to "Metal", 10 to "New Age", 11 to "Oldies", 13 to "Pop", 14 to "R&B", 15 to "Rap",
        16 to "Reggae", 17 to "Rock", 18 to "Techno", 19 to "Industrial", 20 to "Alternative", 21 to "Ska",
        22 to "Death Metal", 24 to "Soundtrack", 26 to "Ambient", 28 to "Vocal", 31 to "Trance", 32 to "Classical",
        33 to "Instrumental", 35 to "House", 37 to "Sound Clip", 40 to "Alternative Rock", 43 to "Punk", 52 to "Electronic",
        66 to "New Wave", 79 to "Hard Rock", 98 to "Easy Listening", 102 to "Chanson", 113 to "Tango", 127 to "Drum & Bass",
    )
    val num = Regex("^\\(?(\\d{1,3})\\)?$").find(s)?.groupValues?.get(1)?.toIntOrNull()
    if (num != null) return id3[num] ?: ""
    return s.replace(Regex("^\\(\\d{1,3}\\)\\s*"), "").replace(Regex("\\s*\\(\\d{1,3}\\)$"), "").substringBefore(';').substringBefore('/').trim()
}

/** Применяет свои правки к треку из сканера. */
fun applyOverride(t: TrackEntity, o: TrackOverride): TrackEntity {
    val albumArtist = o.albumArtist ?: t.albumArtist
    val album = o.album ?: t.album
    return t.copy(
        title = o.title ?: t.title,
        artist = o.artist ?: t.artist,
        album = album,
        albumArtist = albumArtist,
        albumKey = if (o.album != null || o.albumArtist != null) "${albumArtist.lowercase()}|${album.lowercase()}" else t.albumKey,
        year = o.year ?: t.year,
    )
}

data class TrackListenStats(val plays: Int, val listenedMs: Long, val firstAt: Long?, val lastAt: Long?)

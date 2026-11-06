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

@Entity(tableName = "excluded")
data class ExcludedFolder(@PrimaryKey val folder: String)

/** Трек виден, если его папка не в списке исключений. */
private const val VISIBLE = "folder NOT IN (SELECT folder FROM excluded)"
private const val VISIBLE_T = "t.folder NOT IN (SELECT folder FROM excluded)"

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
        list.chunked(400).forEach { insertTracks(it) }
    }

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

    @Query("SELECT * FROM lyrics WHERE `key` = :key")
    abstract suspend fun lyrics(key: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putLyrics(l: LyricsEntity)
}

@Database(
    entities = [TrackEntity::class, FavoriteEntity::class, PlayStatEntity::class, LyricsEntity::class, ExcludedFolder::class],
    version = 2,
    exportSchema = false,
)
abstract class LumenDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

/** 1 → 2: таблица исключённых папок. Любимое и статистика сохраняются. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `excluded` (`folder` TEXT NOT NULL, PRIMARY KEY(`folder`))")
    }
}

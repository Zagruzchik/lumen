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
    FROM tracks
"""

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    abstract fun tracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = :source")
    abstract suspend fun tracksOfSource(source: Int): List<TrackEntity>

    @Query("$ALBUM_SELECT GROUP BY albumKey ORDER BY album COLLATE NOCASE")
    abstract fun albums(): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT GROUP BY albumKey ORDER BY dateAdded DESC LIMIT 16")
    abstract fun recentAlbums(): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT WHERE albumArtist = :name GROUP BY albumKey ORDER BY year DESC")
    abstract fun albumsOfArtist(name: String): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT WHERE albumKey = :key GROUP BY albumKey")
    abstract fun album(key: String): Flow<AlbumRow?>

    @Query(
        """SELECT albumArtist AS name, COUNT(DISTINCT albumKey) AS albumCount,
           COUNT(*) AS trackCount, MIN(uri) AS coverUri
           FROM tracks GROUP BY albumArtist ORDER BY albumArtist COLLATE NOCASE"""
    )
    abstract fun artists(): Flow<List<ArtistRow>>

    @Query(
        """SELECT folder, folderName, COUNT(*) AS trackCount, MIN(uri) AS coverUri
           FROM tracks GROUP BY folder ORDER BY folderName COLLATE NOCASE"""
    )
    abstract fun folders(): Flow<List<FolderRow>>

    @Query("SELECT * FROM tracks WHERE albumKey = :key ORDER BY discNo, trackNo, title COLLATE NOCASE")
    abstract fun albumTracks(key: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumArtist = :name ORDER BY year DESC, albumKey, discNo, trackNo")
    abstract fun artistTracks(name: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folder = :folder ORDER BY trackNo, title COLLATE NOCASE")
    abstract fun folderTracks(folder: String): Flow<List<TrackEntity>>

    @Query(
        """SELECT t.* FROM tracks t INNER JOIN plays p ON p.uri = t.uri
           ORDER BY p.count DESC, p.lastPlayed DESC LIMIT 12"""
    )
    abstract fun frequent(): Flow<List<TrackEntity>>

    @Query(
        """SELECT t.* FROM tracks t INNER JOIN plays p ON p.uri = t.uri
           ORDER BY p.lastPlayed DESC LIMIT 1"""
    )
    abstract fun lastPlayed(): Flow<TrackEntity?>

    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON f.uri = t.uri ORDER BY f.addedAt DESC")
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
    entities = [TrackEntity::class, FavoriteEntity::class, PlayStatEntity::class, LyricsEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LumenDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

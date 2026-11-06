package app.lumen.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lumen.player.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class QueueEntry(val mediaId: String, val title: String, val artist: String)

data class PlayerState(
    val connected: Boolean = false,
    val currentId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val index: Int = 0,
    val queue: List<QueueEntry> = emptyList(),
    val sampleRate: Int = 0,
)

fun TrackEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri)
    .setUri(Uri.parse(uri))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist)
            .setTrackNumber(trackNo)
            .setArtworkUri(Uri.parse(uri))
            .build()
    )
    .build()

/** Связь интерфейса с сервисом воспроизведения через MediaController. Живёт весь процесс. */
class PlayerConnection(private val context: Context) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    var controller: MediaController? = null
        private set
    private val mutex = Mutex()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    suspend fun connect(): MediaController = mutex.withLock {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val c = MediaController.Builder(context, token).buildAsync().await()
        c.addListener(listener)
        controller = c
        refresh(true)
        c
    }

    val position: Long get() = controller?.currentPosition ?: 0L

    private fun refresh(queueChanged: Boolean) {
        val c = controller ?: return
        val old = _state.value
        val queue = if (queueChanged || old.queue.size != c.mediaItemCount) {
            List(c.mediaItemCount) { i ->
                val m = c.getMediaItemAt(i)
                QueueEntry(
                    m.mediaId,
                    m.mediaMetadata.title?.toString().orEmpty(),
                    m.mediaMetadata.artist?.toString().orEmpty(),
                )
            }
        } else old.queue
        var sampleRate = 0
        for (g in c.currentTracks.groups) {
            if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected && g.length > 0) {
                val sr = g.getTrackFormat(0).sampleRate
                if (sr != Format.NO_VALUE) sampleRate = sr
            }
        }
        val md = c.mediaMetadata
        _state.value = PlayerState(
            connected = true,
            currentId = c.currentMediaItem?.mediaId,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            index = c.currentMediaItemIndex,
            queue = queue,
            sampleRate = sampleRate,
        )
    }

    fun playTracks(list: List<TrackEntity>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        val start = if (shuffle) Random.nextInt(list.size) else startIndex.coerceIn(0, list.lastIndex)
        // Сервис сам переставит очередь: выбранный трек первым, остальные вразнобой — и играет сверху вниз.
        if (!shuffle && c.shuffleModeEnabled) c.shuffleModeEnabled = false
        c.setMediaItems(list.map { it.toMediaItem() }, start, 0L)
        if (shuffle && !c.shuffleModeEnabled) c.shuffleModeEnabled = true
        c.prepare()
        c.play()
    }

    fun playItem(item: MediaItem) {
        val c = controller ?: return
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    fun restore(list: List<TrackEntity>, index: Int, position: Long) {
        val c = controller ?: return
        if (c.mediaItemCount > 0 || list.isEmpty()) return
        c.setMediaItems(list.map { it.toMediaItem() }, index.coerceIn(0, list.lastIndex), position)
        c.prepare()
    }

    fun playNext(t: TrackEntity) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTracks(listOf(t), 0)
        c.addMediaItem(c.currentMediaItemIndex + 1, t.toMediaItem())
    }

    fun enqueue(t: TrackEntity) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTracks(listOf(t), 0)
        c.addMediaItem(t.toMediaItem())
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun play() = controller?.play()
    fun pause() = controller?.pause()
    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun nextItem() = controller?.seekToNextMediaItem()
    fun previousItem() = controller?.seekToPreviousMediaItem()

    fun seekFraction(f: Float) {
        val c = controller ?: return
        val d = c.duration
        if (d != C.TIME_UNSET && d > 0) c.seekTo((d * f).toLong())
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun skipTo(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    var volume: Float
        get() = controller?.volume ?: 1f
        set(v) {
            controller?.volume = v
        }
}

/** Таймер сна: за 30 секунд до конца плавно убавляет громкость и ставит на паузу. */
class SleepTimer(private val scope: CoroutineScope, private val player: PlayerConnection) {
    private val _endsAt = MutableStateFlow<Long?>(null)
    val endsAt: StateFlow<Long?> = _endsAt
    private var job: Job? = null

    fun set(minutes: Int?) = setMillis(minutes?.let { it * 60_000L })

    fun setMillis(ms: Long?) {
        job?.cancel()
        if (ms == null || ms <= 0) {
            _endsAt.value = null
            return
        }
        val end = System.currentTimeMillis() + ms
        _endsAt.value = end
        job = scope.launch {
            val fadeMs = 30_000L
            delay((end - System.currentTimeMillis() - fadeMs).coerceAtLeast(0))
            val startVolume = player.volume
            val steps = 30
            for (i in steps downTo 1) {
                player.volume = startVolume * i / steps
                delay(fadeMs / steps)
            }
            player.pause()
            player.volume = startVolume
            _endsAt.value = null
        }
    }
}

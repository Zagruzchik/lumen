package app.lumen.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaSessionService
import app.lumen.player.R
import app.lumen.player.data.Artwork
import app.lumen.player.data.FavoriteEntity
import app.lumen.player.graph
import app.lumen.player.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

private const val ACTION_FAVORITE = "lumen.FAVORITE"
private const val ACTION_SHUFFLE = "lumen.SHUFFLE"
private val CMD_FAVORITE = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
private val CMD_SHUFFLE = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingPlayCount: String? = null
    private var favorites: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        val renderers = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Порядок воспроизведения = порядок в очереди. Перемешивание физически переставляет треки,
        // поэтому очередь всегда показывает то, что реально будет играть сверху вниз.
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
        exo = player

        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                applyShuffle(player, shuffleModeEnabled)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                if (player.isPlaying) record(id) else pendingPlayCount = id
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) pendingPlayCount?.let { record(it) }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    updateNotificationButtons()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applicationContext.graph.eq.attach(audioSessionId)
            }
        })
        applicationContext.graph.eq.attach(player.audioSessionId)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkBitmapLoader(applicationContext))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(CMD_FAVORITE)
                        .add(CMD_SHUFFLE)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_FAVORITE -> toggleFavorite()
                        ACTION_SHUFFLE -> session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val resolved = resolve(mediaItems)
                    val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
                    // Новая очередь: если перемешивание включено — выбранный трек первым, остальные вразнобой.
                    if (mediaSession.player.shuffleModeEnabled && resolved.size > 1) {
                        originalOrder = resolved
                        val first = resolved[start]
                        val rest = resolved.filterIndexed { i, _ -> i != start }.shuffled()
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(listOf(first) + rest, 0, startPositionMs)
                        )
                    }
                    originalOrder = null
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(resolved, start, startPositionMs)
                    )
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    // mediaId у нас всегда равен URI файла: восстанавливаем адрес, если его отрезало при передаче.
                    return Futures.immediateFuture(resolve(mediaItems).toMutableList())
                }
            })
            .build()

        updateNotificationButtons()
        scope.launch {
            applicationContext.graph.dao.favoriteUris().collect {
                favorites = it.toHashSet()
                updateNotificationButtons()
            }
        }
    }

    private var exo: ExoPlayer? = null
    private var originalOrder: List<MediaItem>? = null
    private var reordering = false

    private fun resolve(items: List<MediaItem>): List<MediaItem> = items.map { item ->
        if (item.localConfiguration != null) item
        else item.buildUpon().setUri(Uri.parse(item.mediaId)).build()
    }

    private fun itemsOf(p: Player): List<MediaItem> = List(p.mediaItemCount) { p.getMediaItemAt(it) }

    /**
     * Включение: текущий трек остаётся первым, остальные перемешиваются и идут после него.
     * Выключение: возвращаем исходный порядок (новые треки, добавленные за это время, — в конце).
     * Текущий трек не прерывается: убираем и добавляем только соседей.
     */
    private fun applyShuffle(p: ExoPlayer, enabled: Boolean) {
        if (reordering) return
        val n = p.mediaItemCount
        val cur = p.currentMediaItemIndex
        if (n < 2 || cur !in 0 until n) {
            originalOrder = if (enabled) itemsOf(p) else null
            return
        }
        reordering = true
        try {
            val present = itemsOf(p)
            val target: List<MediaItem>
            val currentItem = present[cur]
            if (enabled) {
                originalOrder = present
                target = listOf(currentItem) + present.filterIndexed { i, _ -> i != cur }.shuffled()
            } else {
                val orig = originalOrder ?: return
                originalOrder = null
                val pool = HashMap<String, ArrayDeque<MediaItem>>()
                present.forEachIndexed { i, m -> if (i != cur) pool.getOrPut(m.mediaId) { ArrayDeque() }.addLast(m) }
                val ordered = ArrayList<MediaItem>(n)
                var currentPlaced = false
                for (m in orig) {
                    if (!currentPlaced && m.mediaId == currentItem.mediaId) {
                        ordered += currentItem
                        currentPlaced = true
                        continue
                    }
                    pool[m.mediaId]?.removeFirstOrNull()?.let { ordered += it }
                }
                if (!currentPlaced) ordered.add(0, currentItem)
                pool.values.forEach { q -> ordered.addAll(q) }
                target = ordered
            }
            val ci = target.indexOf(currentItem)
            if (cur < n - 1) p.removeMediaItems(cur + 1, n)
            if (cur > 0) p.removeMediaItems(0, cur)
            if (ci > 0) p.addMediaItems(0, target.subList(0, ci))
            if (ci < target.size - 1) p.addMediaItems(target.subList(ci + 1, target.size))
        } finally {
            reordering = false
        }
    }

    private fun record(id: String) {
        pendingPlayCount = null
        val dao = applicationContext.graph.dao
        scope.launch { runCatching { dao.recordPlay(id) } }
    }

    private fun toggleFavorite() {
        val id = session?.player?.currentMediaItem?.mediaId ?: return
        val dao = applicationContext.graph.dao
        scope.launch {
            runCatching {
                if (id in favorites) dao.removeFavorite(id)
                else dao.addFavorite(FavoriteEntity(id, System.currentTimeMillis()))
            }
        }
    }

    /** Кнопки в уведомлении и на экране блокировки: «в любимое» и «перемешать». */
    private fun updateNotificationButtons() {
        val s = session ?: return
        val id = s.player.currentMediaItem?.mediaId
        val fav = id != null && id in favorites
        val shuffle = s.player.shuffleModeEnabled
        val favButton = CommandButton.Builder()
            .setDisplayName(if (fav) "Убрать из любимого" else "В любимое")
            .setIconResId(if (fav) R.drawable.ic_n_heart else R.drawable.ic_n_heart_outline)
            .setSessionCommand(CMD_FAVORITE)
            .build()
        val shuffleButton = CommandButton.Builder()
            .setDisplayName(if (shuffle) "Перемешивание включено" else "Перемешать")
            .setIconResId(if (shuffle) R.drawable.ic_n_shuffle_on else R.drawable.ic_n_shuffle_off)
            .setSessionCommand(CMD_SHUFFLE)
            .build()
        s.setCustomLayout(ImmutableList.of(favButton, shuffleButton))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        applicationContext.graph.eq.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}

/** Обложки для уведомления и экрана блокировки: artworkUri у нас — это URI самого трека. */
@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("Не удалось декодировать")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> {
            Artwork.loadBlocking(context, uri.toString(), 512) ?: throw IOException("Нет обложки")
        }
}

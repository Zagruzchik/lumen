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
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import app.lumen.player.R
import app.lumen.player.data.Artwork
import app.lumen.player.data.FavoriteEntity
import app.lumen.player.data.ListenEntity
import app.lumen.player.graph
import app.lumen.player.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

internal const val ACTION_FAVORITE = "lumen.FAVORITE"
private const val ACTION_SHUFFLE = "lumen.SHUFFLE"
private val CMD_FAVORITE = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
private val CMD_SHUFFLE = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
internal const val ACTION_SLEEP_END = "lumen.SLEEP_END"
internal const val ACTION_AB = "lumen.AB"
private val CMD_AB = SessionCommand(ACTION_AB, Bundle.EMPTY)
internal const val ACTION_ALARM = "lumen.ALARM"
private val CMD_ALARM = SessionCommand(ACTION_ALARM, Bundle.EMPTY)
private val CMD_SLEEP_END = SessionCommand(ACTION_SLEEP_END, Bundle.EMPTY)
/** Целевая громкость выравнивания (средняя энергия), дБ. */
private const val TARGET_LOUDNESS_DB = -15f

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibraryService.MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingPlayCount: String? = null
    private var favorites: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        instance = this
        val renderers = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)
            // Скорость и тон — силами AudioTrack: так они работают и для 24-битного FLAC.
            .setEnableAudioTrackPlaybackParams(true)
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
        val mixer = VolumeMix(player)
        mix = mixer
        val prefs = applicationContext.graph.prefs
        val lumen = LumenPlayer(player, mixer) { prefs.fadePause }
        sessionPlayer = lumen

        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // Меняем очередь не внутри обработчика события, а следующим шагом главного потока.
                mainHandler.post {
                    if (player.shuffleModeEnabled == shuffleModeEnabled) {
                        try {
                            applyShuffle(player, shuffleModeEnabled)
                        } catch (t: Throwable) {
                            android.util.Log.e("Lumen", "shuffle reorder failed", t)
                            originalOrder = null
                        }
                    }
                    // После перестановки система могла перестроить уведомление — возвращаем свои кнопки.
                    updateNotificationButtons()
                    mainHandler.postDelayed({ updateNotificationButtons() }, 300)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                finishListen(completedByAuto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                beginListen(player)
                onTrackChanged(player, mediaItem, reason)
                val id = mediaItem?.mediaId ?: return
                if (player.isPlaying) record(id) else pendingPlayCount = id
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && alarmAnim?.isRunning == true) { alarmAnim?.cancel(); mix?.fade = 1f }
                val now = android.os.SystemClock.elapsedRealtime()
                if (!isPlaying) saveLongPosition(player)
                requestTileUpdate()
                if (isPlaying) {
                    sendNowPlaying(player)
                    pendingPlayCount?.let { record(it) }
                    if (listenUri == null) beginListen(player)
                    segmentStart = now
                    // «Запуск музыки»: старт с нуля или после паузы дольше минуты.
                    if (lastStoppedAt == 0L || now - lastStoppedAt > 60_000) {
                        val prefs = applicationContext.graph.prefs
                        prefs.playStarts = prefs.playStarts + 1
                    }
                } else {
                    if (segmentStart > 0) listenAccum += now - segmentStart
                    segmentStart = 0
                    lastStoppedAt = now
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finishListen(completedByAuto = true)
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) listenDuration = d
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    )
                ) {
                    app.lumen.player.widget.WidgetUpdater.push(this@PlaybackService, player, favorites)
                }
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                    )
                ) {
                    updateNotificationButtons()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applicationContext.graph.eq.attach(audioSessionId)
                applicationContext.graph.autoEq.attach(audioSessionId)
            }
        })
        applicationContext.graph.eq.attach(player.audioSessionId)
        applicationContext.graph.autoEq.attach(player.audioSessionId)
        runCatching { player.playbackParameters = androidx.media3.common.PlaybackParameters(prefs.speed, prefs.pitch) }
        crossfader = Crossfader(
            this, player, applicationContext.graph.prefs, mixer,
            endOf = { id -> trims[id]?.endMs ?: -1L },
            blocked = { abUri != null },
            autoEq = applicationContext.graph.autoEq,
        ).also { it.start() }
        scope.launch {
            applicationContext.graph.dao.trims().collect { list -> trims = list.associateBy { it.uri } }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId
                if (abUri != null && abUri != id) abUri = null
                // Обрезанное начало: сразу прыгаем к нужному месту.
                val t = id?.let { trims[it] }
                if (t != null && t.startMs > 0 && player.currentPosition < t.startMs - 300) player.seekTo(t.startMs)
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                // Кратковременное ускорение по удержанию не запоминаем — только настройку из шторки.
                if (!tempSpeed) {
                    prefs.speed = playbackParameters.speed
                    prefs.pitch = playbackParameters.pitch
                }
            }
        })
        mainHandler.post(guard)
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Таймер сна «до конца трека»: трек доигран — выключаем режим.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    player.pauseAtEndOfMediaItems = false
                    sleepAtEnd = false
                }
            }
        })
        mainHandler.postDelayed(positionTicker, 10_000)
        registerDevices()
        registerScreenOff()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibraryService.MediaLibrarySession.Builder(this, lumen, object : MediaLibraryService.MediaLibrarySession.Callback {
                // ---------- Android Auto: дерево медиатеки и поиск ----------
                override fun onGetLibraryRoot(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: MediaLibraryService.LibraryParams?,
                ): ListenableFuture<LibraryResult<MediaItem>> =
                    Futures.immediateFuture(LibraryResult.ofItem(AutoLibrary.root(), params))

                override fun onGetChildren(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: MediaLibraryService.LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
                    val all = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        runCatching { AutoLibrary.children(applicationContext.graph.dao, parentId) }.getOrDefault(emptyList())
                    }
                    val from = (page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
                    val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
                    LibraryResult.ofItemList(ImmutableList.copyOf(if (pageSize > 0 && pageSize < Int.MAX_VALUE) all.subList(from, to) else all), params)
                }

                override fun onGetItem(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    mediaId: String,
                ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
                    val t = kotlinx.coroutines.withContext(Dispatchers.IO) { applicationContext.graph.dao.byUri(AutoLibrary.uriOf(mediaId)) }
                    if (t != null) LibraryResult.ofItem(t.toMediaItem(), null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }

                override fun onSearch(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    params: MediaLibraryService.LibraryParams?,
                ): ListenableFuture<LibraryResult<Void>> {
                    scope.launch {
                        val n = kotlinx.coroutines.withContext(Dispatchers.IO) { AutoLibrary.search(applicationContext.graph.dao, query).size }
                        session.notifySearchResultChanged(browser, query, n, params)
                    }
                    return Futures.immediateFuture(LibraryResult.ofVoid())
                }

                override fun onGetSearchResult(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    page: Int,
                    pageSize: Int,
                    params: MediaLibraryService.LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
                    val list = kotlinx.coroutines.withContext(Dispatchers.IO) { AutoLibrary.search(applicationContext.graph.dao, query) }
                    LibraryResult.ofItemList(ImmutableList.copyOf(AutoLibrary.searchItems(query, list)), params)
                }

                // Кнопка «играть» с виджета/гарнитуры, когда приложение было закрыто:
                // поднимаем последнюю очередь (или «Самое любимое») и продолжаем.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
                    val g = applicationContext.graph
                    val uris = g.prefs.queue
                    val tracks = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        if (uris.isNotEmpty()) {
                            val found = uris.chunked(500).flatMap { g.dao.byUris(it) }.associateBy { it.uri }
                            uris.mapNotNull { found[it] }
                        } else {
                            g.dao.mostLoved()
                        }
                    }
                    if (tracks.isEmpty()) throw IllegalStateException("Нечего играть")
                    val saved = uris.getOrNull(g.prefs.queueIndex)
                    val idx = tracks.indexOfFirst { it.uri == saved }
                    MediaSession.MediaItemsWithStartPosition(
                        tracks.map { it.toMediaItem() },
                        idx.coerceAtLeast(0),
                        if (idx >= 0) g.prefs.queuePosition else 0L,
                    )
                }

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(CMD_FAVORITE)
                        .add(CMD_SHUFFLE)
                        .add(CMD_SLEEP_END)
                        .add(CMD_AB)
                        .add(CMD_ALARM)
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
                        ACTION_SLEEP_END -> {
                            sleepAtEnd = args.getBoolean("on", false)
                            exo?.pauseAtEndOfMediaItems = sleepAtEnd
                        }
                        ACTION_ALARM -> startAlarm(args.getString("source") ?: "play:loved", args.getLong("ramp", 60_000L))
                        ACTION_AB -> {
                            val a = args.getLong("a", -1L)
                            val b = args.getLong("b", -1L)
                            tempSpeed = args.getBoolean("temp", tempSpeed)
                            if (args.containsKey("a")) {
                                if (a >= 0 && b > a + 300) {
                                    abA = a
                                    abB = b
                                    abUri = exo?.currentMediaItem?.mediaId
                                    exo?.let { if (it.currentPosition !in a..b) it.seekTo(a) }
                                } else {
                                    abUri = null
                                }
                            }
                        }
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
                    // Выбор из машины: разворачиваем в целый список (альбом, плейлист, «Самое любимое»).
                    val single = mediaItems.singleOrNull()?.mediaId
                    if (single != null && (single.startsWith("ctx:") || single.startsWith("play:"))) {
                        return scope.future {
                            val ex = kotlinx.coroutines.withContext(Dispatchers.IO) { AutoLibrary.expand(applicationContext.graph.dao, single) }
                            val list = ex?.first.orEmpty()
                            if (list.isEmpty()) throw IllegalStateException("empty")
                            originalOrder = null
                            MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, ex!!.second, 0L)
                        }
                    }
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
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkBitmapLoader(applicationContext))
            .build()

        updateNotificationButtons()
        scope.launch {
            applicationContext.graph.dao.favoriteUris().collect {
                favorites = it.toHashSet()
                updateNotificationButtons()
                exo?.let { p -> app.lumen.player.widget.WidgetUpdater.push(this@PlaybackService, p, favorites) }
            }
        }
    }

    private var exo: ExoPlayer? = null
    private var mix: VolumeMix? = null
    private var sessionPlayer: Player? = null
    private var sleepAtEnd = false

    // ---------- выравнивание, позиции, умная очередь ----------

    private fun onTrackChanged(p: ExoPlayer, item: MediaItem?, reason: Int) {
        val g = applicationContext.graph
        val id = item?.mediaId ?: return
        // Выравнивание громкости: громкие треки тише до общего уровня (усиливать без искажений нельзя).
        applyLoudness(id, ramp = false)
        // Длинные треки продолжаются с места остановки.
        if (g.prefs.resumeLong) {
            scope.launch {
                val pos = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { g.dao.position(id) }.getOrNull() }
                if (pos != null && p.currentMediaItem?.mediaId == id && p.currentPosition < 5_000 && pos.posMs > 30_000) {
                    p.seekTo(pos.posMs)
                }
            }
        }
        // Очередь подходит к концу — подкладываем похожие треки заранее, чтобы не было тишины.
        if (g.prefs.smartQueue && p.repeatMode == Player.REPEAT_MODE_OFF &&
            p.currentMediaItemIndex >= p.mediaItemCount - 2 && !appending
        ) {
            appending = true
            scope.launch {
                try {
                    val ids = List(p.mediaItemCount) { p.getMediaItemAt(it).mediaId }
                    val more = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val seeds = g.dao.byUris(ids.takeLast(3))
                        app.lumen.player.data.Similar.pick(g.dao, seeds, ids.toSet(), 15)
                    }
                    if (more.isNotEmpty()) {
                        val items = more.map { it.toMediaItem() }
                        originalOrder = originalOrder?.plus(items)
                        p.addMediaItems(items)
                    }
                } finally {
                    appending = false
                }
            }
        }
        // Заранее меряем громкость следующего трека.
        if (p.hasNextMediaItem()) {
            val next = p.getMediaItemAt(p.nextMediaItemIndex).mediaId
            // Не сразу: в первые секунды нового трека (и во время кроссфейда) процессору есть чем заняться.
            if (g.beats.loudness(next) == null) g.appScope.launch {
                kotlinx.coroutines.delay(20_000)
                runCatching { g.beats.analyze(next).collect { } }
            }
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) previousLong?.let { u -> g.appScope.launch { runCatching { g.dao.clearPosition(u) } } }
        previousLong = null
    }

    private var appending = false
    private var previousLong: String? = null

    /** Поправка громкости текущего трека. Если ещё не измерена — меряем и мягко применяем. */
    fun applyLoudness(id: String, ramp: Boolean) {
        val g = applicationContext.graph
        val m = mix ?: return
        if (!g.prefs.normalize) {
            m.glideNorm(1f, if (ramp) 1500 else 0)
            return
        }
        val ld = g.beats.loudness(id)
        if (ld == null) {
            m.glideNorm(1f, 0)
            g.appScope.launch {
                runCatching { g.beats.analyze(id).collect { } }
                mainHandler.post { if (exo?.currentMediaItem?.mediaId == id && g.beats.loudness(id) != null) applyLoudness(id, ramp = true) }
            }
            return
        }
        val gainDb = (TARGET_LOUDNESS_DB - ld).coerceIn(-12f, 0f)
        m.glideNorm(Math.pow(10.0, gainDb / 20.0).toFloat(), if (ramp) 2500 else 0)
    }

    private fun saveLongPosition(p: Player) {
        val g = applicationContext.graph
        if (!g.prefs.resumeLong) return
        val id = p.currentMediaItem?.mediaId ?: return
        val d = p.duration
        if (d == C.TIME_UNSET || d < 20 * 60_000L) return
        val pos = p.currentPosition
        previousLong = id
        g.appScope.launch {
            runCatching {
                if (pos > 30_000 && pos < d - 60_000) {
                    g.dao.putPosition(app.lumen.player.data.PositionEntity(id, pos, System.currentTimeMillis()))
                } else {
                    g.dao.clearPosition(id)
                }
            }
        }
    }

    // ---------- будильник ----------

    private var alarmAnim: android.animation.ValueAnimator? = null

    /** Будильник: тихо включаем музыку и плавно поднимаем громкость. */
    fun startAlarm(source: String, rampMs: Long) {
        val p = exo ?: return
        val m = mix ?: return
        scope.launch {
            val dao = applicationContext.graph.dao
            val ex = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { AutoLibrary.expand(dao, source) }.getOrNull()
                    ?: (dao.mostLoved() to 0)
            }
            val list = ex.first.ifEmpty { kotlinx.coroutines.withContext(Dispatchers.IO) { dao.mostLoved() } }
            if (list.isEmpty()) return@launch
            // Громкость медиа: не даём будильнику быть беззвучным.
            runCatching {
                val am = getSystemService(android.media.AudioManager::class.java)
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (cur < max / 2) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max / 2, 0)
            }
            alarmAnim?.cancel()
            m.fade = 0f
            p.setMediaItems(list.map { it.toMediaItem() }, ex.second.coerceIn(0, list.lastIndex), 0L)
            p.prepare()
            p.play()
            alarmAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = rampMs.coerceIn(5_000L, 600_000L)
                interpolator = android.view.animation.LinearInterpolator()
                // Громкость на слух растёт по кривой: начинаем совсем тихо.
                addUpdateListener { m.fade = (it.animatedValue as Float).let { x -> x * x } }
                start()
            }
        }
    }

    // ---------- обрезка и повтор A–B ----------

    @Volatile private var trims: Map<String, app.lumen.player.data.TrimEntity> = emptyMap()
    private var abUri: String? = null
    private var abA = 0L
    private var abB = 0L
    /** Идёт ускорение по удержанию — не сохраняем его как настройку. */
    private var tempSpeed = false

    private val guard = object : Runnable {
        override fun run() {
            val p = exo
            var next = 250L
            if (p != null && p.isPlaying) {
                val id = p.currentMediaItem?.mediaId
                val pos = p.currentPosition
                if (abUri != null && abUri == id) {
                    next = 30L
                    if (pos >= abB) p.seekTo(abA)
                } else {
                    val t = id?.let { trims[it] }
                    if (t != null && t.endMs > 0) {
                        next = 50L
                        if (pos >= t.endMs) {
                            if (p.hasNextMediaItem()) sessionPlayer?.seekToNextMediaItem()
                            else { sessionPlayer?.pause(); p.seekTo(t.startMs) }
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, next)
        }
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            exo?.let { if (it.isPlaying) saveLongPosition(it) }
            mainHandler.postDelayed(this, 10_000)
        }
    }

    // ---------- наушники и экран ----------

    private var deviceCallback: android.media.AudioDeviceCallback? = null
    private var lastPausedAt = 0L

    /** Bluetooth-наушники подключились — продолжаем, если недавно стояли на паузе. */
    private fun registerDevices() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
                applicationContext.graph.autoEq.refreshDevice()
            }

            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                // Подключились другие наушники — включаем их профиль AutoEq.
                mainHandler.postDelayed({ applicationContext.graph.autoEq.refreshDevice() }, 400)
                val p = exo ?: return
                val bt = addedDevices.any {
                    it.isSink && (it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET))
                }
                if (!bt || !applicationContext.graph.prefs.btResume) return
                val recent = lastPausedAt > 0 && System.currentTimeMillis() - lastPausedAt < 30 * 60_000L
                if (!p.isPlaying && p.mediaItemCount > 0 && recent) {
                    mainHandler.postDelayed({ sessionPlayer?.play() }, 1200)
                }
            }
        }
        am.registerAudioDeviceCallback(cb, mainHandler)
        deviceCallback = cb
        exo?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) lastPausedAt = System.currentTimeMillis()
            }
        })
    }

    private var screenReceiver: android.content.BroadcastReceiver? = null

    /** Экран погас во время музыки — показываем свой экран блокировки с текстом песни. */
    private fun registerScreenOff() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val p = exo ?: return
                val prefs = applicationContext.graph.prefs
                if (!prefs.lockScreen || !p.isPlaying) return
                if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(context)) return
                runCatching {
                    startActivity(
                        Intent(context, app.lumen.player.ui.LockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    )
                }
            }
        }
        val f = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        screenReceiver = r
    }

    private fun requestTileUpdate() {
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, app.lumen.player.ui.LumenTileService::class.java),
            )
        }
    }

    /** Для плитки в шторке и экрана блокировки. */
    val isPlayingNow: Boolean get() = exo?.isPlaying == true
    fun togglePlay() {
        val p = sessionPlayer ?: return
        if (exo?.isPlaying == true) p.pause() else p.play()
    }
    fun sessionPause() = sessionPlayer?.pause()
    fun skipNext() = sessionPlayer?.seekToNextMediaItem()
    fun skipPrev() = sessionPlayer?.seekToPrevious()
    private var crossfader: Crossfader? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var originalOrder: List<MediaItem>? = null
    private var reordering = false

    private fun resolve(items: List<MediaItem>): List<MediaItem> = items.map { item ->
        when {
            item.localConfiguration != null -> item
            item.mediaId.startsWith("ctx:") -> {
                val uri = AutoLibrary.uriOf(item.mediaId)
                item.buildUpon().setMediaId(uri).setUri(Uri.parse(uri)).build()
            }
            else -> item.buildUpon().setUri(Uri.parse(item.mediaId)).build()
        }
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
            val ci = target.indexOfFirst { it === currentItem }.takeIf { it >= 0 } ?: target.indexOf(currentItem)
            if (ci < 0) return
            val before = ArrayList(target.subList(0, ci))
            val after = ArrayList(target.subList(ci + 1, target.size))
            val count = p.mediaItemCount
            val curNow = p.currentMediaItemIndex
            if (curNow < 0 || curNow >= count) return
            if (curNow < count - 1) p.removeMediaItems(curNow + 1, count)
            if (curNow > 0) p.removeMediaItems(0, curNow)
            if (before.isNotEmpty()) p.addMediaItems(0, before)
            if (after.isNotEmpty()) p.addMediaItems(p.mediaItemCount, after)
        } finally {
            reordering = false
        }
    }

    // ---------- учёт прослушиваний ----------
    private var listenUri: String? = null
    private var listenStartedAt = 0L
    private var listenAccum = 0L
    private var segmentStart = 0L
    private var listenDuration = 0L
    private var lastStoppedAt = 0L

    private fun beginListen(p: Player) {
        sendNowPlaying(p)
        listenUri = p.currentMediaItem?.mediaId
        listenStartedAt = System.currentTimeMillis()
        listenAccum = 0L
        listenDuration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        segmentStart = if (p.isPlaying) android.os.SystemClock.elapsedRealtime() else 0L
    }

    private fun finishListen(completedByAuto: Boolean) {
        val uri = listenUri ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val total = listenAccum + if (segmentStart > 0) now - segmentStart else 0L
        listenUri = null
        listenAccum = 0L
        segmentStart = 0L
        if (total < 3_000) return
        val dur = listenDuration
        val completed = completedByAuto || (dur > 0 && total >= dur * 0.9)
        val entity = ListenEntity(
            uri = uri,
            startedAt = listenStartedAt,
            listenedMs = total,
            trackDurationMs = dur,
            completed = completed,
        )
        val g = applicationContext.graph
        val started = listenStartedAt
        g.appScope.launch {
            runCatching { g.dao.insertListen(entity) }
            runCatching { g.dao.byUri(uri)?.let { t -> g.lastFm.maybeScrobble(t, started, total) } }
        }
    }

    private var nowPlayingSent: String? = null

    /** «Сейчас играет» в Last.fm — один раз на трек. */
    private fun sendNowPlaying(p: Player) {
        val id = p.currentMediaItem?.mediaId ?: return
        if (!p.isPlaying || id == nowPlayingSent) return
        nowPlayingSent = id
        val g = applicationContext.graph
        g.appScope.launch { runCatching { g.dao.byUri(id)?.let { g.lastFm.nowPlaying(it) } } }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = session

    /** Лайк и перемешивание с виджета — напрямую, без лишних подключений. */
    fun handleWidgetAction(action: String) {
        val p = session?.player ?: return
        when (action) {
            WIDGET_FAV -> if (p.mediaItemCount > 0) toggleFavorite()
            WIDGET_SHUFFLE -> if (p.mediaItemCount > 0) p.shuffleModeEnabled = !p.shuffleModeEnabled
        }
    }

    companion object {
        const val WIDGET_FAV = "app.lumen.player.widget.FAV"
        const val WIDGET_SHUFFLE = "app.lumen.player.widget.SHUFFLE"

        @Volatile
        var instance: PlaybackService? = null
            private set
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(positionTicker)
        mainHandler.removeCallbacks(guard)
        exo?.let { saveLongPosition(it) }
        deviceCallback?.let { cb -> runCatching { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(cb) } }
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        finishListen(completedByAuto = false)
        app.lumen.player.widget.WidgetUpdater.stopped(this)
        crossfader?.release()
        crossfader = null
        applicationContext.graph.eq.release()
        applicationContext.graph.autoEq.release()
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

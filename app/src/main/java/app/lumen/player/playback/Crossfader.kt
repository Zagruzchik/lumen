package app.lumen.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.player.data.Prefs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Кроссфейд на двух проигрывателях БЕЗ передачи звука одной песни между ними
 * (именно такая передача раньше давала заикание в момент старта перехода).
 *
 * Правило одно: песня от начала и до конца звучит из того проигрывателя, в котором началась.
 *
 *  А. Сейчас слышен основной (песня N). За ms до конца второй проигрыватель («голова») начинает
 *     песню N+1 с нуля и нарастает, основной просто доигрывает N и затихает. Когда N кончилась,
 *     основной сам переходит на N+1 — но уже беззвучно: он становится «тенью», которая идёт рядом
 *     с головой и нужна только для очереди, уведомления, позиции и текста.
 *  Б. Сейчас слышна голова (песня N+1), основной — беззвучная тень. За ms до конца основной
 *     переходит на N+2 с нуля и нарастает, голова доигрывает N+1, затихает и освобождается.
 *     Дальше снова А.
 *
 * Тень и голова могут расходиться на десятки миллисекунд — это не слышно, потому что тень молчит.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val prefs: Prefs,
    private val mix: VolumeMix,
    /** Где у трека «конец» с учётом обрезки (или -1). */
    private val endOf: (String) -> Long = { -1L },
    /** Где у трека «начало» с учётом обрезки (или 0). */
    private val startOf: (String) -> Long = { 0L },
    /** Кроссфейд не нужен (например, крутится повтор A–B). */
    private val blocked: () -> Boolean = { false },
) {
    private enum class Stage { IDLE, A_ARMED, A_FADE, GHOST, B_FADE }

    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.IDLE
    private var head: ExoPlayer? = null
    private var headId: String? = null
    private var headReady = false

    /** Песня основного, для которой подготовлен переход А. */
    private var armedFor: String? = null
    private var fadeStartPos = 0L
    private var endPos = 0L
    private var aStart = 0L
    private var bStart = 0L
    private var stageStart = 0L
    private var expectedId: String? = null
    private var switching = false
    private var lastSyncAt = 0L
    private var lastTick = 0L

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }.onFailure { android.util.Log.e("Lumen", "crossfade step", it) }
            handler.postDelayed(
                this,
                when (stage) {
                    Stage.IDLE -> 200L
                    Stage.GHOST -> 100L
                    Stage.A_ARMED -> 40L
                    else -> 16L
                },
            )
        }
    }

    fun start() {
        main.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncPlay()
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = syncPlay()

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                head?.playbackParameters = playbackParameters
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (switching) return
                val id = mediaItem?.mediaId
                when (stage) {
                    Stage.IDLE -> Unit
                    // Старая песня доиграла (или нажали «дальше») — голова уже играет эту самую следующую.
                    Stage.A_FADE ->
                        if (id != null && id == headId && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) adopt() else abort()
                    Stage.B_FADE ->
                        if (!(id == expectedId && SystemClock.elapsedRealtime() - stageStart < 1500)) abort()
                    else -> abort()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (switching || reason != Player.DISCONTINUITY_REASON_SEEK) return
                val h = head ?: return
                // Перемотка, пока слышна голова: перематываем и её.
                if (stage == Stage.GHOST && oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                    abs(newPosition.positionMs - h.currentPosition) > 500
                ) {
                    h.seekTo(newPosition.positionMs)
                }
            }
        })
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        abort()
    }

    /** Кривая громкости: мягкость 1 — равномощная (плавно, без провала), 0 — резче, ближе к концу. */
    private fun curve(x: Float): Float {
        val soft = prefs.crossfadeSoft.coerceIn(0f, 1f)
        val smooth = sin(x.coerceIn(0f, 1f) * PI.toFloat() / 2f)
        val sharp = x.coerceIn(0f, 1f).pow(2.6f)
        return sharp + (smooth - sharp) * soft
    }

    private fun wantPlay() = main.playWhenReady && main.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    /** Пауза/продолжение основного повторяются на голове, когда она уже звучит. */
    private fun syncPlay() {
        val h = head ?: return
        if (stage == Stage.A_FADE || stage == Stage.GHOST || stage == Stage.B_FADE) {
            val want = wantPlay()
            if (h.playWhenReady != want) h.playWhenReady = want
        }
    }

    /** Длина песни основного с учётом обрезки, или -1. */
    private fun mainDuration(): Long {
        val item = main.currentMediaItem ?: return -1
        val full = main.duration
        if (full == C.TIME_UNSET || full <= 0) return -1
        val cut = endOf(item.mediaId)
        return if (cut in 1 until full) cut else full
    }

    private fun canCrossfade(ms: Int): Boolean =
        ms > 0 && main.hasNextMediaItem() && main.repeatMode != Player.REPEAT_MODE_ONE &&
            !main.pauseAtEndOfMediaItems && !blocked()

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTick == 0L) 16L else (now - lastTick).coerceIn(1L, 500L)
        lastTick = now
        when (stage) {
            Stage.IDLE -> maybeArm()

            Stage.A_ARMED -> {
                val h = head ?: return abort()
                val ms = prefs.crossfadeMs
                if (main.currentMediaItem?.mediaId != armedFor || !canCrossfade(ms)) return abort()
                // Очередь изменили — следующая песня уже другая.
                if (main.getMediaItemAt(main.nextMediaItemIndex).mediaId != headId) return abort()
                val pos = main.currentPosition
                if (endPos - pos > ms + PREPARE_AHEAD_MS * 3) return abort()
                if (headReady && main.isPlaying && pos >= fadeStartPos) {
                    if (endPos - pos < 1200) return abort()
                    aStart = pos
                    mix.followerCross = 0f
                    stage = Stage.A_FADE
                    h.play()
                }
            }

            Stage.A_FADE -> {
                if (main.currentMediaItem?.mediaId != armedFor) return
                val pos = main.currentPosition
                // Отмотали назад — переход отменяется, песня снова звучит целиком.
                if (pos < aStart - 400) return abort()
                val p = ((pos - aStart).toFloat() / (endPos - aStart).coerceAtLeast(1L)).coerceIn(0f, 1f)
                mix.cross = curve(1f - p)
                mix.followerCross = curve(p)
            }

            Stage.GHOST -> {
                val h = head ?: return abort()
                if (main.currentMediaItem?.mediaId != headId) return abort()
                if (mix.cross != 0f) mix.cross = 0f
                if (mix.followerCross < 1f) mix.followerCross = (mix.followerCross + dt / 300f).coerceAtMost(1f)
                // Тень идёт рядом с головой; разошлись заметно — тихо подтягиваем тень.
                if (now - lastSyncAt > 2500 && main.isPlaying && h.isPlaying) {
                    lastSyncAt = now
                    val target = h.currentPosition
                    if (abs(target - main.currentPosition) > 350) {
                        switching = true
                        main.seekTo(target)
                        switching = false
                    }
                }
                // Переход Б: основной начинает следующую с нуля, голова доигрывает.
                val ms = prefs.crossfadeMs
                val dur = mainDuration()
                if (dur > 0 && wantPlay() && h.isPlaying && canCrossfade(ms) && dur >= ms * 2L + 5_000) {
                    val pos = h.currentPosition
                    if (pos >= dur - ms && dur - pos > 1200) {
                        bStart = pos
                        endPos = dur
                        expectedId = main.getMediaItemAt(main.nextMediaItemIndex).mediaId
                        stage = Stage.B_FADE
                        stageStart = now
                        mix.cross = 0f
                        switching = true
                        main.seekToNextMediaItem()
                        switching = false
                    }
                }
            }

            Stage.B_FADE -> {
                val h = head ?: return abort()
                val ended = h.playbackState == Player.STATE_ENDED || h.playbackState == Player.STATE_IDLE
                val p = if (ended) 1f else
                    ((h.currentPosition - bStart).toFloat() / (endPos - bStart).coerceAtLeast(1L)).coerceIn(0f, 1f)
                mix.followerCross = curve(1f - p)
                mix.cross = curve(p)
                if (p >= 1f) finish()
            }
        }
    }

    /** Переход А: готовим голову со следующей песней заранее, она ждёт на паузе у своего начала. */
    private fun maybeArm() {
        val ms = prefs.crossfadeMs
        if (!main.isPlaying || !canCrossfade(ms)) return
        val item = main.currentMediaItem ?: return
        val dur = mainDuration()
        if (dur <= 0 || dur < ms * 2L + 5_000) return
        val left = dur - main.currentPosition
        if (left > ms + PREPARE_AHEAD_MS) {
            armedFor = null
            return
        }
        if (armedFor == item.mediaId || left < ms * 0.5) return
        val next = main.getMediaItemAt(main.nextMediaItemIndex)
        armedFor = item.mediaId
        fadeStartPos = dur - ms
        endPos = dur
        val h = buildHead()
        head = h
        headId = next.mediaId
        headReady = false
        mix.followerCross = 0f
        mix.follower = h
        h.playWhenReady = false
        h.setMediaItem(next, startOf(next.mediaId).coerceAtLeast(0L))
        h.prepare()
        stage = Stage.A_ARMED
    }

    /** Второй проигрыватель — такой же, как основной: тот же вывод звука, тот же эквалайзер. */
    private fun buildHead(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setEnableAudioFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(true)
        val h = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(LumenMedia.sourceFactory(context))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Та же аудиосессия — эквалайзер и AutoEq основного действуют и на голову.
        runCatching { if (main.audioSessionId > 0) h.audioSessionId = main.audioSessionId }
        h.playbackParameters = main.playbackParameters
        h.skipSilenceEnabled = main.skipSilenceEnabled
        h.volume = 0f
        h.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (head !== h) return
                if (state == Player.STATE_READY) headReady = true
                if (state == Player.STATE_ENDED) handler.post { if (head === h) onHeadEnded() }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Голова не смогла играть — возвращаем звук основному.
                handler.post { if (head === h) { if (stage == Stage.B_FADE) finish() else abort() } }
            }
        })
        return h
    }

    private fun onHeadEnded() {
        when (stage) {
            Stage.B_FADE -> finish()
            Stage.GHOST -> {
                // Песня кончилась без кроссфейда (короткая, последняя…). Двигаем основной дальше.
                if (main.hasNextMediaItem() && main.repeatMode != Player.REPEAT_MODE_ONE &&
                    !main.pauseAtEndOfMediaItems && wantPlay()
                ) {
                    main.seekToNextMediaItem() // слушатель снимет тень и вернёт основному звук
                    if (stage != Stage.IDLE) abort()
                } else abort()
            }
            else -> abort()
        }
    }

    /** Основной перешёл на песню, которую уже играет голова: он становится беззвучной тенью. */
    private fun adopt() {
        val h = head ?: return abort()
        stage = Stage.GHOST
        armedFor = null
        mix.cross = 0f
        lastSyncAt = SystemClock.elapsedRealtime()
        switching = true
        main.seekTo(h.currentPosition)
        switching = false
        syncPlay()
    }

    /** Переход закончен: голова больше не нужна, основной звучит в полную силу. */
    private fun finish() {
        val h = head
        head = null
        headId = null
        headReady = false
        expectedId = null
        mix.follower = null
        mix.followerCross = 0f
        stage = Stage.IDLE
        mix.cross = 1f
        h?.let { p -> handler.post { runCatching { p.release() } } }
    }

    private fun abort() {
        armedFor = null
        finish()
    }

    private companion object {
        const val PREPARE_AHEAD_MS = 8_000L
    }
}

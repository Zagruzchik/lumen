package app.lumen.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.player.data.Prefs
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Кроссфейд без второй сессии: «хвост» — отдельный тихий плеер, доигрывающий конец текущей песни,
 * пока основной (тот, что в уведомлении) уже играет следующую.
 *
 * Чтобы не было заикания:
 *  1. Хвост готовится заранее (за ~4 с до начала перехода) и стоит на паузе у нужного места.
 *  2. В момент старта хвост подхватывает позицию основного с поправкой на задержку вывода звука.
 *  3. Короткие 0,3 с оба играют одно и то же, и громкость плавно перетекает с основного на хвост —
 *     даже если они разошлись на пару десятков миллисекунд, этого не слышно.
 *  4. Только после этого основной переключается на следующий трек и плавно нарастает.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val prefs: Prefs,
    private val mix: VolumeMix,
) {
    private enum class Stage { IDLE, ARMED, SYNC, HANDOFF, FADING }

    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.IDLE
    private var tail: ExoPlayer? = null
    private var tailReady = false
    private var armedFor: String? = null
    private var fadeStartPos = 0L
    private var stageStart = 0L
    private var fadeMs = 0L
    private var switching = false
    private var syncStartPos = 0L
    private var lastDrift = 0L
    private var lastSpeedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }
            handler.postDelayed(this, if (stage == Stage.IDLE) 200 else 16)
        }
    }

    fun start() {
        main.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && !switching && stage != Stage.IDLE) abort()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Переключили вручную во время перехода — сразу полная громкость новому треку.
                if (!switching && stage != Stage.IDLE) abort()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // Перемотка во время подготовки — начинаем заново.
                if (!switching && reason == Player.DISCONTINUITY_REASON_SEEK && stage != Stage.IDLE) abort()
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

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        when (stage) {
            Stage.IDLE -> maybeArm()
            Stage.ARMED -> {
                val t = tail ?: return abort()
                if (main.currentMediaItem?.mediaId != armedFor) return abort()
                // Хвост стоит на паузе ровно у позиции, которую основной пройдёт через ~2 с.
                // Запускаем его в нужный момент — без перемотки, без перебуферизации.
                // Опоздали к точке старта (поздно включили/долго готовился) — переставляем хвост вперёд.
                if (tailReady && main.currentPosition > syncStartPos + 250) {
                    syncStartPos = main.currentPosition + 700
                    tailReady = false
                    t.seekTo(syncStartPos)
                    return
                }
                if (tailReady && main.currentPosition >= syncStartPos - 30) {
                    t.volume = 0f
                    t.play()
                    stage = Stage.SYNC
                    stageStart = now
                }
            }
            Stage.SYNC -> {
                // Хвост играет беззвучно; выравниваем его по основному лёгким ускорением/замедлением,
                // пока расхождение не станет меньше ~10 мс. Тогда подмена будет неслышной.
                val t = tail ?: return abort()
                if (main.currentMediaItem?.mediaId != armedFor) return abort()
                if (now - stageStart > 200 && t.isPlaying && now - lastSpeedAt > 250) {
                    val drift = main.currentPosition - t.currentPosition
                    val speed = if (kotlin.math.abs(drift) <= 8) 1f else (1f + drift / 900f).coerceIn(0.85f, 1.25f)
                    if (kotlin.math.abs(t.playbackParameters.speed - speed) > 0.004f) {
                        t.setPlaybackSpeed(speed)
                        lastSpeedAt = now
                    }
                    lastDrift = drift
                }
                if (main.currentPosition >= fadeStartPos) {
                    t.setPlaybackSpeed(1f)
                    stage = Stage.HANDOFF
                    stageStart = now
                }
            }
            Stage.HANDOFF -> {
                val t = tail ?: return abort()
                val p = ((now - stageStart) / HANDOFF_MS.toFloat()).coerceIn(0f, 1f)
                // Равномощная передача: суммарная громкость не проседает.
                t.volume = sin(p * PI.toFloat() / 2f)
                mix.cross = sin((1f - p) * PI.toFloat() / 2f)
                if (p >= 1f) {
                    val left = (main.duration - main.currentPosition).coerceAtLeast(500L)
                    switching = true
                    mix.cross = 0f
                    main.seekToNextMediaItem()
                    switching = false
                    fadeMs = left
                    stage = Stage.FADING
                    stageStart = now
                }
            }
            Stage.FADING -> {
                val t = tail ?: return abort()
                val p = ((now - stageStart) / fadeMs.toFloat()).coerceIn(0f, 1f)
                mix.cross = curve(p)
                t.volume = curve(1f - p)
                if (p >= 1f) finish()
            }
        }
    }

    private fun maybeArm() {
        val ms = prefs.crossfadeMs
        if (ms <= 0) return
        if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return
        val dur = main.duration
        if (dur == C.TIME_UNSET || dur < ms * 2L + 5_000) return
        val item = main.currentMediaItem ?: return
        val left = dur - main.currentPosition
        if (left > ms + PREPARE_AHEAD_MS) {
            armedFor = null
            return
        }
        if (armedFor == item.mediaId || left < ms * 0.6) return
        armedFor = item.mediaId
        fadeStartPos = dur - ms
        syncStartPos = maxOf(0L, fadeStartPos - SYNC_LEAD_MS)
        // Готовим хвост заранее и ставим у места, откуда начнём синхронизацию.
        val t = ExoPlayer.Builder(context, DefaultRenderersFactory(context))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .build()
        tail = t
        tailReady = false
        t.volume = 0f
        t.playWhenReady = false
        t.setMediaItem(item, syncStartPos)
        t.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (tail !== t) return
                if (state == Player.STATE_READY) tailReady = true
                if (state == Player.STATE_ENDED) finish()
            }
        })
        t.prepare()
        stage = Stage.ARMED
    }

    private fun finish() {
        val t = tail
        tail = null
        tailReady = false
        stage = Stage.IDLE
        mix.cross = 1f
        t?.let { p -> handler.post { runCatching { p.release() } } }
    }

    private fun abort() {
        armedFor = null
        finish()
    }

    private companion object {
        const val PREPARE_AHEAD_MS = 6_000L
        const val SYNC_LEAD_MS = 2_000L
        const val HANDOFF_MS = 400L
    }
}

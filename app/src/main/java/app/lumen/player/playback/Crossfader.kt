package app.lumen.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.player.data.Prefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Кроссфейд без второй сессии: за N секунд до конца трека запускается «хвост» —
 * отдельный тихий плеер, доигрывающий конец текущей песни. Основной плеер (тот, что
 * в уведомлении) в этот момент переходит на следующий трек, и громкости плавно
 * меняются местами по равномощной кривой. Пауза или ручное переключение — хвост гаснет.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val prefs: Prefs,
    private val mix: VolumeMix,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tail: ExoPlayer? = null
    private var fadeStart = 0L
    private var fadeMs = 0
    private var armedFor: String? = null
    private var switching = false

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, if (tail != null) 40 else 200)
        }
    }

    fun start() {
        main.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && !switching && tail != null && main.playbackState == Player.STATE_READY && !main.playWhenReady) {
                    abort()
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Ручное переключение во время кроссфейда — сразу отдаём полную громкость новому треку.
                if (!switching && tail != null) abort()
            }
        })
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        abort()
    }

    private fun check() {
        val t = tail
        if (t != null) {
            if (fadeStart == 0L) return // хвост ещё готовится
            val p = ((android.os.SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
            // Равномощный переход: суммарная громкость не проседает посередине.
            mix.cross = sin(p * PI.toFloat() / 2f)
            t.volume = cos(p * PI.toFloat() / 2f)
            if (p >= 1f) abort()
            return
        }
        val ms = prefs.crossfadeMs
        if (ms <= 0) return
        if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return
        val dur = main.duration
        if (dur == C.TIME_UNSET || dur < ms * 2L + 5_000) return
        val id = main.currentMediaItem?.mediaId ?: return
        val left = dur - main.currentPosition
        if (left > ms || left < 600) {
            if (left > ms) armedFor = null
            return
        }
        if (armedFor == id) return
        armedFor = id
        begin(ms, left)
    }

    private fun begin(ms: Int, left: Long) {
        val item = main.currentMediaItem ?: return
        val pos = main.currentPosition
        val t = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableAudioFloatOutput(true))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .build()
        tail = t
        fadeStart = 0L
        fadeMs = minOf(ms.toLong(), left).toInt().coerceAtLeast(500)
        t.volume = 1f
        t.setMediaItem(item)
        t.seekTo(pos)
        t.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && fadeStart == 0L && tail === t) {
                    // Хвост готов — подхватываем ровно с того места, где сейчас основной.
                    t.seekTo(main.currentPosition + 60)
                    t.play()
                    switching = true
                    mix.cross = 0f
                    main.seekToNextMediaItem()
                    switching = false
                    fadeStart = android.os.SystemClock.elapsedRealtime()
                }
                if (state == Player.STATE_ENDED && tail === t) abort()
            }
        })
        t.prepare()
    }

    private fun abort() {
        val t = tail ?: return
        tail = null
        fadeStart = 0L
        runCatching { t.release() }
        mix.cross = 1f
    }
}

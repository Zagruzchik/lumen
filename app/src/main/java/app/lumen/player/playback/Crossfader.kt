package app.lumen.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.player.data.Prefs

/**
 * Включает кроссфейд в [CrossfadeSink]: за несколько секунд до конца песни говорит выводу звука,
 * с какого места начинать переход. Само смешивание — в выводе звука, с точностью до сэмпла.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val main: ExoPlayer,
    private val prefs: Prefs,
    @Suppress("UNUSED_PARAMETER") mix: VolumeMix,
    /** Где у трека «конец» с учётом обрезки (или -1). */
    private val endOf: (String) -> Long = { -1L },
    /** Кроссфейд не нужен (например, крутится повтор A–B). */
    private val blocked: () -> Boolean = { false },
    @Suppress("UNUSED_PARAMETER") autoEq: AutoEqEngine? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var armedFor: String? = null

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }
            handler.postDelayed(this, 250)
        }
    }

    fun start() {
        main.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                armedFor = null
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) armedFor = null
            }

            override fun onRepeatModeChanged(repeatMode: Int) = disarm()
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // Очередь поменяли — следующая песня могла исчезнуть.
                if (!main.hasNextMediaItem()) disarm()
            }
        })
        handler.post(tick)
    }

    private fun disarm() {
        armedFor = null
        CrossfadeSink.current?.disarm()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        disarm()
    }

    private fun step() {
        val sink = CrossfadeSink.current ?: return
        sink.softness = prefs.crossfadeSoft
        val ms = prefs.crossfadeMs
        if (ms <= 0 || !main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE ||
            main.pauseAtEndOfMediaItems || blocked()
        ) {
            if (armedFor != null) disarm()
            return
        }
        val item = main.currentMediaItem ?: return
        val full = main.duration
        if (full == C.TIME_UNSET || full <= 0) return
        val cut = endOf(item.mediaId)
        val dur = if (cut in 1 until full) cut else full
        if (dur < ms * 2L + 5_000) return
        val left = dur - main.currentPosition
        // Включаем заранее (вывод звука опережает то, что слышно, меньше чем на секунду).
        if (armedFor != item.mediaId && left in (ms + 1_500L)..(ms + 20_000L)) {
            armedFor = item.mediaId
            sink.arm(
                stream = sink.streamIndex,
                cutUs = (dur - ms) * 1000L,
                endUs = if (cut in 1 until full) cut * 1000L else Long.MAX_VALUE,
            )
        }
    }
}

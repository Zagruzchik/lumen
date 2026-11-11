package app.lumen.player.playback

import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Итоговая громкость = пользовательская × выравнивание × плавная пауза × кроссфейд.
 * Каждая часть меняется независимо, поэтому эффекты не мешают друг другу.
 */
class VolumeMix(private val player: ExoPlayer) {
    var user = 1f
        set(v) { field = v.coerceIn(0f, 1f); apply() }
    var norm = 1f
        set(v) { field = v.coerceIn(0f, 1f); apply() }
    var fade = 1f
        set(v) { field = v.coerceIn(0f, 1f); apply() }
    var cross = 1f
        set(v) { field = v.coerceIn(0f, 1f); apply() }

    private fun apply() {
        player.volume = (user * norm * fade * cross).coerceIn(0f, 1f)
    }

    private var normAnim: ValueAnimator? = null

    /** Плавно меняет поправку выравнивания (чтобы не было скачка громкости посреди песни). */
    fun glideNorm(target: Float, ms: Long) {
        normAnim?.cancel()
        if (ms <= 0) {
            norm = target
            return
        }
        normAnim = ValueAnimator.ofFloat(norm, target).apply {
            duration = ms
            addUpdateListener { norm = it.animatedValue as Float }
            start()
        }
    }
}

/**
 * Плеер, который видит система и уведомление: пауза плавно затухает, старт плавно нарастает,
 * а «громкость» снаружи — это пользовательская часть микшера.
 */
@OptIn(UnstableApi::class)
class LumenPlayer(
    private val exo: ExoPlayer,
    private val mix: VolumeMix,
    private val fadeEnabled: () -> Boolean,
) : ForwardingPlayer(exo) {
    private var anim: ValueAnimator? = null
    private val ease = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private fun fadeTo(target: Float, ms: Long, end: () -> Unit = {}) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(mix.fade, target).apply {
            duration = ms
            interpolator = ease
            addUpdateListener { mix.fade = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** Идёт плавное затухание перед паузой: для всех (кнопки, уведомление) мы уже на паузе. */
    private var fadingOut = false

    override fun getPlayWhenReady(): Boolean = if (fadingOut) false else super.getPlayWhenReady()
    override fun isPlaying(): Boolean = if (fadingOut) false else super.isPlaying()

    override fun play() {
        fadingOut = false
        if (fadeEnabled() && !exo.isPlaying) {
            anim?.cancel()
            mix.fade = 0f
            super.play()
            fadeTo(1f, 450)
        } else {
            anim?.cancel()
            mix.fade = 1f
            super.play()
        }
    }

    override fun pause() {
        if (fadeEnabled() && exo.isPlaying) {
            fadingOut = true
            fadeTo(0f, 260) {
                fadingOut = false
                super.pause()
                mix.fade = 1f
            }
        } else {
            anim?.cancel()
            fadingOut = false
            super.pause()
            mix.fade = 1f
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun setVolume(volume: Float) {
        mix.user = volume
    }

    override fun getVolume(): Float = mix.user
}

package app.lumen.player.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import app.lumen.player.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EqBand(val freqHz: Int, val level: Int)

data class EqState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val minLevel: Int = -1500,
    val maxLevel: Int = 1500,
    val presets: List<String> = emptyList(),
    val preset: Int = -1,
    val bassSupported: Boolean = false,
    val bass: Int = 0,
)

/**
 * Эквалайзер и усиление баса через системные эффекты Android,
 * привязанные к аудиосессии плеера. Настройки хранятся и применяются заново при смене сессии.
 */
class EqController(private val prefs: Prefs) {
    private var eq: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private val _state = MutableStateFlow(EqState(enabled = prefs.eqEnabled, bass = prefs.bassStrength))
    val state: StateFlow<EqState> = _state

    fun attach(sessionId: Int) {
        release()
        if (sessionId <= 0) return
        try {
            val e = Equalizer(0, sessionId)
            val saved = prefs.eqLevels
            val n = e.numberOfBands.toInt()
            if (prefs.eqPreset >= 0 && prefs.eqPreset < e.numberOfPresets) {
                e.usePreset(prefs.eqPreset.toShort())
            } else if (saved.size == n) {
                for (i in 0 until n) e.setBandLevel(i.toShort(), saved[i].toShort())
            }
            e.enabled = prefs.eqEnabled
            eq = e
        } catch (t: Throwable) {
            eq = null
        }
        try {
            val b = BassBoost(0, sessionId)
            if (b.strengthSupported) b.setStrength(prefs.bassStrength.coerceIn(0, 1000).toShort())
            b.enabled = prefs.eqEnabled && prefs.bassStrength > 0
            bassBoost = b
        } catch (t: Throwable) {
            bassBoost = null
        }
        publish()
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { bassBoost?.release() }
        eq = null
        bassBoost = null
    }

    private fun publish() {
        val e = eq
        if (e == null) {
            _state.value = _state.value.copy(available = false)
            return
        }
        val range = runCatching { e.bandLevelRange }.getOrNull()
        val bands = (0 until e.numberOfBands).map { i ->
            EqBand(
                freqHz = e.getCenterFreq(i.toShort()) / 1000,
                level = e.getBandLevel(i.toShort()).toInt(),
            )
        }
        val presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
        _state.value = EqState(
            available = true,
            enabled = prefs.eqEnabled,
            bands = bands,
            minLevel = range?.getOrNull(0)?.toInt() ?: -1500,
            maxLevel = range?.getOrNull(1)?.toInt() ?: 1500,
            presets = presets,
            preset = prefs.eqPreset,
            bassSupported = bassBoost?.strengthSupported == true,
            bass = prefs.bassStrength,
        )
    }

    fun setEnabled(on: Boolean) {
        prefs.eqEnabled = on
        runCatching { eq?.enabled = on }
        runCatching { bassBoost?.enabled = on && prefs.bassStrength > 0 }
        publish()
    }

    fun setBand(index: Int, level: Int) {
        if (!prefs.eqEnabled) return
        val e = eq ?: return
        val st = _state.value
        val clamped = level.coerceIn(st.minLevel, st.maxLevel)
        runCatching { e.setBandLevel(index.toShort(), clamped.toShort()) }
        prefs.eqPreset = -1
        prefs.eqLevels = (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() }
        publish()
    }

    fun usePreset(index: Int) {
        if (!prefs.eqEnabled) return
        val e = eq ?: return
        runCatching { e.usePreset(index.toShort()) }
        prefs.eqPreset = index
        prefs.eqLevels = (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() }
        publish()
    }

    fun reset() {
        val e = eq ?: return
        for (i in 0 until e.numberOfBands) runCatching { e.setBandLevel(i.toShort(), 0) }
        prefs.eqPreset = -1
        prefs.eqLevels = List(e.numberOfBands.toInt()) { 0 }
        setBass(0)
    }

    fun setBass(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        prefs.bassStrength = s
        runCatching {
            bassBoost?.setStrength(s.toShort())
            bassBoost?.enabled = prefs.eqEnabled && s > 0
        }
        publish()
    }
}

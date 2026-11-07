package app.lumen.player.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import app.lumen.player.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Один фильтр параметрического эквалайзера AutoEq: PK — колокол, LSC/HSC — полки. */
data class PeqFilter(val type: String, val fc: Double, val gainDb: Double, val q: Double)

data class PeqProfile(val name: String, val source: String, val preampDb: Double, val filters: List<PeqFilter>) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("source", source)
        .put("preamp", preampDb)
        .put("filters", JSONArray().apply {
            filters.forEach { put(JSONObject().put("t", it.type).put("f", it.fc).put("g", it.gainDb).put("q", it.q)) }
        })

    companion object {
        fun fromJson(o: JSONObject): PeqProfile {
            val arr = o.optJSONArray("filters") ?: JSONArray()
            return PeqProfile(
                o.optString("name"),
                o.optString("source"),
                o.optDouble("preamp", 0.0),
                (0 until arr.length()).map {
                    val f = arr.getJSONObject(it)
                    PeqFilter(f.optString("t", "PK"), f.optDouble("f"), f.optDouble("g"), f.optDouble("q", 0.7))
                },
            )
        }

        /** Разбор файла «… ParametricEQ.txt» из AutoEq. */
        fun parse(name: String, source: String, text: String): PeqProfile? {
            var pre = 0.0
            val out = ArrayList<PeqFilter>()
            val preRe = Regex("Preamp:\\s*(-?[\\d.]+)\\s*dB", RegexOption.IGNORE_CASE)
            val fRe = Regex("Filter\\s*\\d+:\\s*ON\\s+(PK|LSC|HSC|LS|HS)\\s+Fc\\s+([\\d.]+)\\s*Hz\\s+Gain\\s+(-?[\\d.]+)\\s*dB\\s+Q\\s+([\\d.]+)", RegexOption.IGNORE_CASE)
            text.lineSequence().forEach { line ->
                preRe.find(line)?.let { pre = it.groupValues[1].toDoubleOrNull() ?: 0.0 }
                fRe.find(line)?.let { m ->
                    val t = when (m.groupValues[1].uppercase()) { "LS", "LSC" -> "LSC"; "HS", "HSC" -> "HSC"; else -> "PK" }
                    out += PeqFilter(t, m.groupValues[2].toDouble(), m.groupValues[3].toDouble(), m.groupValues[4].toDouble())
                }
            }
            return if (out.isEmpty()) null else PeqProfile(name, source, pre, out)
        }
    }
}

/** Строка каталога AutoEq: модель наушников, путь к результатам и кто измерял. */
data class AutoEqEntry(val name: String, val path: String, val source: String)

data class AutoEqState(
    val supported: Boolean = Build.VERSION.SDK_INT >= 28,
    val enabled: Boolean = false,
    val deviceKey: String = "",
    val deviceName: String = "",
    val profile: PeqProfile? = null,
    val savedCount: Int = 0,
)

/**
 * AutoEq: поправка звука под конкретную модель наушников.
 * Профиль запоминается для каждого устройства вывода отдельно и включается сам, когда оно подключено.
 * Работает через системный DynamicsProcessing, поэтому подходит для любого формата, в том числе 24-битного FLAC.
 */
class AutoEqEngine(private val context: Context, private val prefs: Prefs) {
    private var dp: DynamicsProcessing? = null
    private var session = 0
    private val _state = MutableStateFlow(AutoEqState(enabled = prefs.autoEqEnabled))
    val state: StateFlow<AutoEqState> = _state

    private fun profiles(): MutableMap<String, PeqProfile> = runCatching {
        val o = JSONObject(prefs.autoEqProfiles.ifBlank { "{}" })
        o.keys().asSequence().associateWith { PeqProfile.fromJson(o.getJSONObject(it)) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun saveProfiles(map: Map<String, PeqProfile>) {
        prefs.autoEqProfiles = JSONObject().apply { map.forEach { (k, v) -> put(k, v.toJson()) } }.toString()
    }

    fun attach(sessionId: Int) {
        session = sessionId
        refreshDevice()
    }

    fun release() {
        runCatching { dp?.release() }
        dp = null
    }

    /** Текущее устройство вывода: наушники важнее динамика. */
    private fun currentDevice(): Pair<String, String> {
        val am = context.getSystemService(AudioManager::class.java) ?: return "speaker" to "Динамик телефона"
        val outs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        fun pri(d: AudioDeviceInfo): Int = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 6
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 5
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 4
            else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 6 else 0
        }
        val d = outs.filter { pri(it) > 0 }.maxByOrNull { pri(it) } ?: return "speaker" to "Динамик телефона"
        val product = d.productName?.toString()?.trim().orEmpty()
        val kind = when (pri(d)) { 6 -> "bt"; 5 -> "usb"; else -> "wired" }
        val label = product.ifBlank {
            when (kind) { "bt" -> "Bluetooth-наушники"; "usb" -> "USB-наушники"; else -> "Проводные наушники" }
        }
        // У проводных имя обычно пустое или общее — для них один профиль на всех.
        val key = if (kind == "wired") "wired" else "$kind:$product"
        return key to label
    }

    fun refreshDevice() {
        val (key, name) = currentDevice()
        val map = profiles()
        _state.value = _state.value.copy(deviceKey = key, deviceName = name, profile = map[key], savedCount = map.size, enabled = prefs.autoEqEnabled)
        apply()
    }

    fun setEnabled(on: Boolean) {
        prefs.autoEqEnabled = on
        _state.value = _state.value.copy(enabled = on)
        apply()
    }

    fun setProfile(p: PeqProfile?) {
        val map = profiles()
        val key = _state.value.deviceKey.ifBlank { currentDevice().first }
        if (p == null) map.remove(key) else map[key] = p
        saveProfiles(map)
        if (p != null && !prefs.autoEqEnabled) prefs.autoEqEnabled = true
        refreshDevice()
    }

    private fun apply() {
        if (Build.VERSION.SDK_INT < 28) return
        val st = _state.value
        val profile = st.profile
        if (!st.enabled || profile == null || session <= 0) {
            runCatching { dp?.enabled = false }
            return
        }
        try {
            val engine = dp ?: create(session).also { dp = it }
            configure(engine, profile)
        } catch (t: Throwable) {
            android.util.Log.w("Lumen", "AutoEq failed", t)
            runCatching { dp?.release() }
            dp = null
        }
    }

    /** Та же поправка для второго плеера (хвост кроссфейда), чтобы звук не менялся при переходе. */
    fun attachExtra(sessionId: Int): Any? {
        if (Build.VERSION.SDK_INT < 28 || sessionId <= 0) return null
        val st = _state.value
        val profile = st.profile ?: return null
        if (!st.enabled) return null
        return runCatching { create(sessionId).also { configure(it, profile) } }.getOrNull()
    }

    fun releaseExtra(handle: Any?) {
        if (Build.VERSION.SDK_INT >= 28) runCatching { (handle as? DynamicsProcessing)?.release() }
    }

    @androidx.annotation.RequiresApi(28)
    private fun create(sessionId: Int) = DynamicsProcessing(
        0,
        sessionId,
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true, BANDS.size,
            false, 0,
            false, 0,
            true,
        ).build(),
    )

    @androidx.annotation.RequiresApi(28)
    private fun configure(engine: DynamicsProcessing, profile: PeqProfile) {
        val bands = BANDS
        val eq = DynamicsProcessing.Eq(true, true, bands.size)
        bands.forEachIndexed { i, fc ->
            val upper = if (i == bands.lastIndex) 20_000f else sqrt(fc * bands[i + 1]).toFloat()
            eq.setBand(i, DynamicsProcessing.EqBand(true, upper, responseDb(profile, fc).toFloat().coerceIn(-15f, 15f)))
        }
        engine.setPreEqAllChannelsTo(eq)
        engine.setInputGainAllChannelsTo(profile.preampDb.toFloat().coerceIn(-20f, 0f))
        // Мягкий ограничитель на всякий случай: усиленные полосы не должны хрипеть.
        engine.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -0.5f, 0f))
        engine.enabled = true
    }

    companion object {
        /** Полосы по 1/3 октавы — достаточно, чтобы повторить кривую AutoEq. */
        val BANDS: List<Double> = listOf(
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
            800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
        )

        private const val FS = 48_000.0

        /** Суммарная поправка всех фильтров профиля на частоте f, в дБ. */
        fun responseDb(p: PeqProfile, f: Double): Double = p.filters.sumOf { biquadDb(it, f) }

        private fun biquadDb(flt: PeqFilter, f: Double): Double {
            if (flt.fc <= 0 || flt.fc >= FS / 2) return 0.0
            val a = 10.0.pow(flt.gainDb / 40.0)
            val w0 = 2 * PI * flt.fc / FS
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * flt.q.coerceAtLeast(0.05))
            val sa = 2 * sqrt(a) * alpha
            val (b, den) = when (flt.type) {
                "LSC" -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                ) to doubleArrayOf((a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa)
                "HSC" -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                ) to doubleArrayOf((a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa)
                else -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            val w = 2 * PI * f / FS
            fun mag(c: DoubleArray): Double {
                val re = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
                val im = -c[1] * sin(w) - c[2] * sin(2 * w)
                return sqrt(re * re + im * im)
            }
            val m = mag(b) / mag(den).coerceAtLeast(1e-12)
            return 20 * log10(m.coerceAtLeast(1e-9))
        }

        private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"

        private fun http(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Lumen")
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }

        /** Каталог моделей (несколько тысяч), кэшируется на месяц. */
        suspend fun catalog(context: Context, force: Boolean = false): List<AutoEqEntry> = withContext(Dispatchers.IO) {
            val f = File(context.filesDir, "autoeq_index.md")
            val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000L
            val text = if (fresh && !force) f.readText() else runCatching { http("$BASE/INDEX.md").also { f.writeText(it) } }
                .getOrElse { if (f.exists()) f.readText() else throw it }
            val re = Regex("^- \\[(.+?)]\\(\\./(.+?)\\) by (.+)$")
            text.lineSequence().mapNotNull { line ->
                re.find(line.trim())?.let { m -> AutoEqEntry(m.groupValues[1], m.groupValues[2], m.groupValues[3].trim()) }
            }.toList()
        }

        suspend fun download(e: AutoEqEntry): PeqProfile = withContext(Dispatchers.IO) {
            val file = e.path.substringAfterLast('/')
            val text = http("$BASE/${e.path}/$file%20ParametricEQ.txt")
            PeqProfile.parse(e.name, e.source, text) ?: error("empty profile")
        }
    }
}

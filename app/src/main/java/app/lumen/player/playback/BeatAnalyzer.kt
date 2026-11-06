package app.lumen.player.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Удары (биты) трека: время в мс и сила 0..1. */
class Beats(val times: IntArray, val strengths: FloatArray, val complete: Boolean) {
    val size get() = times.size

    /** Индекс последнего удара не позже позиции (бинарный поиск). */
    fun lastAtOrBefore(ms: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= ms) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }
}

/**
 * Находит удары в треке: декодирует аудио быстрее реального времени (в десятки раз),
 * ищет всплески энергии в басах и общей громкости. Первые секунды готовы почти сразу —
 * результаты выдаются по ходу анализа. Готовый результат кэшируется в файл,
 * и при следующем запуске вспышки начинаются мгновенно.
 */
class BeatAnalyzer(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "beats").apply { mkdirs() }
    private val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private fun fileFor(uri: String) =
        File(dir, Integer.toHexString(uri.hashCode()) + "_" + uri.length + ".beats")

    fun hasCache(uri: String) = fileFor(uri).exists()

    fun analyze(uri: String): Flow<Beats> = flow {
        val cached = runCatching { read(fileFor(uri)) }.getOrNull()
        if (cached != null) {
            emit(cached)
            return@flow
        }
        decode(uri)
    }.flowOn(dispatcher)

    private fun read(f: File): Beats? {
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return null
            val n = inp.readInt()
            val t = IntArray(n)
            val s = FloatArray(n)
            for (i in 0 until n) {
                t[i] = inp.readInt()
                s[i] = inp.readFloat()
            }
            return Beats(t, s, true)
        }
    }

    private fun write(f: File, b: Beats) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(b.size)
                for (i in 0 until b.size) {
                    out.writeInt(b.times[i])
                    out.writeFloat(b.strengths[i])
                }
            }
            tmp.renameTo(f)
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<Beats>.decode(uri: String) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(appContext, Uri.parse(uri), null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val det = Detector(sampleRate)
            var pcmFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastEmitFrame = 0

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                when {
                    oi >= 0 -> {
                        val out = c.getOutputBuffer(oi)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            if (pcmFloat) {
                                val fb = out.asFloatBuffer()
                                val total = fb.remaining()
                                var i = 0
                                while (i + channels <= total) {
                                    var sum = 0f
                                    for (ch in 0 until channels) sum += fb.get(i + ch)
                                    det.push(sum / channels)
                                    i += channels
                                }
                            } else {
                                val sb = out.asShortBuffer()
                                val total = sb.remaining()
                                var i = 0
                                while (i + channels <= total) {
                                    var sum = 0f
                                    for (ch in 0 until channels) sum += sb.get(i + ch) / 32768f
                                    det.push(sum / channels)
                                    i += channels
                                }
                            }
                        }
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        // Выдаём результат по ходу: первые секунды готовы почти сразу.
                        val firstEmit = lastEmitFrame == 0 && det.frames >= 150
                        if (firstEmit || det.frames - lastEmitFrame >= 500) {
                            lastEmitFrame = det.frames
                            emit(det.snapshot(false))
                        }
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        pcmFloat = Build.VERSION.SDK_INT >= 24 &&
                            of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        det.setSampleRate(sampleRate)
                    }
                }
            }
            val result = det.snapshot(true)
            write(fileFor(uri), result)
            emit(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Не получилось декодировать — просто без вспышек.
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    /**
     * Детектор ударов по кадрам 20 мс: энергия баса (НЧ-фильтр ~150 Гц) + общая энергия,
     * рост энергии против среднего за последнюю секунду, пик-пикинг с минимальным интервалом.
     */
    private class Detector(sr: Int) {
        private var hop = sr / 50
        private var alpha = lowpassAlpha(sr)
        private var y = 0f
        private var accLow = 0f
        private var accAll = 0f
        private var inFrame = 0
        var frames = 0
            private set

        private var prevLow = 0f
        private var prevAll = 0f
        private val hist = FloatArray(50)
        private var histN = 0
        private var histPos = 0
        private var prevFlux = 0f
        private var prevWasCandidate = false
        private var candStrength = 0f
        private var lastBeatMs = -10_000
        private var runningMax = 1e-6f

        private val times = ArrayList<Int>(1024)
        private val strengths = ArrayList<Float>(1024)

        fun setSampleRate(sr: Int) {
            hop = (sr / 50).coerceAtLeast(1)
            alpha = lowpassAlpha(sr)
        }

        private fun lowpassAlpha(sr: Int) = (1.0 - exp(-2.0 * Math.PI * 150.0 / sr)).toFloat()

        fun push(x: Float) {
            y += alpha * (x - y)
            accLow += y * y
            accAll += x * x
            inFrame++
            if (inFrame >= hop) {
                frame(sqrt(accLow / inFrame), sqrt(accAll / inFrame))
                accLow = 0f
                accAll = 0f
                inFrame = 0
            }
        }

        private fun frame(low: Float, all: Float) {
            val flux = max(0f, low - prevLow) * 1.6f + max(0f, all - prevAll) * 0.6f
            prevLow = low
            prevAll = all

            var mean = 0f
            var sq = 0f
            for (i in 0 until histN) {
                mean += hist[i]
                sq += hist[i] * hist[i]
            }
            if (histN > 0) {
                mean /= histN
                sq = sqrt(max(0f, sq / histN - mean * mean))
            }
            val threshold = mean + sq * 1.4f + 0.004f
            val ms = frames * 20

            // Пик: кадр выше порога, а следующий уже ниже — фиксируем удар на пике.
            if (prevWasCandidate && flux < prevFlux) {
                val beatMs = ms - 20
                if (beatMs - lastBeatMs >= 240) {
                    runningMax = max(runningMax * 0.995f, candStrength)
                    times += beatMs
                    strengths += candStrength
                    lastBeatMs = beatMs
                }
                prevWasCandidate = false
            }
            if (flux > threshold) {
                prevWasCandidate = true
                candStrength = flux - mean
            } else if (flux <= prevFlux) {
                prevWasCandidate = false
            }
            prevFlux = flux

            hist[histPos] = flux
            histPos = (histPos + 1) % hist.size
            if (histN < hist.size) histN++
            frames++
        }

        fun snapshot(complete: Boolean): Beats {
            val n = times.size
            val t = IntArray(n) { times[it] }
            // Нормируем силу ударов по 90-му перцентилю, чтобы вспышки были яркими и не «залипали».
            val sorted = strengths.sorted()
            val ref = if (n > 0) sorted[((n - 1) * 0.9f).toInt()].coerceAtLeast(1e-6f) else 1f
            val s = FloatArray(n) { (strengths[it] / ref).coerceIn(0.15f, 1f) }
            return Beats(t, s, complete)
        }
    }

    private companion object {
        const val MAGIC = 0x4C425431 // "LBT1"
    }
}

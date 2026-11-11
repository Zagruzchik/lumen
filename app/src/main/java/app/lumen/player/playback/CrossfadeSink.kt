package app.lumen.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Кроссфейд внутри одного проигрывателя — без второго плеера и без синхронизации.
 *
 * Плеер декодирует песни подряд (бесшовно). Когда старая песня доходит до точки начала перехода,
 * её оставшиеся секунды не отправляются в динамик, а складываются в память — декодер пробегает
 * их за доли секунды, и сразу начинается следующая песня. Её звук на выходе смешивается с
 * сохранённым хвостом: хвост затихает, новая нарастает. Один поток звука, точность до сэмпла.
 */
@OptIn(UnstableApi::class)
class CrossfadeSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    // ---------- задаётся с главного потока ----------
    @Volatile private var armedStream = -1
    @Volatile private var armedCutUs = 0L
    @Volatile private var armedEndUs = Long.MAX_VALUE
    /** Мягкость кривой (как в настройках): 1 — равномощная, 0 — резче. */
    @Volatile var softness = 1f

    /** Номер текущей песни на выходе (растёт при каждой смене песни в потоке). */
    @Volatile var streamIndex = 0
        private set

    // ---------- поток воспроизведения ----------
    private var boundary = false
    private var streamOffsetUs = 0L
    private var rate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    private var capturing = false
    private val chunks = ArrayList<FloatArray>()
    private var tailRate = 0
    private var tailChannels = 0
    private var tailSamples = 0

    private var mixing = false
    private var tail: FloatArray? = null
    private var tailFramesOut = 0 // длина хвоста в кадрах выходной частоты
    private var mixPos = 0

    private var lastIn: ByteBuffer? = null
    private var lastOut: ByteBuffer? = null

    /** Подготовить переход: на песне [stream] с позиции [cutUs] (время внутри песни) до [endUs]. */
    fun arm(stream: Int, cutUs: Long, endUs: Long) {
        armedCutUs = cutUs
        armedEndUs = endUs
        armedStream = stream
    }

    fun disarm() {
        armedStream = -1
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        rate = inputFormat.sampleRate
        channels = inputFormat.channelCount
        encoding = inputFormat.pcmEncoding
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        boundary = true
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleDiscontinuity() {
        boundary = true
        super.handleDiscontinuity()
    }

    private fun pcm() = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_24BIT ||
        encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_FLOAT

    private fun bytesPerSample() = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        else -> 4
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (boundary) {
            boundary = false
            streamIndex++
            if (capturing) startMix()
        }
        if (!pcm() || channels <= 0) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        // 1. Хвост старой песни: копим в память вместо вывода.
        if (!capturing && !mixing && armedStream == streamIndex && buffer !== lastIn) {
            val mediaUs = presentationTimeUs - streamOffsetUs
            if (mediaUs >= armedCutUs) {
                armedStream = -1
                capturing = true
                chunks.clear()
                tailRate = rate
                tailChannels = channels
                tailSamples = 0
            }
        }
        if (capturing) {
            val mediaUs = presentationTimeUs - streamOffsetUs
            // За концом обрезки — просто выбрасываем; лимит памяти — 40 с.
            if (mediaUs < armedEndUs && tailSamples < tailRate * tailChannels * 40) {
                val f = readFloats(buffer)
                chunks += f
                tailSamples += f.size
            }
            buffer.position(buffer.limit())
            return true
        }

        // 2. Начало новой песни: подмешиваем затихающий хвост.
        if (mixing) {
            if (buffer !== lastIn) {
                lastIn = buffer
                lastOut = mixed(buffer)
            }
            val out = lastOut ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            val ok = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
            if (ok) {
                buffer.position(buffer.limit())
                lastIn = null
                lastOut = null
                if (mixPos >= tailFramesOut) {
                    mixing = false
                    tail = null
                }
            }
            return ok
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /** Хвост собран, пошла следующая песня — готовим смешивание. */
    private fun startMix() {
        capturing = false
        val all = FloatArray(tailSamples)
        var o = 0
        for (c in chunks) {
            System.arraycopy(c, 0, all, o, c.size)
            o += c.size
        }
        chunks.clear()
        val frames = if (tailChannels > 0) all.size / tailChannels else 0
        if (frames <= 0) return
        tail = all
        mixPos = 0
        // Длина хвоста в кадрах новой песни (частоты могут отличаться).
        tailFramesOut = (frames.toLong() * rate / tailRate.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        mixing = true
        lastIn = null
        lastOut = null
    }

    private fun curve(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val smooth = sin(c * PI.toFloat() / 2f)
        val sharp = c.pow(2.6f)
        val s = softness.coerceIn(0f, 1f)
        return sharp + (smooth - sharp) * s
    }

    private fun tailAt(frameOut: Int, ch: Int): Float {
        val t = tail ?: return 0f
        val tc = tailChannels
        val c = if (tc == 1) 0 else ch % tc
        val pos = frameOut.toDouble() * tailRate / rate
        val i = pos.toInt()
        val frac = (pos - i).toFloat()
        val total = t.size / tc
        if (i >= total) return 0f
        val a = t[i * tc + c]
        val b = if (i + 1 < total) t[(i + 1) * tc + c] else a
        return a + (b - a) * frac
    }

    private fun mixed(input: ByteBuffer): ByteBuffer {
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bps = bytesPerSample()
        val n = src.remaining() / bps
        val out = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val ch = channels
        var p = src.position()
        for (s in 0 until n) {
            val frame = mixPos + s / ch
            val c = s % ch
            val x = read(src, p)
            val y = if (frame < tailFramesOut) {
                val k = frame.toFloat() / tailFramesOut
                x * curve(k) + tailAt(frame, c) * curve(1f - k)
            } else x
            write(out, y)
            p += bps
        }
        mixPos += n / ch
        out.flip()
        return out
    }

    private fun read(b: ByteBuffer, p: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> b.getShort(p) / 32768f
        C.ENCODING_PCM_24BIT -> {
            val v = (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)
            v / 8388608f
        }
        C.ENCODING_PCM_32BIT -> b.getInt(p) / 2147483648f
        else -> b.getFloat(p)
    }

    private fun write(b: ByteBuffer, v: Float) {
        val x = v.coerceIn(-1f, 1f)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> b.putShort((x * 32767f).toInt().toShort())
            C.ENCODING_PCM_24BIT -> {
                val i = (x * 8388607f).toInt()
                b.put((i and 0xFF).toByte()); b.put(((i shr 8) and 0xFF).toByte()); b.put(((i shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> b.putInt((x.toDouble() * 2147483647.0).toInt())
            else -> b.putFloat(v)
        }
    }

    private fun readFloats(buffer: ByteBuffer): FloatArray {
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bps = bytesPerSample()
        val n = src.remaining() / bps
        val f = FloatArray(n)
        var p = src.position()
        for (i in 0 until n) {
            f[i] = read(src, p)
            p += bps
        }
        return f
    }

    private fun clear() {
        capturing = false
        mixing = false
        chunks.clear()
        tail = null
        lastIn = null
        lastOut = null
        boundary = false
    }

    override fun flush() {
        // Перемотка/смена очереди: всё, что копили или смешивали, больше не нужно.
        clear()
        armedStream = -1
        super.flush()
    }

    override fun reset() {
        clear()
        armedStream = -1
        super.reset()
    }

    companion object {
        @Volatile
        var current: CrossfadeSink? = null
    }
}

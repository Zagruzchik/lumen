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
 * Плавный переход между песнями внутри одного потока звука.
 *
 * Ничего не вырезается и не копится в памяти: каждый сэмпл уходит на выход в своё время.
 * Последние [fadeUs] старой песни плавно затихают, первые [fadeUs] новой — плавно нарастают.
 * Поэтому часы плеера и звук не расходятся, заикания и скачков позиции быть не должно.
 */
@OptIn(UnstableApi::class)
class CrossfadeSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    // ---------- задаётся с главного потока ----------
    @Volatile private var fadeOutStream = -1
    @Volatile private var cutUs = 0L
    @Volatile private var fadeUs = 0L
    /** Мягкость кривой (как в настройках): 1 — плавнее, 0 — резче. */
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

    private var fadeOutActive = false
    private var fadeInStream = -1

    private var lastIn: ByteBuffer? = null
    private var lastOut: ByteBuffer? = null

    /**
     * Подготовить затухание: песня [stream] начнёт затухать с позиции [cutUs] (время внутри песни)
     * и затихнет через [fadeUs]. Следующая песня нарастёт за такое же время.
     */
    fun arm(stream: Int, cutUs: Long, fadeUs: Long) {
        this.cutUs = cutUs
        this.fadeUs = fadeUs.coerceAtLeast(1L)
        fadeOutStream = stream
    }

    fun disarm() {
        // Если затухание уже началось — досказываем его, иначе звук резко вернётся на полную громкость.
        if (!fadeOutActive) fadeOutStream = -1
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
            // Старая песня успела затихнуть — новая начинает нарастать.
            if (fadeOutActive) {
                fadeOutActive = false
                fadeInStream = streamIndex
            }
        }
        val fadeOutHere = fadeOutStream == streamIndex
        val fadeInHere = fadeInStream == streamIndex
        if (!pcm() || channels <= 0 || (!fadeOutHere && !fadeInHere)) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        // Один и тот же буфер может прийти повторно, если вывод был занят: тогда берём уже готовую копию.
        if (buffer !== lastIn) {
            lastIn = buffer
            lastOut = faded(buffer, presentationTimeUs - streamOffsetUs, fadeOutHere, fadeInHere)
        }
        val out = lastOut ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val ok = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (ok) {
            buffer.position(buffer.limit())
            lastIn = null
            lastOut = null
        }
        return ok
    }

    private fun curve(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val smooth = sin(c * PI.toFloat() / 2f)
        val sharp = c.pow(2.6f)
        val s = softness.coerceIn(0f, 1f)
        return sharp + (smooth - sharp) * s
    }

    /** Копия буфера с громкостью по времени внутри песни. */
    private fun faded(input: ByteBuffer, baseUs: Long, outHere: Boolean, inHere: Boolean): ByteBuffer {
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bps = bytesPerSample()
        val n = src.remaining() / bps
        val out = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val ch = channels
        val fade = fadeUs
        val cut = cutUs
        var p = src.position()
        for (s in 0 until n) {
            val frame = (s / ch).toLong()
            val t = baseUs + frame * 1_000_000L / rate
            var g = 1f
            if (outHere && t >= cut) {
                fadeOutActive = true
                g = 1f - curve((t - cut).toFloat() / fade)
            } else if (inHere && t < fade) {
                g = curve(t.toFloat() / fade)
            }
            write(out, read(src, p) * g)
            p += bps
        }
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
            else -> b.putFloat(x)
        }
    }

    private fun clear() {
        fadeOutStream = -1
        fadeOutActive = false
        fadeInStream = -1
        boundary = false
        lastIn = null
        lastOut = null
    }

    override fun flush() {
        // Перемотка/смена очереди: затухание и нарастание больше не нужны.
        clear()
        super.flush()
    }

    override fun reset() {
        clear()
        super.reset()
    }

    companion object {
        @Volatile
        var current: CrossfadeSink? = null
    }
}

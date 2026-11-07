package app.lumen.player.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.data.Artwork
import app.lumen.player.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Что цитируем: трек и выбранные строки. */
data class QuoteRequest(val track: TrackEntity, val lines: List<String>)

/** Оформление карточки: размытая обложка, градиент акцента или строгий чёрный. */
enum class QuoteStyle(val label: String) { COVER("Обложка"), ACCENT("Акцент"), DARK("Чёрный"), LIGHT("Светлый") }

/**
 * Карточка с цитатой из песни 1080×1350 (формат поста/сторис): строки крупно,
 * внизу обложка, название, исполнитель и подпись Lumen.
 */
object QuoteCard {
    private const val W = 1080
    private const val H = 1350

    suspend fun render(context: Context, req: QuoteRequest, style: QuoteStyle, accent: Int): Bitmap = withContext(Dispatchers.Default) {
        val cover = runCatching { Artwork.load(context, req.track.uri, 640) }.getOrNull()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val light = style == QuoteStyle.LIGHT
        val textColor = if (light) 0xFF15121B.toInt() else 0xFFFFFFFF.toInt()
        val dimColor = if (light) 0x9915121B.toInt() else 0xB3FFFFFF.toInt()

        // ---- фон ----
        when (style) {
            QuoteStyle.COVER -> {
                c.drawColor(0xFF0B0812.toInt())
                if (cover != null) {
                    // Дешёвое, но мягкое размытие: сильно уменьшаем и растягиваем с фильтрацией (дважды).
                    val tiny = Bitmap.createScaledBitmap(cover, 18, 18, true)
                    val mid = Bitmap.createScaledBitmap(tiny, 120, 120, true)
                    val big = Bitmap.createScaledBitmap(mid, H, H, true)
                    c.drawBitmap(big, (W - H) / 2f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(0x66000000, 0x8C000000.toInt(), 0xE6000000.toInt()), null, Shader.TileMode.CLAMP)
                })
            }
            QuoteStyle.ACCENT -> {
                c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                    shader = LinearGradient(0f, 0f, W * 0.6f, H.toFloat(), intArrayOf(accent, darken(accent, 0.35f), 0xFF0B0812.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                })
            }
            QuoteStyle.DARK -> c.drawColor(0xFF09080D.toInt())
            QuoteStyle.LIGHT -> c.drawColor(0xFFF6F3EE.toInt())
        }

        val pad = 96f
        // ---- кавычка ----
        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (style == QuoteStyle.ACCENT) 0x55FFFFFF else (accent and 0x00FFFFFF) or 0xCC000000.toInt()
            textSize = 260f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        c.drawText("“", pad - 14f, 300f, quotePaint)

        // ---- строки: подбираем размер, чтобы всё влезло ----
        val text = req.lines.joinToString("\n") { it.trim() }
        val areaTop = 330f
        val areaBottom = H - 300f
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = -0.01f
        }
        var size = 84f
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, (W - pad * 2).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .build()
            if (layout.height <= areaBottom - areaTop || size <= 38f) break
            size -= 4f
        }
        c.save()
        val top = areaTop + ((areaBottom - areaTop) - layout.height) / 2f * 0.6f
        c.translate(pad, top)
        layout.draw(c)
        c.restore()

        // ---- низ: обложка, название, исполнитель ----
        val thumb = 132f
        val ty = H - pad - thumb
        if (cover != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val sh = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            val sc = thumb / minOf(cover.width, cover.height)
            m.setScale(sc, sc)
            m.postTranslate(pad, ty)
            sh.setLocalMatrix(m)
            p.shader = sh
            c.drawRoundRect(RectF(pad, ty, pad + thumb, ty + thumb), 26f, 26f, p)
        }
        val tx = pad + (if (cover != null) thumb + 34f else 0f)
        val maxW = W - tx - pad - 150f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dimColor
            textSize = 36f
        }
        c.drawText(ellipsize(req.track.title, titlePaint, maxW), tx, ty + 58f, titlePaint)
        c.drawText(ellipsize(req.track.artist, artistPaint, maxW), tx, ty + 110f, artistPaint)
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dimColor
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("lumen", W - pad, ty + 110f, brand)
        bmp
    }

    private fun ellipsize(s: String, p: TextPaint, w: Float): String =
        android.text.TextUtils.ellipsize(s, p, w, android.text.TextUtils.TruncateAt.END).toString()

    private fun darken(color: Int, f: Float): Int {
        val r = ((color shr 16 and 0xFF) * f).toInt()
        val g = ((color shr 8 and 0xFF) * f).toInt()
        val b = ((color and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun share(context: Context, bmp: Bitmap, req: QuoteRequest) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "lumen-quote.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "${req.track.artist} — ${req.track.title}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Поделиться цитатой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Сохраняет картинку в «Изображения/Lumen». */
    fun saveToGallery(context: Context, bmp: Bitmap): Boolean = runCatching {
        val name = "lumen-quote-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        true
    }.getOrDefault(false)
}

/** Шторка с предпросмотром карточки: выбор оформления, «Поделиться» и «Сохранить». */
@androidx.compose.runtime.Composable
fun QuoteSheet(req: QuoteRequest, onClose: () -> Unit, onToast: (String) -> Unit, sheetModifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accent = app.lumen.player.ui.theme.LocalAccent.current
    var style by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(QuoteStyle.COVER) }
    val bmp by androidx.compose.runtime.produceState<Bitmap?>(null, req, style, accent) {
        value = runCatching { QuoteCard.render(ctx, req, style, accent.toArgb()) }.getOrNull()
    }
    BottomGlassSheet(onClose, sheetModifier) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(horizontal = 20.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text("Цитата", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier
                    .height(380.dp)
                    .aspectRatio(1080f / 1350f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .background(androidx.compose.ui.graphics.Color(0x14FFFFFF)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.animation.Crossfade(bmp, animationSpec = androidx.compose.animation.core.tween(260), label = "quote") { b ->
                    if (b != null) {
                        androidx.compose.foundation.Image(
                            b.asImageBitmap(),
                            contentDescription = "Карточка с цитатой",
                            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        )
                    } else {
                        androidx.compose.material3.CircularProgressIndicator(color = accent, modifier = androidx.compose.ui.Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(14.dp))
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                QuoteStyle.entries.forEach { s -> LChip(s.label, style == s) { style = s } }
            }
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(14.dp))
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                PillButton("Сохранить", null, {
                    val b = bmp ?: return@PillButton
                    onToast(if (QuoteCard.saveToGallery(ctx, b)) "Сохранено в «Изображения/Lumen»" else "Не удалось сохранить")
                }, androidx.compose.ui.Modifier.weight(1f), primary = false)
                PillButton("Поделиться", LIcons.Share, {
                    val b = bmp ?: return@PillButton
                    runCatching { QuoteCard.share(ctx, b, req) }
                }, androidx.compose.ui.Modifier.weight(1f))
            }
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
        }
    }
}

package app.lumen.player.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Загрузка обложек: встроенная картинка в файле, затем системная миниатюра. С кэшем в памяти. */
object Artwork {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val gate = Semaphore(3)

    fun peek(uri: String?, size: Int): Bitmap? = uri?.let { cache.get("$it@$size") }

    /** Меняется, когда пользователь поставил свою обложку — экраны перечитывают картинки. */
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun customFile(context: Context, uri: String): java.io.File {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(context.filesDir, "covers").apply { mkdirs() }, "$h.jpg")
    }

    /** Своя обложка для трека (из Deezer или из галереи). null — вернуть встроенную. */
    fun setCustom(context: Context, uri: String, bytes: ByteArray?) {
        val f = customFile(context, uri)
        if (bytes == null) f.delete() else f.writeBytes(bytes)
        cache.snapshot().keys.filter { it.startsWith("$uri@") }.forEach { cache.remove(it) }
        missing.remove(uri)
        revision.value++
    }

    fun hasCustom(context: Context, uri: String) = customFile(context, uri).exists()

    /** Своя обложка файлом (для передачи на другой телефон). */
    fun customBytes(context: Context, uri: String): ByteArray? = customFile(context, uri).takeIf { it.exists() }?.readBytes()

    /** Забыть закэшированную обложку (например, онлайн-трек получил обложку альбома). */
    fun forget(uri: String) {
        missing.remove(uri)
        cache.snapshot().keys.filter { it.startsWith("$uri@") }.forEach { cache.remove(it) }
    }

    suspend fun load(context: Context, uri: String, size: Int): Bitmap? {
        peek(uri, size)?.let { return it }
        if (uri in missing) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) { loadBlocking(context, uri, size) }
        }
    }

    fun loadBlocking(context: Context, uri: String, size: Int): Bitmap? {
        val key = "$uri@$size"
        cache.get(key)?.let { return it }
        if (uri in missing) return null
        val bmp = runCatching { decode(context, Uri.parse(uri), size) }.getOrNull()
        // Онлайн-обложку не помечаем «нет»: сеть могла просто моргнуть.
        if (bmp == null) { if (!Online.isOnline(uri)) missing.add(uri) } else cache.put(key, bmp)
        return bmp
    }

    /** Как найти адрес обложки онлайн-трека, если его нет в памяти (ставит AppGraph). */
    @Volatile var onlineThumbLookup: ((String) -> String?)? = null

    /** Обложка онлайн-трека: качаем картинку и обрезаем до квадрата (у YouTube превью 16:9). */
    private fun onlineCover(context: Context, uri: String, size: Int): Bitmap? {
        val url = Online.thumbs[uri] ?: onlineThumbLookup?.invoke(uri) ?: return null
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = java.io.File(java.io.File(context.cacheDir, "othumb").apply { mkdirs() }, h)
        val bytes = if (f.exists()) f.readBytes() else runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            try { c.inputStream.use { it.readBytes() }.also { f.writeBytes(it) } } finally { c.disconnect() }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(full.width, full.height)
        if (full.width == full.height) return full
        return Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
    }

    private fun decode(context: Context, uri: Uri, size: Int): Bitmap? {
        val custom = customFile(context, uri.toString())
        if (!custom.exists() && Online.isOnline(uri.toString())) return onlineCover(context, uri.toString(), size)
        val bytes = if (custom.exists()) custom.readBytes() else embedded(context, uri)
        if (bytes != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            uri.scheme == ContentResolver.SCHEME_CONTENT &&
            uri.authority == MediaStore.AUTHORITY
        ) {
            return runCatching { context.contentResolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()
        }
        return null
    }

    private fun embedded(context: Context, uri: Uri): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

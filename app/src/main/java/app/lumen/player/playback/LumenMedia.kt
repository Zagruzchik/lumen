package app.lumen.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.lumen.player.data.Online

/** Источники звука: локальные файлы и онлайн-треки через свой сервер music-api. */
object LumenMedia {
    fun sourceFactory(context: Context): MediaSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            // Сервер отвечает 302 на https-ссылку CDN — переход с http на https нужно разрешить явно.
            .setAllowCrossProtocolRedirects(true)
            // Сервер сначала разбирает трек через yt-dlp — это бывает небыстро.
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(45_000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Lumen")
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
    }

    /** Готовый к проигрыванию элемент: у онлайн-трека адрес — поток на сервере, у файла — сам файл. */
    fun playable(base: MediaItem, mediaId: String, forceProxy: Boolean = false): MediaItem {
        val b = base.buildUpon().setMediaId(mediaId)
        if (Online.isOnline(mediaId)) {
            val url = Online.streamUrl(mediaId, forceProxy)
            b.setUri(Uri.parse(url ?: mediaId))
            b.setMimeType(
                when {
                    Online.isHls(mediaId, forceProxy) -> MimeTypes.APPLICATION_M3U8
                    forceProxy || Online.viaServer -> MimeTypes.AUDIO_MPEG
                    else -> null
                }
            )
        } else {
            b.setUri(Uri.parse(mediaId))
        }
        return b.build()
    }
}

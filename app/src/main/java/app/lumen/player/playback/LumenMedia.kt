package app.lumen.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.lumen.player.data.NewPipeEngine
import app.lumen.player.data.ONLINE_SCHEME
import app.lumen.player.data.Online

/** Источники звука: локальные файлы и онлайн-треки через свой сервер music-api. */
object LumenMedia {
    fun sourceFactory(context: Context): MediaSource.Factory {
        NewPipeEngine.attach(context.applicationContext)
        // Общий клиент OkHttp: соединения с CDN переиспользуются (сегменты SoundCloud, догрузка YouTube),
        // HTTP/2 там, где сервер умеет; редиректы http→https он проходит сам.
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            app.lumen.player.data.Net.client.newBuilder()
                // Свой сервер сначала разбирает трек через yt-dlp — это бывает небыстро.
                .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
            // User-Agent по умолчанию; для YouTube движок подставляет свой (иначе CDN отвечает 403).
            .setDefaultRequestProperties(mapOf("User-Agent" to NewPipeEngine.DESKTOP_UA))
        val upstream = DefaultDataSource.Factory(context, http)
        // lumen-online://… превращается в настоящую ссылку прямо перед загрузкой — она всегда свежая.
        val resolving = ResolvingDataSource.Factory(upstream, ResolvingDataSource.Resolver { spec -> resolve(spec) })
        return DefaultMediaSourceFactory(resolving)
    }

    private fun resolve(spec: DataSpec): DataSpec {
        val u = spec.uri.toString()
        if (!u.startsWith(ONLINE_SCHEME)) return spec
        val r = NewPipeEngine.resolve(u)
        val target = if (Online.isHls(u) && !r.hls) {
            // Ждали HLS, а пришёл обычный файл — заворачиваем его в плейлист из одного сегмента.
            val m3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3600\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:3600.0,\n${r.url}\n#EXT-X-ENDLIST\n"
            "data:application/vnd.apple.mpegurl;base64," +
                android.util.Base64.encodeToString(m3u8.toByteArray(), android.util.Base64.NO_WRAP)
        } else r.url
        return spec.withUri(Uri.parse(target)).withAdditionalHeaders(r.headers)
    }

    /** Готовый к проигрыванию элемент: у онлайн-трека адрес — поток на сервере, у файла — сам файл. */
    fun playable(base: MediaItem, mediaId: String, forceProxy: Boolean = false): MediaItem {
        val b = base.buildUpon().setMediaId(mediaId)
        if (Online.isOnline(mediaId) && Online.builtin) {
            // Встроенный движок: ссылка достаётся в момент загрузки (см. resolve).
            b.setUri(Uri.parse(mediaId))
            b.setMimeType(if (Online.isHls(mediaId)) MimeTypes.APPLICATION_M3U8 else null)
        } else if (Online.isOnline(mediaId)) {
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

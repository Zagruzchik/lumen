package app.lumen.player.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import app.lumen.player.R
import app.lumen.player.playback.PlaybackService

/** Плитка «Lumen» в шторке: играть / пауза одним касанием. */
class LumenTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val svc = PlaybackService.instance
        if (svc != null) {
            svc.togglePlay()
        } else {
            // Плеер не запущен — как кнопка на наушниках: система поднимет Lumen и продолжит очередь.
            sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(ComponentName(this, androidx.media3.session.MediaButtonReceiver::class.java))
                    .setData(Uri.parse("lumen-tile://play"))
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            )
        }
        android.os.Handler(mainLooper).postDelayed({ refresh() }, 450)
    }

    private fun refresh() {
        val t = qsTile ?: return
        val playing = PlaybackService.instance?.isPlayingNow == true
        t.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Lumen"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (playing) "Играет" else "Пауза"
        t.icon = Icon.createWithResource(this, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        t.updateTile()
    }
}

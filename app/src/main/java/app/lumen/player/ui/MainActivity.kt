package app.lumen.player.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleIntent(intent)
        setContent { LumenRoot(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            // «Поделиться» ссылкой SoundCloud/YouTube в Lumen — трек сразу играет через свой сервер.
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("https?://\\S+").find(text)?.value?.let { vm.openLink(it) }
        } else if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "lumen") {
            // Ссылка из письма: подтверждение почты или смена пароля.
            intent.data?.let { vm.handleAuthLink(it) }
        } else if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { vm.playExternal(it) }
        } else if (intent.action?.startsWith("app.lumen.player.action.") == true) {
            vm.runShortcut(intent.action!!.removePrefix("app.lumen.player.action."))
        } else if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            vm.requestOpenPlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        vm.saveQueue()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

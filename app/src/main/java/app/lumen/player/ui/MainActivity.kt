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
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { vm.playExternal(it) }
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

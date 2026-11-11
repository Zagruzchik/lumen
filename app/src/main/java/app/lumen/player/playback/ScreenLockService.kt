package app.lumen.player.playback

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Служба «Lumen — гашение экрана»: нужна только для одного действия — заблокировать экран
 * двойным тапом на экране блокировки Lumen. Ничего не читает и не отслеживает.
 */
class ScreenLockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        private var instance: ScreenLockService? = null

        /** Включена ли служба в «Специальных возможностях». */
        fun enabled(context: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.startsWith(context.packageName + "/") && it.endsWith("ScreenLockService") }
        }

        /** Гасит экран (как кнопка питания). false — служба не включена. */
        fun lock(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

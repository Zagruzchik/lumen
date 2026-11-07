package app.lumen.player.playback

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.lumen.player.R
import app.lumen.player.data.Prefs
import app.lumen.player.ui.MainActivity
import com.google.common.util.concurrent.MoreExecutors
import java.util.Calendar

/** Настройки будильника. days — битовая маска пн…вс (0 — один раз). */
data class AlarmConfig(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 30,
    val days: Int = 0,
    val source: String = "play:loved",
    val sourceName: String = "Самое любимое",
    val rampSec: Int = 60,
)

/**
 * Музыкальный будильник: в нужное время Lumen сам включает выбранную музыку
 * и плавно, по кривой, поднимает громкость.
 */
object LumenAlarm {
    private const val ACTION_FIRE = "app.lumen.player.ALARM_FIRE"
    private const val ACTION_STOP = "app.lumen.player.ALARM_STOP"
    private const val ACTION_SNOOZE = "app.lumen.player.ALARM_SNOOZE"
    private const val CHANNEL = "alarm"
    private const val NOTIF_ID = 4242

    fun load(p: Prefs): AlarmConfig = runCatching {
        val o = org.json.JSONObject(p.alarm.ifBlank { "{}" })
        AlarmConfig(
            enabled = o.optBoolean("on", false),
            hour = o.optInt("h", 7),
            minute = o.optInt("m", 30),
            days = o.optInt("d", 0),
            source = o.optString("src", "play:loved"),
            sourceName = o.optString("srcName", "Самое любимое"),
            rampSec = o.optInt("ramp", 60),
        )
    }.getOrDefault(AlarmConfig())

    fun save(p: Prefs, c: AlarmConfig) {
        p.alarm = org.json.JSONObject()
            .put("on", c.enabled).put("h", c.hour).put("m", c.minute).put("d", c.days)
            .put("src", c.source).put("srcName", c.sourceName).put("ramp", c.rampSec)
            .toString()
    }

    /** Ближайшее срабатывание (мс) или null, если выключен. */
    fun nextAt(c: AlarmConfig, now: Long = System.currentTimeMillis()): Long? {
        if (!c.enabled) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, c.hour)
            set(Calendar.MINUTE, c.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (c.days == 0) return cal.timeInMillis
        repeat(8) {
            // Calendar: воскресенье = 1 … суббота = 7; наша маска: бит 0 — понедельник.
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if (c.days and (1 shl dow) != 0) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun canSchedule(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun firePi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Ставит (или снимает) системный будильник по текущим настройкам. */
    fun schedule(ctx: Context, p: Prefs, snoozeAt: Long? = null) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePi(ctx)
        val at = snoozeAt ?: nextAt(load(p))
        if (at == null) {
            am.cancel(pi)
            return
        }
        val show = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            if (canSchedule(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    internal fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        when (intent.action) {
            ACTION_FIRE -> {
                val c = load(prefs)
                // Разовый будильник выключается после срабатывания, повторяющийся — ставится на следующий день.
                if (c.days == 0) save(prefs, c.copy(enabled = false))
                schedule(app, prefs)
                send(app, Bundle().apply {
                    putString("source", c.source)
                    putLong("ramp", c.rampSec * 1000L)
                }, ACTION_ALARM)
                notify(app, c)
            }
            ACTION_STOP -> {
                cancelNotice(app)
                PlaybackService.instance?.let { it.sessionPause() }
            }
            ACTION_SNOOZE -> {
                cancelNotice(app)
                PlaybackService.instance?.let { it.sessionPause() }
                schedule(app, prefs, snoozeAt = System.currentTimeMillis() + 10 * 60_000L)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED" ->
                schedule(app, prefs)
        }
    }

    /** Команда сервису через MediaController: подключение само поднимет сервис. */
    private fun send(ctx: Context, args: Bundle, action: String) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            runCatching {
                val c = f.get()
                c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args).addListener({ c.release() }, MoreExecutors.directExecutor())
            }
        }, MoreExecutors.directExecutor())
    }

    private fun notify(ctx: Context, c: AlarmConfig) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Будильник", NotificationManager.IMPORTANCE_HIGH))
        }
        fun action(a: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, AlarmReceiver::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_w_play)
            .setContentTitle("Доброе утро ☀")
            .setContentText("Играет: ${c.sourceName}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Стоп", action(ACTION_STOP, 5))
            .addAction(0, "Ещё 10 минут", action(ACTION_SNOOZE, 6))
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    private fun cancelNotice(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = LumenAlarm.onReceive(context, intent)
}

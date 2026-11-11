package app.lumen.player

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.room.Room
import app.lumen.player.data.LibraryDao
import app.lumen.player.data.LumenDb
import app.lumen.player.data.MIGRATION_1_2
import app.lumen.player.data.MIGRATION_2_3
import app.lumen.player.data.MIGRATION_3_4
import app.lumen.player.data.MIGRATION_4_5
import app.lumen.player.data.MIGRATION_5_6
import app.lumen.player.data.MIGRATION_6_7
import app.lumen.player.data.LyricsBulkDownloader
import app.lumen.player.data.LyricsRepository
import app.lumen.player.data.MediaScanner
import app.lumen.player.data.Prefs
import app.lumen.player.playback.EqController
import app.lumen.player.playback.PlayerConnection
import app.lumen.player.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LumenApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        app.lumen.player.data.CrashGuard.install(this)
        app.lumen.player.ui.theme.ThemeStore.init(this)
        graph = AppGraph(this)
    }
}

/** Простой контейнер зависимостей, живёт столько же, сколько процесс. */
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("Lumen", "Фоновая задача упала", e) }
    )
    val db: LumenDb = Room.databaseBuilder(context, LumenDb::class.java, "lumen.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .fallbackToDestructiveMigration()
        .build()
    val dao: LibraryDao get() = db.dao()
    val prefs = Prefs(context)
    val scanner = MediaScanner(context, db.dao(), prefs)
    val lyrics = LyricsRepository(context, db.dao(), prefs)
    val lyricsBulk = LyricsBulkDownloader(appScope, db.dao(), lyrics)
    val player = PlayerConnection(context)
    val sleepTimer = SleepTimer(appScope, player)
    val eq = EqController(prefs)
    val autoEq = app.lumen.player.playback.AutoEqEngine(context, prefs)
    val lastFm = app.lumen.player.data.LastFm(context, appScope)
    val sync by lazy { app.lumen.player.data.LibrarySync(context, db.dao(), prefs) }
    val beats = app.lumen.player.playback.BeatAnalyzer(context)
    val recs = app.lumen.player.data.RecommendationsRepository(context, db.dao(), prefs)
    val updates = app.lumen.player.data.UpdateRepository(context)
    val share by lazy { app.lumen.player.data.NearShare(context, appScope, db.dao(), scanner, lyrics) }

    init {
        app.lumen.player.data.Online.configure(prefs)
        app.lumen.player.data.Artwork.onlineThumbLookup = { uri ->
            kotlinx.coroutines.runBlocking { runCatching { db.dao().byUri(uri) }.getOrNull() }?.let { app.lumen.player.data.Online.thumbOf(it) }
        }
        // Онлайн-треки, мелькнувшие только в поиске, через неделю убираем.
        appScope.launch(Dispatchers.IO) {
            runCatching { db.dao().pruneOnline(System.currentTimeMillis() - 7 * 86_400_000L, prefs.searchHistory) }
        }
    }

    private var observerRegistered = false
    private var rescanJob: Job? = null

    fun rescan() {
        appScope.launch { scanner.scan() }
    }

    /** Следим за изменениями медиатеки и пересканируем с задержкой. */
    fun watchMediaStore() {
        if (observerRegistered) return
        observerRegistered = true
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                rescanJob?.cancel()
                rescanJob = appScope.launch {
                    delay(4000)
                    scanner.scan()
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }
}

val Context.graph: AppGraph get() = (applicationContext as LumenApp).graph

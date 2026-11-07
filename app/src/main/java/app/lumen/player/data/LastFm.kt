package app.lumen.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class LastFmState(
    val hasKeys: Boolean = false,
    val user: String? = null,
    val enabled: Boolean = true,
    val pending: Int = 0,
    val error: String? = null,
)

/**
 * Скробблинг в Last.fm: «сейчас играет» при старте трека и запись прослушивания,
 * когда трек прозвучал хотя бы наполовину (или 4 минуты). Без сети — копится в очереди и уходит позже.
 * Ключ API и секрет пользователь создаёт сам на last.fm/api/account/create.
 */
class LastFm(context: Context, private val scope: CoroutineScope) {
    private val sp = context.getSharedPreferences("lumen_lastfm", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LastFmState> = _state

    private fun read() = LastFmState(
        hasKeys = !sp.getString("key", null).isNullOrBlank() && !sp.getString("secret", null).isNullOrBlank(),
        user = sp.getString("user", null)?.takeIf { !sp.getString("session", null).isNullOrBlank() },
        enabled = sp.getBoolean("enabled", true),
        pending = queue().length(),
    )

    private fun publish(err: String? = null) { _state.value = read().copy(error = err) }

    val apiKey get() = sp.getString("key", "").orEmpty()

    fun setKeys(key: String, secret: String) {
        sp.edit().putString("key", key.trim()).putString("secret", secret.trim()).apply()
        publish()
    }

    fun setEnabled(on: Boolean) {
        sp.edit().putBoolean("enabled", on).apply()
        publish()
    }

    fun logout() {
        sp.edit().remove("session").remove("user").apply()
        publish()
    }

    private fun sign(params: Map<String, String>): String {
        val raw = params.toSortedMap().entries.joinToString("") { it.key + it.value } + sp.getString("secret", "").orEmpty()
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun call(params: Map<String, String>): JSONObject {
        val all = params + ("api_key" to apiKey)
        val body = (all + ("api_sig" to sign(all)) + ("format" to "json")).entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val c = URL("https://ws.audioscrobbler.com/2.0/").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("User-Agent", "Lumen")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (o.has("error")) throw LastFmError(o.optInt("error"), o.optString("message"))
            if (c.responseCode !in 200..299) throw LastFmError(c.responseCode, "HTTP ${c.responseCode}")
            return o
        } finally {
            c.disconnect()
        }
    }

    class LastFmError(val code: Int, msg: String) : Exception(msg)

    /** Вход по логину и паролю Last.fm (пароль никуда не сохраняется — только ключ сессии). */
    suspend fun login(user: String, password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val o = call(mapOf("method" to "auth.getMobileSession", "username" to user.trim(), "password" to password))
            val s = o.getJSONObject("session")
            sp.edit().putString("session", s.getString("key")).putString("user", s.optString("name", user)).apply()
            publish()
            flush()
            true
        } catch (e: Exception) {
            publish(
                when ((e as? LastFmError)?.code) {
                    4 -> "Неверный логин или пароль"
                    10, 26 -> "Неверный ключ API — проверь ключ и секрет"
                    13 -> "Неверный секрет (Shared secret)"
                    else -> "Не получилось войти: ${e.message ?: "нет сети"}"
                }
            )
            false
        }
    }

    private fun ready() = _state.value.let { it.enabled && it.user != null && it.hasKeys }

    fun nowPlaying(t: TrackEntity) {
        if (!ready()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                call(
                    mapOf(
                        "method" to "track.updateNowPlaying",
                        "sk" to sp.getString("session", "").orEmpty(),
                        "artist" to t.artist,
                        "track" to t.title,
                        "album" to t.album,
                        "duration" to (t.durationMs / 1000).toString(),
                    )
                )
            }
        }
    }

    private fun queue(): JSONArray = runCatching { JSONArray(sp.getString("queue", "[]")) }.getOrDefault(JSONArray())

    /** Прослушивание засчитывается по правилам Last.fm: трек длиннее 30 с, прозвучал половину или 4 минуты. */
    fun maybeScrobble(t: TrackEntity, startedAtMs: Long, listenedMs: Long) {
        if (!ready()) return
        if (t.durationMs in 1..30_000) return
        if (listenedMs < minOf(t.durationMs / 2, 240_000L)) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val q = queue()
                q.put(JSONObject().put("a", t.artist).put("t", t.title).put("al", t.album).put("ts", startedAtMs / 1000).put("d", t.durationMs / 1000))
                // Не копим бесконечно, если долго без сети.
                val trimmed = if (q.length() > 2000) JSONArray((q.length() - 2000 until q.length()).map { q.get(it) }) else q
                sp.edit().putString("queue", trimmed.toString()).apply()
            }
            flush()
        }
    }

    /** Отправка накопленного пачками по 50. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext
        mutex.withLock {
            var q = queue()
            while (q.length() > 0) {
                val n = minOf(50, q.length())
                val params = HashMap<String, String>()
                params["method"] = "track.scrobble"
                params["sk"] = sp.getString("session", "").orEmpty()
                for (i in 0 until n) {
                    val o = q.getJSONObject(i)
                    params["artist[$i]"] = o.optString("a")
                    params["track[$i]"] = o.optString("t")
                    params["timestamp[$i]"] = o.optLong("ts").toString()
                    if (o.optString("al").isNotBlank()) params["album[$i]"] = o.optString("al")
                    params["duration[$i]"] = o.optLong("d").toString()
                }
                try {
                    call(params)
                } catch (e: LastFmError) {
                    if (e.code == 9) { logout(); return@withLock } // сессия больше не действует
                    if (e.code in listOf(11, 16, 29) || e.code >= 500) break // временно недоступно — попробуем позже
                    // Остальные ошибки — данные не примут никогда, выбрасываем пачку.
                } catch (e: Exception) {
                    break // нет сети
                }
                q = JSONArray((n until q.length()).map { q.get(it) })
                sp.edit().putString("queue", q.toString()).apply()
            }
        }
        publish()
    }
}

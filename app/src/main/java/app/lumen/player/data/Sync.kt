package app.lumen.player.data

import android.content.Context
import android.os.Build
import app.lumen.player.ui.normalize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class SyncState(
    val email: String? = null,
    val busy: Boolean = false,
    val lastSyncAt: Long = 0L,
    val message: String? = null,
    val error: Boolean = false,
)

/**
 * Синхронизация с Lumen на ПК через Supabase (вход по почте и паролю).
 *
 * Один JSON-документ на аккаунт (таблица lumen_sync). Треки на разных устройствах лежат по разным путям,
 * поэтому трек узнаётся по ключу «исполнитель|название|длительность в 2-секундных шагах».
 * Слияние трёхстороннее: сравниваем с тем, что было при прошлой синхронизации, — так видно,
 * что добавили и что удалили на каждом устройстве, и ничего не воскресает и не теряется.
 */
class LibrarySync(private val context: Context, private val dao: LibraryDao, private val prefs: Prefs) {
    private val base = "https://mplorochmulzbxyiihdq.supabase.co"
    private val anon = "sb_publishable_e7dBxeLArf9KSHHQnq2hKw_PEm131rz"
    private val sp = context.getSharedPreferences("lumen_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val baseFile = File(context.filesDir, "sync_base.json")

    private val _state = MutableStateFlow(SyncState(email = sp.getString("email", null), lastSyncAt = sp.getLong("last", 0L)))
    val state: StateFlow<SyncState> = _state

    // ---------- вход ----------

    private fun http(method: String, path: String, body: String?, token: String?, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL("$base$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("apikey", anon)
        c.setRequestProperty("Content-Type", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    private fun saveSession(o: JSONObject, email: String) {
        sp.edit()
            .putString("email", email)
            .putString("access", o.optString("access_token"))
            .putString("refresh", o.optString("refresh_token"))
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000)
            .putString("uid", o.optJSONObject("user")?.optString("id"))
            .apply()
    }

    private fun errorText(text: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val m = (o?.optString("msg")?.takeIf { it.isNotBlank() } ?: o?.optString("error_description")?.takeIf { it.isNotBlank() } ?: o?.optString("message")).orEmpty()
        return when {
            "Invalid login" in m -> "Неверная почта или пароль"
            "Email not confirmed" in m -> "Почта не подтверждена — открой письмо от Supabase и нажми ссылку"
            "already registered" in m -> "Такая почта уже зарегистрирована — просто войди"
            "Password should be" in m -> "Пароль слишком короткий (нужно от 6 символов)"
            m.isNotBlank() -> m
            else -> "Не получилось — проверь интернет"
        }
    }

    suspend fun signIn(email: String, password: String, create: Boolean): Boolean = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(busy = true, message = null, error = false)
        try {
            val body = JSONObject().put("email", email.trim()).put("password", password).toString()
            if (create) {
                val (code, text) = http("POST", "/auth/v1/signup", body, null)
                if (code !in 200..299) error(errorText(text))
                val o = JSONObject(text)
                if (o.optString("access_token").isBlank()) {
                    _state.value = _state.value.copy(busy = false, message = "Письмо отправлено на ${email.trim()} — подтверди почту и нажми «Войти»")
                    return@withContext false
                }
                saveSession(o, email.trim())
            } else {
                val (code, text) = http("POST", "/auth/v1/token?grant_type=password", body, null)
                if (code !in 200..299) error(errorText(text))
                saveSession(JSONObject(text), email.trim())
            }
            _state.value = _state.value.copy(email = email.trim(), busy = false, message = "Вход выполнен")
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = e.message ?: "Ошибка", error = true)
            false
        }
    }

    fun signOut() {
        sp.edit().clear().apply()
        baseFile.delete()
        _state.value = SyncState()
    }

    private fun token(): String? {
        val access = sp.getString("access", null) ?: return null
        if (System.currentTimeMillis() < sp.getLong("expires", 0)) return access
        val refresh = sp.getString("refresh", null) ?: return null
        val (code, text) = http("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh).toString(), null)
        if (code !in 200..299) return null
        saveSession(JSONObject(text), sp.getString("email", "").orEmpty())
        return sp.getString("access", null)
    }

    // ---------- документ ----------

    private fun keyOf(t: TrackEntity): String =
        "${normalize(Artists.of(t).firstOrNull() ?: t.artist).replace(" ", "")}|${normalize(t.title).replace(" ", "")}|${t.durationMs / 2000}"

    private suspend fun localDoc(byUri: Map<String, TrackEntity>): JSONObject {
        fun k(uri: String) = byUri[uri]?.let(::keyOf)
        val meta = JSONObject()
        fun note(uri: String) {
            val t = byUri[uri] ?: return
            meta.put(keyOf(t), JSONObject().put("a", t.artist).put("t", t.title).put("al", t.album).put("d", t.durationMs))
        }
        val favs = JSONObject()
        dao.allFavorites().forEach { f -> k(f.uri)?.let { favs.put(it, f.addedAt); note(f.uri) } }
        val dis = JSONObject()
        dao.allDislikes().forEach { d -> k(d.uri)?.let { dis.put(it, d.addedAt); note(d.uri) } }
        val plTracks = dao.allPlaylistTracks().groupBy { it.playlistId }
        val pls = JSONArray()
        dao.allPlaylists().forEach { p ->
            val keys = JSONArray()
            plTracks[p.id].orEmpty().sortedBy { it.position }.forEach { pt -> k(pt.uri)?.let { keys.put(it); note(pt.uri) } }
            pls.put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt).put("tracks", keys))
        }
        val smart = JSONArray()
        dao.allSmartPlaylists().forEach { s -> smart.put(JSONObject().put("name", s.name).put("rules", JSONObject(s.rules)).put("updatedAt", s.updatedAt)) }
        val plays = JSONObject()
        dao.allPlays().forEach { p -> k(p.uri)?.let { plays.put(it, JSONObject().put("c", p.count).put("l", p.lastPlayed)) } }
        return JSONObject()
            .put("v", 1)
            .put("updatedAt", System.currentTimeMillis())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("favorites", favs)
            .put("dislikes", dis)
            .put("playlists", pls)
            .put("smartPlaylists", smart)
            .put("plays", plays)
            .put("tracks", meta)
    }

    /** Трёхстороннее слияние словаря «ключ → время». */
    private fun merge3(local: JSONObject, remote: JSONObject, base: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = local.keys().asSequence().toSet() + remote.keys().asSequence().toSet()
        for (k in keys) {
            val inL = local.has(k)
            val inR = remote.has(k)
            val inB = base.has(k)
            val keep = (inL && inR) || (inL && !inB) || (inR && !inB)
            if (keep) out.put(k, maxOf(local.optLong(k, 0), remote.optLong(k, 0)))
        }
        return out
    }

    /** Списки по имени: новее — побеждает; удалённый на одной стороне (и не менявшийся на другой) удаляется. */
    private fun mergeNamed(local: JSONArray, remote: JSONArray, base: JSONArray): JSONArray {
        fun map(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.optString("name") }
        val l = map(local); val r = map(remote); val b = map(base)
        val out = JSONArray()
        for (name in l.keys + r.keys) {
            val lo = l[name]; val ro = r[name]; val bo = b[name]
            val pick = when {
                lo != null && ro != null -> if (lo.optLong("updatedAt") >= ro.optLong("updatedAt")) lo else ro
                lo != null -> if (bo != null && lo.optLong("updatedAt") <= bo.optLong("updatedAt")) null else lo
                ro != null -> if (bo != null && ro.optLong("updatedAt") <= bo.optLong("updatedAt")) null else ro
                else -> null
            }
            if (pick != null) out.put(pick)
        }
        return out
    }

    private fun mergePlays(local: JSONObject, remote: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in local.keys().asSequence().toSet() + remote.keys().asSequence().toSet()) {
            val a = local.optJSONObject(k); val b = remote.optJSONObject(k)
            out.put(k, JSONObject().put("c", maxOf(a?.optInt("c") ?: 0, b?.optInt("c") ?: 0)).put("l", maxOf(a?.optLong("l") ?: 0, b?.optLong("l") ?: 0)))
        }
        return out
    }

    /** Синхронизация целиком: скачать, слить, применить у себя, загрузить обратно. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        if (sp.getString("refresh", null) == null) return@withContext false
        mutex.withLock {
            _state.value = _state.value.copy(busy = true, message = null, error = false)
            try {
                val tok = token() ?: error("Вход устарел — войди заново")
                val uid = sp.getString("uid", null) ?: error("Нет аккаунта")
                val (code, text) = http("GET", "/rest/v1/lumen_sync?select=data&user_id=eq.$uid", null, tok)
                if (code !in 200..299) error("Сервер ответил $code")
                val arr = JSONArray(text)
                val remote = if (arr.length() > 0) arr.getJSONObject(0).optJSONObject("data") ?: JSONObject() else JSONObject()
                val baseDoc = runCatching { JSONObject(baseFile.readText()) }.getOrDefault(JSONObject())

                val all = dao.allTracksOnce()
                val byUri = all.associateBy { it.uri }
                val byKey = HashMap<String, TrackEntity>()
                all.forEach { byKey.putIfAbsent(keyOf(it), it) }
                val local = localDoc(byUri)

                fun o(d: JSONObject, n: String) = d.optJSONObject(n) ?: JSONObject()
                fun a(d: JSONObject, n: String) = d.optJSONArray(n) ?: JSONArray()
                val favs = merge3(o(local, "favorites"), o(remote, "favorites"), o(baseDoc, "favorites"))
                val dis = merge3(o(local, "dislikes"), o(remote, "dislikes"), o(baseDoc, "dislikes"))
                val pls = mergeNamed(a(local, "playlists"), a(remote, "playlists"), a(baseDoc, "playlists"))
                val smart = mergeNamed(a(local, "smartPlaylists"), a(remote, "smartPlaylists"), a(baseDoc, "smartPlaylists"))
                val plays = mergePlays(o(local, "plays"), o(remote, "plays"))
                val meta = o(remote, "tracks").also { m -> o(local, "tracks").let { l -> l.keys().forEach { m.put(it, l.get(it)) } } }

                // ---- применяем у себя ----
                val localFavKeys = o(local, "favorites").keys().asSequence().toSet()
                favs.keys().forEach { k -> if (k !in localFavKeys) byKey[k]?.let { dao.addFavorite(FavoriteEntity(it.uri, favs.optLong(k))) } }
                dao.allFavorites().forEach { f -> byUri[f.uri]?.let(::keyOf)?.let { if (!favs.has(it)) dao.removeFavorite(f.uri) } }
                val localDisKeys = o(local, "dislikes").keys().asSequence().toSet()
                dis.keys().forEach { k -> if (k !in localDisKeys) byKey[k]?.let { dao.addDislike(DislikeEntity(it.uri, dis.optLong(k))) } }
                dao.allDislikes().forEach { d -> byUri[d.uri]?.let(::keyOf)?.let { if (!dis.has(it)) dao.removeDislike(d.uri) } }

                val localPls = dao.allPlaylists().associateBy { it.name }
                val keepNames = HashSet<String>()
                for (i in 0 until pls.length()) {
                    val p = pls.getJSONObject(i)
                    val name = p.optString("name")
                    keepNames += name
                    val existing = localPls[name]
                    if (existing != null && existing.updatedAt >= p.optLong("updatedAt")) continue
                    val keys = p.optJSONArray("tracks") ?: JSONArray()
                    val uris = (0 until keys.length()).mapNotNull { byKey[keys.optString(it)]?.uri }.distinct()
                    val id = existing?.id ?: dao.insertPlaylist(PlaylistEntity(name = name, createdAt = p.optLong("createdAt"), updatedAt = p.optLong("updatedAt")))
                    dao.reorderPlaylist(id, uris)
                }
                localPls.values.filter { it.name !in keepNames }.forEach { dao.deletePlaylist(it.id) }

                val localSmart = dao.allSmartPlaylists().associateBy { it.name }
                val keepSmart = HashSet<String>()
                for (i in 0 until smart.length()) {
                    val p = smart.getJSONObject(i)
                    val name = p.optString("name")
                    keepSmart += name
                    val ex = localSmart[name]
                    if (ex != null && ex.updatedAt >= p.optLong("updatedAt")) continue
                    val rules = p.optJSONObject("rules")?.toString() ?: p.optString("rules")
                    dao.putSmartPlaylist(SmartPlaylistEntity(id = ex?.id ?: 0, name = name, rules = rules, createdAt = ex?.createdAt ?: p.optLong("updatedAt"), updatedAt = p.optLong("updatedAt")))
                }
                localSmart.values.filter { it.name !in keepSmart }.forEach { dao.deleteSmartPlaylist(it.id) }

                plays.keys().forEach { k ->
                    val t = byKey[k] ?: return@forEach
                    val r = plays.getJSONObject(k)
                    val old = dao.playStat(t.uri)
                    if (old == null || old.count < r.optInt("c") || old.lastPlayed < r.optLong("l")) {
                        dao.putPlayStat(PlayStatEntity(t.uri, maxOf(old?.count ?: 0, r.optInt("c")), maxOf(old?.lastPlayed ?: 0, r.optLong("l"))))
                    }
                }

                // ---- отправляем общий результат ----
                val merged = JSONObject()
                    .put("v", 1)
                    .put("updatedAt", System.currentTimeMillis())
                    .put("device", local.optString("device"))
                    .put("favorites", favs)
                    .put("dislikes", dis)
                    .put("playlists", pls)
                    .put("smartPlaylists", smart)
                    .put("plays", plays)
                    .put("tracks", meta)
                val row = JSONObject().put("user_id", uid).put("data", merged).put("device", local.optString("device")).put("updated_at", java.time.Instant.now().toString())
                val (c2, t2) = http("POST", "/rest/v1/lumen_sync?on_conflict=user_id", row.toString(), tok, mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
                if (c2 !in 200..299) error("Не удалось сохранить: ${errorText(t2)}")
                baseFile.writeText(merged.toString())
                val now = System.currentTimeMillis()
                sp.edit().putLong("last", now).apply()
                _state.value = _state.value.copy(busy = false, lastSyncAt = now, message = "Синхронизировано", error = false)
                true
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = e.message ?: "Ошибка синхронизации", error = true)
                false
            }
        }
    }
}

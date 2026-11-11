package app.lumen.player.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.lumen.player.ui.normalize
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SharePeer(val id: String, val name: String)

/** Что сейчас показывает окно «Поделиться» / «Получить». */
sealed interface ShareUi {
    data object Idle : ShareUi
    /** Докачиваем онлайн-треки, которых нет на телефоне. */
    data class Preparing(val done: Int, val total: Int, val current: String, val progress: Float) : ShareUi
    /** Отправитель ищет телефоны, получатель ждёт отправителя. */
    data class Searching(val sending: Boolean, val title: String, val count: Int, val peers: List<SharePeer>, val me: String) : ShareUi
    /** Получателю предлагают принять передачу. */
    data class Incoming(val peer: SharePeer, val summary: String) : ShareUi
    data class Connecting(val peer: String, val sending: Boolean) : ShareUi
    data class Transfer(
        val peer: String,
        val sending: Boolean,
        val done: Int,
        val count: Int,
        val bytes: Long,
        val total: Long,
        val skipped: Int,
    ) : ShareUi
    data class Done(val sending: Boolean, val count: Int, val skipped: Int, val playlistId: Long?, val title: String) : ShareUi
    data class Failed(val message: String) : ShareUi
}

/**
 * Передача треков и плейлистов между телефонами с Lumen, как Mi Share / Quick Share:
 * телефоны находят друг друга по Bluetooth, затем Nearby Connections сам переводит
 * связь на Wi‑Fi (точка доступа или Wi‑Fi Direct) — файлы идут на полной скорости, без интернета.
 *
 * Протокол: отправитель шлёт заголовок и файл-манифест (что за треки), получатель отвечает,
 * каких у него нет («need»), отправитель шлёт только их (с обложками и своими текстами),
 * получатель кладёт файлы в «Музыка/Lumen», собирает плейлист и отвечает «done».
 */
class NearShare(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: LibraryDao,
    private val scanner: MediaScanner,
    private val lyrics: LyricsRepository,
) {
    val state = MutableStateFlow<ShareUi>(ShareUi.Idle)

    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val service = "app.lumen.player.share"

    val me: String by lazy {
        val n = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?: runCatching { android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull()
        (n?.takeIf { it.isNotBlank() } ?: Build.MODEL).take(24)
    }

    // ---------- общее ----------
    private var sending = false
    private var started = false
    private var endpoint: String? = null
    private var peerName = ""
    private val peers = mutableListOf<SharePeer>()
    private var autoJob: Job? = null

    // ---------- отправитель ----------
    private var outgoing: List<TrackEntity> = emptyList()
    private var outTitle = ""
    private var outPlaylist = false
    private var need: CompletableDeferred<List<Int>>? = null
    private var peerDone: CompletableDeferred<Unit>? = null
    private val sendPids = HashMap<Long, Long>() // pid трека → размер
    private val sentBytes = HashMap<Long, Long>()
    private val sentDone = HashSet<Long>()
    private var sendSkipped = 0

    // ---------- получатель ----------
    private val lock = Mutex()
    private var manifestPid: Long? = null
    private var manifest: JSONObject? = null
    private val files = HashMap<Long, Payload>()
    private val completed = HashSet<Long>()
    private val failed = HashSet<Long>()
    private val gotBytes = HashMap<Long, Long>()
    private var inTracks: List<JSONObject> = emptyList()
    private var needIdx: List<Int> = emptyList()
    private var existing: Array<String?> = emptyArray()
    private var saved: Array<String?> = emptyArray()
    private var finalizing = false

    private fun reset() {
        autoJob?.cancel()
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        started = false
        endpoint = null
        peerName = ""
        peers.clear()
        need = null
        peerDone = null
        sendPids.clear(); sentBytes.clear(); sentDone.clear(); sendSkipped = 0
        manifestPid = null; manifest = null
        files.clear(); completed.clear(); failed.clear(); gotBytes.clear()
        inTracks = emptyList(); needIdx = emptyList(); existing = emptyArray(); saved = emptyArray()
        finalizing = false
    }

    /** Местные файлы, которые сейчас готовы к отправке (для запасного варианта через системное меню). */
    fun outgoingUris(): List<Uri> = outgoing.map { Uri.parse(it.uri) }

    fun startSend(tracks: List<TrackEntity>, title: String, playlist: Boolean) {
        reset()
        sending = true
        outgoing = tracks
        outTitle = title
        outPlaylist = playlist
        state.value = ShareUi.Searching(true, title, tracks.size, emptyList(), me)
    }

    fun startReceive() {
        reset()
        sending = false
        outgoing = emptyList()
        if (Build.VERSION.SDK_INT < 29) {
            state.value = ShareUi.Failed("Получение работает на Android 10 и новее")
            return
        }
        state.value = ShareUi.Searching(false, "", 0, emptyList(), me)
    }

    /** Разрешения получены — начинаем искать (отправитель) или ждать (получатель). */
    fun go() {
        if (started || state.value !is ShareUi.Searching) return
        started = true
        if (sending) {
            client.startDiscovery(
                service,
                discovery,
                DiscoveryOptions.Builder().setStrategy(strategy).build(),
            ).addOnFailureListener { fail(it) }
        } else {
            client.startAdvertising(
                me,
                service,
                lifecycle,
                AdvertisingOptions.Builder().setStrategy(strategy).build(),
            ).addOnFailureListener { fail(it) }
        }
    }

    fun cancel() {
        reset()
        cleanupCache()
        state.value = ShareUi.Idle
    }

    /** Ошибка до начала (нет разрешений, нечего отправлять, не скачалось). */
    fun fail(message: String) {
        reset()
        state.value = ShareUi.Failed(message)
    }

    private fun fail(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return
        val m = e.message.orEmpty()
        fail(
            when {
                code == ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Включи Bluetooth и Wi‑Fi и попробуй снова"
                m.contains("MISSING_PERMISSION", true) && m.contains("LOCATION", true) ->
                    "Нужна точная геолокация: «О приложении» Lumen → «Разрешения» → «Геолокация» → «Разрешить» (и «Точное местоположение»)"
                m.contains("MISSING_PERMISSION", true) || m.contains("permission", true) ->
                    "Нет разрешения «Устройства поблизости» — «О приложении» Lumen → «Разрешения»"
                m.contains("location", true) -> "Включи геолокацию в шторке и попробуй снова"
                m.contains("API_UNAVAILABLE", true) || code == 17 -> "На телефоне нет сервисов Google Play — используй «Другим способом»"
                code != null -> "Не получилось: " + ConnectionsStatusCodes.getStatusCodeString(code)
                else -> "Не получилось: ${m.ifBlank { "ошибка" }}"
            }
        )
    }

    // ---------- поиск ----------

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != service) return
            peers.removeAll { it.id == id }
            peers += SharePeer(id, info.endpointName)
            showPeers()
        }

        override fun onEndpointLost(id: String) {
            peers.removeAll { it.id == id }
            showPeers()
        }
    }

    private fun showPeers() {
        val s = state.value as? ShareUi.Searching ?: return
        state.value = s.copy(peers = peers.toList())
        // Один телефон рядом — соединяемся сами, как Mi Share. Несколько — пусть выберет.
        autoJob?.cancel()
        if (peers.size == 1) {
            val only = peers[0]
            autoJob = scope.launch {
                delay(1500)
                if (state.value is ShareUi.Searching && peers.size == 1 && peers[0] == only) connect(only)
            }
        }
    }

    fun connect(peer: SharePeer) {
        autoJob?.cancel()
        runCatching { client.stopDiscovery() }
        peerName = peer.name
        state.value = ShareUi.Connecting(peer.name, true)
        client.requestConnection(requestName(), peer.id, lifecycle).addOnFailureListener { fail(it) }
    }

    /** Имя отправителя + что отправляем: получатель видит это до того, как согласится. */
    private fun requestName(): String {
        val what = if (outPlaylist) "плейлист «$outTitle» · ${outgoing.size} тр." else if (outgoing.size == 1) "«$outTitle»" else "${outgoing.size} треков"
        return fitUtf8("$me$SEP$what", 120)
    }

    private fun fitUtf8(s: String, max: Int): String {
        var r = s
        while (r.toByteArray().size > max && r.isNotEmpty()) r = r.dropLast(1)
        return r
    }

    // ---------- соединение ----------

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (sending) {
                client.acceptConnection(id, payloads)
            } else {
                val name = info.endpointName.substringBefore(SEP)
                val what = info.endpointName.substringAfter(SEP, "")
                state.value = ShareUi.Incoming(SharePeer(id, name), what)
            }
        }

        override fun onConnectionResult(id: String, res: ConnectionResolution) {
            if (res.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                endpoint = id
                if (sending) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { sendAll(id) }.onFailure { e ->
                            withContext(Dispatchers.Main) { fail("Не удалось отправить: ${e.message ?: "ошибка"}") }
                        }
                    }
                } else {
                    runCatching { client.stopAdvertising() }
                    state.value = ShareUi.Transfer(peerName, false, 0, 0, 0, 0, 0)
                }
            } else if (sending) {
                fail(
                    if (res.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) "Второй телефон отклонил передачу"
                    else "Не удалось соединиться — поднеси телефоны ближе и попробуй ещё раз"
                )
            } else {
                state.value = ShareUi.Searching(false, "", 0, emptyList(), me)
            }
        }

        override fun onDisconnected(id: String) {
            val s = state.value
            if (s is ShareUi.Done || s is ShareUi.Idle || s is ShareUi.Failed) return
            if (!sending && finalizing) return // всё получили, дописываем у себя
            fail("Связь прервалась — поднеси телефоны ближе и попробуй ещё раз")
        }
    }

    fun accept() {
        val s = state.value as? ShareUi.Incoming ?: return
        peerName = s.peer.name
        state.value = ShareUi.Connecting(s.peer.name, false)
        client.acceptConnection(s.peer.id, payloads).addOnFailureListener { fail(it) }
    }

    fun reject() {
        val s = state.value as? ShareUi.Incoming ?: return
        runCatching { client.rejectConnection(s.peer.id) }
        state.value = ShareUi.Searching(false, "", 0, emptyList(), me)
    }

    // ---------- данные ----------

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            if (p.type == Payload.Type.BYTES) {
                val o = runCatching { JSONObject(String(p.asBytes() ?: return, Charsets.UTF_8)) }.getOrNull() ?: return
                when {
                    o.has("manifest") -> {
                        manifestPid = o.getLong("manifest")
                        scope.launch(Dispatchers.IO) { process() }
                    }
                    o.has("need") -> {
                        val a = o.getJSONArray("need")
                        need?.complete(List(a.length()) { a.getInt(it) })
                    }
                    o.has("done") -> peerDone?.complete(Unit)
                }
            } else if (p.type == Payload.Type.FILE) {
                files[p.id] = p
            }
        }

        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            if (sending) onSendUpdate(u) else onReceiveUpdate(u)
        }
    }

    // ---------- отправка ----------

    private suspend fun sendAll(id: String) {
        val cr = context.contentResolver
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val arr = JSONArray()
        val trackPayloads = ArrayList<Payload>()
        val coverPayloads = arrayOfNulls<Payload>(outgoing.size)
        outgoing.forEachIndexed { i, t ->
            val uri = Uri.parse(t.uri)
            val pfd = cr.openFileDescriptor(uri, "r") ?: error("Нет доступа к «${t.title}»")
            val size = pfd.statSize
            val p = Payload.fromFile(pfd)
            val name = displayName(uri) ?: "${t.artist} - ${t.title}"
            val ext = name.substringAfterLast('.', "").takeIf { it.length in 2..5 } ?: extFor(t.mime.ifBlank { cr.getType(uri).orEmpty() })
            val o = JSONObject()
                .put("pid", p.id)
                .put("title", t.title)
                .put("artist", t.artist)
                .put("album", t.album)
                .put("albumArtist", t.albumArtist)
                .put("year", t.year)
                .put("durationMs", t.durationMs)
                .put("mime", t.mime.ifBlank { cr.getType(uri).orEmpty() })
                .put("ext", ext)
                .put("size", size)
            runCatching { Artwork.customBytes(context, t.uri) }.getOrNull()?.let { b ->
                val f = File(dir, "cover$i.jpg").apply { writeBytes(b) }
                val cp = Payload.fromFile(f)
                coverPayloads[i] = cp
                o.put("cover", cp.id)
            }
            runCatching { lyrics.userLyrics(t) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { o.put("lyrics", it) }
            arr.put(o)
            trackPayloads += p
        }
        val mf = File(dir, "manifest.json")
        mf.writeText(
            JSONObject()
                .put("v", 1)
                .put("from", me)
                .put("title", outTitle)
                .put("playlist", outPlaylist)
                .put("tracks", arr)
                .toString()
        )
        val mp = Payload.fromFile(mf)
        val wait = CompletableDeferred<List<Int>>()
        need = wait
        peerDone = CompletableDeferred()
        client.sendPayload(id, Payload.fromBytes(JSONObject().put("lumen", 1).put("manifest", mp.id).toString().toByteArray()))
        client.sendPayload(id, mp)
        // Получатель отвечает, каких треков у него нет. Не ответил — шлём всё.
        val needed = withTimeoutOrNull(60_000) { wait.await() } ?: outgoing.indices.toList()
        sendSkipped = outgoing.size - needed.size
        needed.forEach { i -> trackPayloads.getOrNull(i)?.let { sendPids[it.id] = arr.getJSONObject(i).optLong("size") } }
        withContext(Dispatchers.Main) { showSend() }
        for (i in needed) {
            val p = trackPayloads.getOrNull(i) ?: continue
            client.sendPayload(id, p)
            coverPayloads[i]?.let { client.sendPayload(id, it) }
        }
        // Ненужные файлы закрываем.
        outgoing.indices.filter { it !in needed }.forEach { i -> runCatching { trackPayloads[i].asFile()?.asParcelFileDescriptor()?.close() } }
        if (needed.isEmpty()) finishSend()
    }

    private fun showSend() {
        state.value = ShareUi.Transfer(
            peer = peerName,
            sending = true,
            done = sentDone.size,
            count = sendPids.size,
            bytes = sentBytes.values.sum(),
            total = sendPids.values.sum(),
            skipped = sendSkipped,
        )
    }

    private fun onSendUpdate(u: PayloadTransferUpdate) {
        val pid = u.payloadId
        if (pid !in sendPids) return
        when (u.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> {
                sentBytes[pid] = u.bytesTransferred
                showSend()
            }
            PayloadTransferUpdate.Status.SUCCESS -> {
                sentBytes[pid] = u.totalBytes.takeIf { it > 0 } ?: sendPids[pid] ?: 0
                sentDone += pid
                showSend()
                if (sentDone.size == sendPids.size) finishSend()
            }
            else -> fail("Передача прервалась — поднеси телефоны ближе и попробуй ещё раз")
        }
    }

    private fun finishSend() {
        scope.launch {
            // Ждём, пока получатель допишет файлы и соберёт плейлист.
            withTimeoutOrNull(90_000) { peerDone?.await() }
            val count = sendPids.size
            val skipped = sendSkipped
            reset()
            cleanupCache()
            state.value = ShareUi.Done(true, count, skipped, null, outTitle)
        }
    }

    // ---------- приём ----------

    private fun onReceiveUpdate(u: PayloadTransferUpdate) {
        val pid = u.payloadId
        when (u.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> {
                gotBytes[pid] = u.bytesTransferred
                showReceive()
            }
            PayloadTransferUpdate.Status.SUCCESS -> {
                gotBytes[pid] = u.totalBytes
                completed += pid
                scope.launch(Dispatchers.IO) { process() }
            }
            else -> {
                failed += pid
                val isTrack = inTracks.any { it.optLong("pid") == pid }
                if (isTrack) fail("Передача прервалась — поднеси телефоны ближе и попробуй ещё раз")
                else scope.launch(Dispatchers.IO) { process() }
            }
        }
    }

    private fun showReceive() {
        if (manifest == null) return
        val pids = needIdx.map { inTracks[it].optLong("pid") }
        state.value = ShareUi.Transfer(
            peer = peerName,
            sending = false,
            done = needIdx.count { saved[it] != null },
            count = needIdx.size,
            bytes = pids.sumOf { gotBytes[it] ?: 0L },
            total = needIdx.sumOf { inTracks[it].optLong("size") },
            skipped = inTracks.size - needIdx.size,
        )
    }

    private suspend fun process() = lock.withLock {
        if (manifest == null) {
            val mp = manifestPid ?: return@withLock
            if (mp !in completed) return@withLock
            val f = files[mp] ?: return@withLock
            val m = runCatching { JSONObject(readText(f)) }.getOrNull()
            deletePayloadFile(f)
            if (m == null) {
                withContext(Dispatchers.Main) { fail("Второй телефон прислал непонятные данные — обнови Lumen на обоих") }
                return@withLock
            }
            manifest = m
            val arr = m.getJSONArray("tracks")
            inTracks = List(arr.length()) { arr.getJSONObject(it) }
            // Что у нас уже есть: то же название, исполнитель и длительность — не качаем повторно.
            val local = dao.visibleTracksOnce()
            val index = HashMap<String, MutableList<TrackEntity>>()
            local.forEach { t -> index.getOrPut(normalize(t.artist) + "|" + normalize(t.title)) { mutableListOf() } += t }
            existing = Array(inTracks.size) { i ->
                val o = inTracks[i]
                val key = normalize(o.optString("artist")) + "|" + normalize(o.optString("title"))
                val ms = o.optLong("durationMs")
                index[key]?.firstOrNull { ms <= 0 || it.durationMs <= 0 || kotlin.math.abs(it.durationMs - ms) < 3000 }?.uri
            }
            saved = arrayOfNulls(inTracks.size)
            needIdx = inTracks.indices.filter { existing[it] == null }
            val ep = endpoint
            if (ep != null) {
                client.sendPayload(ep, Payload.fromBytes(JSONObject().put("need", JSONArray(needIdx)).toString().toByteArray()))
            }
            withContext(Dispatchers.Main) { showReceive() }
        }
        // Сохраняем пришедшие треки.
        for (i in needIdx) {
            if (saved[i] != null) continue
            val o = inTracks[i]
            val pid = o.optLong("pid")
            if (pid !in completed) continue
            val f = files[pid] ?: continue
            val uri = runCatching { saveIncoming(f, o) }.getOrElse { e ->
                withContext(Dispatchers.Main) { fail("Не удалось сохранить «${o.optString("title")}»: ${e.message ?: "ошибка"}") }
                return@withLock
            }
            deletePayloadFile(f)
            saved[i] = uri
            withContext(Dispatchers.Main) { showReceive() }
        }
        val allTracks = needIdx.all { saved[it] != null }
        val coversSettled = needIdx.all { i ->
            val c = inTracks[i].optLong("cover", -1L)
            c == -1L || c in completed || c in failed
        }
        if (allTracks && coversSettled && !finalizing) {
            finalizing = true
            scope.launch(Dispatchers.IO) { finalizeReceive() }
        } else if (allTracks && !finalizing) {
            // Обложки задерживаются — не ждём их дольше 15 секунд.
            scope.launch(Dispatchers.IO) {
                delay(15_000)
                lock.withLock {
                    if (!finalizing) {
                        finalizing = true
                        scope.launch(Dispatchers.IO) { finalizeReceive() }
                    }
                }
            }
        }
    }

    private suspend fun finalizeReceive() {
        val m = manifest ?: return
        val got = needIdx.mapNotNull { saved[it] }
        if (got.isNotEmpty()) {
            scanner.scan()
            repeat(12) {
                if (got.all { dao.byUri(it) != null }) return@repeat
                delay(1500)
                scanner.scan()
            }
        }
        for (i in needIdx) {
            val uri = saved[i] ?: continue
            val o = inTracks[i]
            runCatching {
                dao.putOverride(
                    TrackOverride(
                        uri,
                        o.optString("title").ifBlank { null },
                        o.optString("artist").ifBlank { null },
                        o.optString("album").ifBlank { null },
                        o.optString("albumArtist").ifBlank { null },
                        o.optInt("year").takeIf { it > 0 },
                    )
                )
            }
            val cpid = o.optLong("cover", -1L)
            if (cpid != -1L && cpid in completed) {
                files[cpid]?.let { f ->
                    runCatching { readBytes(f) }.getOrNull()?.let { Artwork.setCustom(context, uri, it) }
                    deletePayloadFile(f)
                }
            }
            o.optString("lyrics").takeIf { it.isNotBlank() }?.let { text ->
                dao.byUri(uri)?.let { t -> runCatching { lyrics.saveUser(t, text) } }
            }
        }
        var playlistId: Long? = null
        val title = m.optString("title")
        if (m.optBoolean("playlist")) {
            val uris = inTracks.indices.mapNotNull { saved[it] ?: existing[it] }
            if (uris.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val id = dao.insertPlaylist(PlaylistEntity(name = title.ifBlank { "От ${m.optString("from")}" }, createdAt = now, updatedAt = now))
                dao.addToPlaylist(id, uris)
                playlistId = id
            }
        }
        endpoint?.let { ep -> runCatching { client.sendPayload(ep, Payload.fromBytes(JSONObject().put("done", true).toString().toByteArray())) } }
        val count = got.size
        val skipped = inTracks.size - needIdx.size
        delay(1200)
        withContext(Dispatchers.Main) {
            reset()
            state.value = ShareUi.Done(false, count, skipped, playlistId, title)
        }
    }

    // ---------- файлы ----------

    private fun payloadUri(p: Payload): Uri? = if (Build.VERSION.SDK_INT >= 29) p.asFile()?.asUri() else null

    private fun readBytes(p: Payload): ByteArray {
        val u = payloadUri(p) ?: error("нет файла")
        return context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: error("нет файла")
    }

    private fun readText(p: Payload) = String(readBytes(p), Charsets.UTF_8)

    private fun deletePayloadFile(p: Payload) {
        runCatching { payloadUri(p)?.let { context.contentResolver.delete(it, null, null) } }
    }

    /** Кладёт пришедший файл в «Музыка/Lumen» и возвращает его адрес в медиатеке. */
    private fun saveIncoming(p: Payload, o: JSONObject): String {
        if (Build.VERSION.SDK_INT < 29) error("нужен Android 10+")
        val src = payloadUri(p) ?: error("нет файла")
        val ext = o.optString("ext").ifBlank { "mp3" }
        val name = safe("${o.optString("artist")} - ${o.optString("title")}") + "." + ext
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, o.optString("mime").ifBlank { mimeFor(ext) })
            put(MediaStore.Audio.Media.TITLE, o.optString("title"))
            put(MediaStore.Audio.Media.ARTIST, o.optString("artist"))
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lumen")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val target = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Android не дал создать файл в «Музыке»")
        try {
            cr.openInputStream(src)?.use { inp ->
                cr.openOutputStream(target)?.use { out -> inp.copyTo(out, 256 * 1024) } ?: error("не удалось записать")
            } ?: error("не удалось прочитать")
            cr.update(target, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(target, null, null) }
            throw e
        }
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(target)).toString()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it }

    private fun cleanupCache() {
        runCatching { File(context.cacheDir, "share").deleteRecursively() }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "track" }

    private fun extFor(mime: String) = when {
        "flac" in mime -> "flac"
        "mpeg" in mime || "mp3" in mime -> "mp3"
        "mp4" in mime || "m4a" in mime || "aac" in mime -> "m4a"
        "ogg" in mime -> "ogg"
        "opus" in mime -> "opus"
        "wav" in mime -> "wav"
        "webm" in mime -> "webm"
        else -> "mp3"
    }

    private fun mimeFor(ext: String) = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "audio/*"
    }

    private companion object {
        const val SEP = "\u001F"
    }
}

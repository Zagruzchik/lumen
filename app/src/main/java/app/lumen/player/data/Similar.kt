package app.lumen.player.data

import kotlin.math.ln
import kotlin.random.Random

/**
 * «Похожее» из своей библиотеки: тот же исполнитель, близкий год, та же папка,
 * плюс то, что ты любишь и часто слушаешь. Немного случайности, чтобы не было одинаково,
 * и ограничения, чтобы не залипнуть в одном альбоме или исполнителе.
 */
object Similar {
    suspend fun pick(dao: LibraryDao, seeds: List<TrackEntity>, exclude: Set<String>, n: Int): List<TrackEntity> {
        if (seeds.isEmpty()) return emptyList()
        val all = dao.visibleTracksOnce()
        val plays = dao.allPlays().associateBy { it.uri }
        val favs = dao.allFavorites().mapTo(HashSet()) { it.uri }
        val disliked = dao.allDislikes().mapTo(HashSet()) { it.uri }
        val artists = seeds.map { it.artist.lowercase() }.toSet()
        val albumArtists = seeds.map { it.albumArtist.lowercase() }.toSet()
        val albums = seeds.map { it.albumKey }.toSet()
        val folders = seeds.map { it.folder }.toSet()
        val years = seeds.map { it.year }.filter { it > 0 }
        val rnd = Random(System.nanoTime())
        val scored = all.asSequence()
            .filter { it.uri !in exclude && it.uri !in disliked && seeds.none { s -> s.uri == it.uri } }
            .map { t ->
                var sc = 0.0
                if (t.artist.lowercase() in artists) sc += 5
                if (t.albumArtist.lowercase() in albumArtists) sc += 3
                if (t.albumKey in albums) sc += 1.5
                if (t.folder in folders) sc += 1
                if (years.isNotEmpty() && t.year > 0 && years.any { kotlin.math.abs(it - t.year) <= 3 }) sc += 2
                if (t.uri in favs) sc += 2
                plays[t.uri]?.let { sc += ln(1.0 + it.count) }
                sc += rnd.nextDouble() * 2.5
                t to sc
            }
            .sortedByDescending { it.second }
            .toList()
        val perAlbum = HashMap<String, Int>()
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<TrackEntity>(n)
        for ((t, _) in scored) {
            val a = perAlbum.getOrDefault(t.albumKey, 0)
            val ar = perArtist.getOrDefault(t.artist.lowercase(), 0)
            if (a >= 3 || ar >= 5) continue
            perAlbum[t.albumKey] = a + 1
            perArtist[t.artist.lowercase()] = ar + 1
            out += t
            if (out.size >= n) break
        }
        return out
    }
}

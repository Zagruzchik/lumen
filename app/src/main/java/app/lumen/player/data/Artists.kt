package app.lumen.player.data

/**
 * Разбор «составных» исполнителей: «Мияги & Эндшпиль», «A feat. B», «A, B», «A x B»,
 * а также «(feat. B)» в названии — каждый попадает в своего исполнителя.
 */
object Artists {
    private val SEP = Regex(
        "\\s*(?:,|;|\\s&\\s|&|\\s\\+\\s|\\s/\\s|\\bfeat\\.?(?=\\s)|\\bft\\.?(?=\\s)|\\bfeaturing\\b|\\s[xх×]\\s|\\svs\\.?\\s|\\sи\\s|\\sпри участии\\s)\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_FEAT = Regex("[(\\[](?:feat\\.?|ft\\.?|featuring|при уч\\.?|при участии)\\s+([^)\\]]+)[)\\]]", RegexOption.IGNORE_CASE)

    fun split(s: String): List<String> =
        s.split(SEP).map { it.trim().trim('-', '—').trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(s.trim()) }

    /** Все исполнители трека: из поля «исполнитель», «исполнитель альбома» и «feat.» в названии. */
    fun of(t: TrackEntity): List<String> {
        val out = LinkedHashMap<String, String>()
        fun add(n: String) { if (n.isNotBlank()) out.putIfAbsent(n.lowercase(), n) }
        split(t.artist).forEach(::add)
        TITLE_FEAT.findAll(t.title).forEach { m -> split(m.groupValues[1]).forEach(::add) }
        if (out.isEmpty()) split(t.albumArtist).forEach(::add)
        return out.values.toList()
    }

    /** Главный исполнитель трека (для перехода «к исполнителю»). */
    fun main(t: TrackEntity): String = of(t).firstOrNull() ?: t.albumArtist

    fun has(t: TrackEntity, name: String): Boolean {
        val key = name.lowercase()
        return of(t).any { it.lowercase() == key } || split(t.albumArtist).any { it.lowercase() == key }
    }

    fun rows(tracks: List<TrackEntity>): List<ArtistRow> {
        class Acc(val name: String, val cover: String) {
            val albums = HashSet<String>()
            var count = 0
        }
        val map = LinkedHashMap<String, Acc>()
        for (t in tracks) {
            val names = LinkedHashSet<String>()
            of(t).forEach { names += it }
            split(t.albumArtist).forEach { n -> if (names.none { it.equals(n, true) }) names += n }
            for (n in names) {
                val a = map.getOrPut(n.lowercase()) { Acc(n, t.uri) }
                a.albums += t.albumKey
                a.count++
            }
        }
        return map.values
            .map { ArtistRow(it.name, it.albums.size, it.count, it.cover) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}

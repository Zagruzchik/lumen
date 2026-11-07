package app.lumen.player.data

import org.json.JSONArray
import org.json.JSONObject

/** Поле, по которому проверяется трек. */
enum class SmartField(val id: String, val label: String, val kind: Kind) {
    ARTIST("artist", "Исполнитель", Kind.TEXT),
    ALBUM("album", "Альбом", Kind.TEXT),
    TITLE("title", "Название", Kind.TEXT),
    FOLDER("folder", "Папка", Kind.TEXT),
    FORMAT("format", "Формат", Kind.TEXT),
    YEAR("year", "Год", Kind.NUMBER),
    PLAYS("plays", "Прослушиваний", Kind.NUMBER),
    DURATION("duration", "Длительность, мин", Kind.NUMBER),
    ADDED("added", "Добавлен, дней назад", Kind.DAYS),
    LAST_PLAYED("lastPlayed", "Слушал, дней назад", Kind.DAYS),
    FAVORITE("favorite", "В любимом", Kind.BOOL);

    enum class Kind { TEXT, NUMBER, DAYS, BOOL }

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: ARTIST
    }
}

/** Сравнение. Для каждого вида поля — свой набор. */
enum class SmartOp(val id: String, val label: String) {
    CONTAINS("contains", "содержит"),
    NOT_CONTAINS("notContains", "не содержит"),
    IS("is", "равно"),
    IS_NOT("isNot", "не равно"),
    AT_LEAST("atLeast", "не меньше"),
    AT_MOST("atMost", "не больше"),
    WITHIN("within", "не позже чем"),
    OLDER("older", "раньше чем (или никогда)"),
    YES("yes", "да"),
    NO("no", "нет");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: CONTAINS
        fun forKind(k: SmartField.Kind): List<SmartOp> = when (k) {
            SmartField.Kind.TEXT -> listOf(CONTAINS, NOT_CONTAINS, IS, IS_NOT)
            SmartField.Kind.NUMBER -> listOf(AT_LEAST, AT_MOST, IS)
            SmartField.Kind.DAYS -> listOf(WITHIN, OLDER)
            SmartField.Kind.BOOL -> listOf(YES, NO)
        }
    }
}

enum class SmartSort(val id: String, val label: String) {
    RANDOM("random", "Случайно"),
    ADDED("added", "Сначала новые"),
    PLAYS("plays", "Сначала частые"),
    LAST_PLAYED("lastPlayed", "Недавно слушал"),
    TITLE("title", "По названию"),
    ARTIST("artist", "По исполнителю"),
    YEAR("year", "Сначала свежие по году");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: RANDOM
    }
}

data class SmartCond(val field: SmartField, val op: SmartOp, val value: String)

data class SmartRules(
    val matchAll: Boolean = true,
    val conds: List<SmartCond> = emptyList(),
    val sort: SmartSort = SmartSort.RANDOM,
    val limit: Int = 0,
) {
    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("match", if (matchAll) "all" else "any")
        .put("sort", sort.id)
        .put("limit", limit)
        .put("rules", JSONArray().apply {
            conds.forEach { put(JSONObject().put("field", it.field.id).put("op", it.op.id).put("value", it.value)) }
        })
        .toString()

    /** Короткое описание правил для подписи. */
    fun summary(): String {
        if (conds.isEmpty()) return "Вся библиотека"
        val parts = conds.map { c ->
            when (c.field.kind) {
                SmartField.Kind.BOOL -> "${c.field.label.lowercase()}: ${c.op.label}"
                SmartField.Kind.DAYS -> when (c.op) {
                    SmartOp.WITHIN -> "${if (c.field == SmartField.ADDED) "добавлен" else "слушал"} за ${c.value} дн."
                    else -> "${if (c.field == SmartField.ADDED) "добавлен" else "не слушал"} больше ${c.value} дн."
                }
                else -> "${c.field.label.lowercase()} ${c.op.label} «${c.value}»"
            }
        }
        val join = if (matchAll) " и " else " или "
        val base = parts.joinToString(join)
        return if (limit > 0) "$base · до $limit" else base
    }

    companion object {
        fun parse(s: String): SmartRules = runCatching {
            val o = JSONObject(s)
            val arr = o.optJSONArray("rules") ?: JSONArray()
            SmartRules(
                matchAll = o.optString("match", "all") != "any",
                conds = (0 until arr.length()).map {
                    val c = arr.getJSONObject(it)
                    SmartCond(SmartField.of(c.optString("field")), SmartOp.of(c.optString("op")), c.optString("value"))
                },
                sort = SmartSort.of(o.optString("sort")),
                limit = o.optInt("limit", 0).coerceIn(0, 5000),
            )
        }.getOrDefault(SmartRules())

        /** Готовые заготовки, чтобы не собирать правила с нуля. */
        val templates: List<Pair<String, SmartRules>> = listOf(
            "Свежее" to SmartRules(conds = listOf(SmartCond(SmartField.ADDED, SmartOp.WITHIN, "30")), sort = SmartSort.ADDED),
            "Давно не слушал" to SmartRules(
                conds = listOf(SmartCond(SmartField.FAVORITE, SmartOp.YES, ""), SmartCond(SmartField.LAST_PLAYED, SmartOp.OLDER, "60")),
            ),
            "Топ-50" to SmartRules(conds = listOf(SmartCond(SmartField.PLAYS, SmartOp.AT_LEAST, "1")), sort = SmartSort.PLAYS, limit = 50),
            "Ни разу не слушал" to SmartRules(conds = listOf(SmartCond(SmartField.PLAYS, SmartOp.IS, "0"))),
            "Только lossless" to SmartRules(
                matchAll = false,
                conds = listOf(
                    SmartCond(SmartField.FORMAT, SmartOp.IS, "FLAC"),
                    SmartCond(SmartField.FORMAT, SmartOp.IS, "ALAC"),
                    SmartCond(SmartField.FORMAT, SmartOp.IS, "WAV"),
                ),
            ),
            "Нулевые" to SmartRules(
                conds = listOf(SmartCond(SmartField.YEAR, SmartOp.AT_LEAST, "2000"), SmartCond(SmartField.YEAR, SmartOp.AT_MOST, "2009")),
                sort = SmartSort.YEAR,
            ),
            "Длинные треки" to SmartRules(conds = listOf(SmartCond(SmartField.DURATION, SmartOp.AT_LEAST, "8")), sort = SmartSort.RANDOM),
        )
    }
}

/** Всё, что нужно для проверки правил, собранное один раз. */
class SmartContext(
    val plays: Map<String, PlayStatEntity>,
    val favorites: Set<String>,
    val disliked: Set<String>,
    val format: (TrackEntity) -> String,
    val now: Long = System.currentTimeMillis(),
)

object SmartEngine {
    private const val DAY = 86_400_000L

    /** seed — чтобы «случайный» порядок не прыгал при каждом обновлении библиотеки. */
    fun evaluate(rules: SmartRules, all: List<TrackEntity>, ctx: SmartContext, seed: Long = 0L): List<TrackEntity> {
        val picked = all.filter { t ->
            t.uri !in ctx.disliked && (rules.conds.isEmpty() ||
                if (rules.matchAll) rules.conds.all { matches(it, t, ctx) } else rules.conds.any { matches(it, t, ctx) })
        }
        val sorted = when (rules.sort) {
            SmartSort.RANDOM -> picked.shuffled(kotlin.random.Random(seed))
            SmartSort.ADDED -> picked.sortedByDescending { addedMs(it) }
            SmartSort.PLAYS -> picked.sortedWith(compareByDescending<TrackEntity> { ctx.plays[it.uri]?.count ?: 0 }.thenByDescending { ctx.plays[it.uri]?.lastPlayed ?: 0L })
            SmartSort.LAST_PLAYED -> picked.sortedByDescending { ctx.plays[it.uri]?.lastPlayed ?: 0L }
            SmartSort.TITLE -> picked.sortedBy { it.title.lowercase() }
            SmartSort.ARTIST -> picked.sortedWith(compareBy<TrackEntity> { it.artist.lowercase() }.thenBy { it.albumKey }.thenBy { it.discNo }.thenBy { it.trackNo })
            SmartSort.YEAR -> picked.sortedByDescending { it.year }
        }
        return if (rules.limit > 0) sorted.take(rules.limit) else sorted
    }

    private fun addedMs(t: TrackEntity) = if (t.dateAdded in 1 until 10_000_000_000L) t.dateAdded * 1000 else t.dateAdded

    private fun matches(c: SmartCond, t: TrackEntity, ctx: SmartContext): Boolean {
        val v = c.value.trim()
        return when (c.field.kind) {
            SmartField.Kind.TEXT -> {
                val subject = when (c.field) {
                    SmartField.ARTIST -> "${t.artist} ${t.albumArtist}"
                    SmartField.ALBUM -> t.album
                    SmartField.TITLE -> t.title
                    SmartField.FOLDER -> "${t.folderName} ${t.path}"
                    SmartField.FORMAT -> ctx.format(t)
                    else -> ""
                }.lowercase()
                val needle = v.lowercase()
                when (c.op) {
                    SmartOp.CONTAINS -> needle.isEmpty() || subject.contains(needle)
                    SmartOp.NOT_CONTAINS -> needle.isEmpty() || !subject.contains(needle)
                    SmartOp.IS -> when (c.field) {
                        SmartField.ARTIST -> t.artist.equals(v, true) || t.albumArtist.equals(v, true) ||
                            Artists.split(t.artist).any { it.equals(v, true) }
                        SmartField.FOLDER -> t.folderName.equals(v, true)
                        else -> subject == needle
                    }
                    SmartOp.IS_NOT -> !matches(c.copy(op = SmartOp.IS), t, ctx)
                    else -> true
                }
            }
            SmartField.Kind.NUMBER -> {
                val n = v.replace(',', '.').toDoubleOrNull() ?: return true
                val x: Double = when (c.field) {
                    SmartField.YEAR -> t.year.toDouble()
                    SmartField.PLAYS -> (ctx.plays[t.uri]?.count ?: 0).toDouble()
                    SmartField.DURATION -> t.durationMs / 60000.0
                    else -> 0.0
                }
                if (c.field == SmartField.YEAR && t.year <= 0) return false
                when (c.op) {
                    SmartOp.AT_LEAST -> x >= n
                    SmartOp.AT_MOST -> x <= n
                    SmartOp.IS -> if (c.field == SmartField.DURATION) kotlin.math.abs(x - n) < 0.5 else x.toLong() == n.toLong()
                    else -> true
                }
            }
            SmartField.Kind.DAYS -> {
                val days = v.toLongOrNull() ?: return true
                val at: Long? = when (c.field) {
                    SmartField.ADDED -> addedMs(t).takeIf { it > 0 }
                    SmartField.LAST_PLAYED -> ctx.plays[t.uri]?.lastPlayed?.takeIf { it > 0 }
                    else -> null
                }
                val border = ctx.now - days * DAY
                when (c.op) {
                    SmartOp.WITHIN -> at != null && at >= border
                    SmartOp.OLDER -> at == null || at < border
                    else -> true
                }
            }
            SmartField.Kind.BOOL -> {
                val yes = t.uri in ctx.favorites
                if (c.op == SmartOp.NO) !yes else yes
            }
        }
    }
}

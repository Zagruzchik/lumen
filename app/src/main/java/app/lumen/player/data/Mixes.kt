package app.lumen.player.data

/**
 * Подборки под настроение и под занятие.
 * genres — слова, по которым подходят треки из своей библиотеки (жанр из тегов);
 * playlists — запросы, по которым ищутся готовые плейлисты в сети.
 */
data class MoodMix(
    val id: String,
    val title: String,
    val subtitle: String,
    val from: Long,
    val to: Long,
    val genres: List<String>,
    val playlists: List<String>,
)

object Mixes {
    val moods = listOf(
        MoodMix("calm", "Спокойное", "Тихо и тепло", 0xFF5B8DEF, 0xFF1B2747,
            listOf("ambient", "acoustic", "folk", "singer", "new age", "chill", "lo-fi", "lofi", "jazz", "easy listening", "bossa"),
            listOf("chill vibes", "спокойная музыка", "acoustic chill")),
        MoodMix("energy", "Бодрое", "Заряд на день", 0xFFFFB347, 0xFF5A2A0A,
            listOf("dance", "pop", "electro", "edm", "house", "funk", "disco"),
            listOf("feel good hits", "бодрая музыка", "upbeat pop")),
        MoodMix("sad", "Грустное", "Когда хочется помолчать", 0xFF6C7A99, 0xFF161A26,
            listOf("indie", "alternative", "singer", "acoustic", "slowcore", "emo", "piano"),
            listOf("sad songs", "грустные песни", "melancholy indie")),
        MoodMix("love", "Романтика", "Для двоих", 0xFFFF6B9A, 0xFF3D0F24,
            listOf("r&b", "rnb", "soul", "love", "ballad"),
            listOf("love songs", "романтичные песни", "r&b love")),
        MoodMix("party", "Вечеринка", "Громче!", 0xFFB45CFF, 0xFF24093D,
            listOf("dance", "house", "hip-hop", "hip hop", "rap", "electro", "edm", "reggaeton", "pop"),
            listOf("party hits", "хиты для вечеринки", "club bangers")),
        MoodMix("dreamy", "Мечтательное", "Синтвейв и дрим-поп", 0xFF4FD1C5, 0xFF0E2E35,
            listOf("dream", "shoegaze", "synth", "ambient", "chillwave", "indie"),
            listOf("dream pop", "synthwave", "night drive synthwave")),
        MoodMix("drive", "Драйв", "Тяжелее и быстрее", 0xFFFF4D4D, 0xFF2E0606,
            listOf("metal", "rock", "punk", "phonk", "hardcore", "grunge"),
            listOf("phonk", "aggressive rock", "hard rock hits")),
    )

    val activities = listOf(
        MoodMix("workout", "Тренировка", "Ещё один подход", 0xFFFF7A45, 0xFF3A1206,
            listOf("hip-hop", "hip hop", "rap", "phonk", "edm", "trap", "rock", "metal"),
            listOf("workout motivation", "gym phonk", "музыка для тренировок")),
        MoodMix("run", "Бег", "Ровный быстрый темп", 0xFF34D399, 0xFF06301F,
            listOf("dance", "edm", "drum & bass", "drum and bass", "electro", "house", "pop"),
            listOf("running mix", "running music", "музыка для бега")),
        MoodMix("focus", "Фокус", "Без слов, без отвлечений", 0xFF60A5FA, 0xFF0B1E3A,
            listOf("ambient", "classical", "instrumental", "soundtrack", "post-rock", "electronic", "minimal"),
            listOf("deep focus", "music for work", "музыка для работы")),
        MoodMix("study", "Учёба", "Лоу-фай и тишина", 0xFFA78BFA, 0xFF1E1240,
            listOf("lo-fi", "lofi", "jazz", "classical", "instrumental", "chill"),
            listOf("lofi study", "lofi hip hop", "музыка для учёбы")),
        MoodMix("sleep", "Сон", "Очень тихо", 0xFF818CF8, 0xFF0D0F2A,
            listOf("ambient", "new age", "classical", "piano", "meditation"),
            listOf("sleep music", "музыка для сна", "calm piano sleep")),
        MoodMix("road", "В дороге", "Музыка в машину", 0xFFFBBF24, 0xFF3A2A05,
            listOf("rock", "pop", "synth", "hip-hop", "hip hop", "indie", "country"),
            listOf("road trip", "музыка в машину", "driving music")),
        MoodMix("morning", "Утро", "Мягкий старт", 0xFFFDE68A, 0xFF4A3510,
            listOf("pop", "indie", "acoustic", "soul", "folk", "jazz"),
            listOf("morning coffee", "good morning songs", "утренняя музыка")),
        MoodMix("home", "Дома", "Готовить, убираться, жить", 0xFFF472B6, 0xFF3A0F28,
            listOf("soul", "jazz", "funk", "indie", "disco", "pop"),
            listOf("cooking music", "sunday morning", "музыка для дома")),
        MoodMix("gaming", "Игры", "Фон для катки", 0xFF22D3EE, 0xFF052A33,
            listOf("electro", "phonk", "edm", "dubstep", "soundtrack", "synth"),
            listOf("gaming music", "phonk gaming", "музыка для игр")),
        MoodMix("meditate", "Медитация", "Дыхание и тишина", 0xFF5EEAD4, 0xFF06302A,
            listOf("ambient", "new age", "meditation", "drone"),
            listOf("meditation music", "музыка для медитации")),
    )

    /** Что подойдёт прямо сейчас — по времени суток и дню недели. */
    fun now(hour: Int, weekend: Boolean): MoodMix = when (hour) {
        in 5..9 -> activities.first { it.id == "morning" }
        in 10..17 -> if (weekend) activities.first { it.id == "home" } else activities.first { it.id == "focus" }
        in 18..21 -> if (weekend) moods.first { it.id == "party" } else moods.first { it.id == "calm" }
        else -> activities.first { it.id == "sleep" }
    }
}

package app.lumen.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent

/** Раздел настроек: что внутри (для подписи и поиска). */
data class SettingsCat(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val items: List<String>,
)

val SETTINGS_CATS = listOf(
    SettingsCat(
        "look", "Внешний вид", LIcons.Sparkle,
        listOf("Темы", "Редактор тем", "Material You", "Светлая тема", "Формат файла", "Вспышки под бит", "Сила вспышек"),
    ),
    SettingsCat(
        "play", "Воспроизведение", LIcons.Play,
        listOf(
            "Выравнивание громкости", "Плавная пауза", "Умная очередь", "Помнить место в длинных треках",
            "Продолжать при подключении наушников", "Пропускать тишину", "Экран блокировки", "Кроссфейд", "Переходы между треками",
        ),
    ),
    SettingsCat(
        "sound", "Звук и эквалайзер", LIcons.Sliders,
        listOf("Эквалайзер", "Пресеты", "Бас", "AutoEq", "Профиль наушников"),
    ),
    SettingsCat(
        "library", "Библиотека и папки", LIcons.Library,
        listOf("Пересканировать", "Короткие файлы", "Свои папки", "Исключённые папки", "Не нравится", "Дубликаты"),
    ),
    SettingsCat(
        "lyrics", "Тексты песен", LIcons.Lyrics,
        listOf("Тексты из интернета", "Скачать все тексты", "Караоке", "Синхронный текст"),
    ),
    SettingsCat(
        "online", "Онлайн-музыка", LIcons.Globe,
        listOf("SoundCloud", "YouTube", "Встроенный движок", "Свой сервер", "Скачивание", "FLAC", "Opus"),
    ),
    SettingsCat(
        "modes", "Будильник, машина, ночь", LIcons.Car,
        listOf("Будильник", "Android Auto", "Режим в машине", "Ночные часы на зарядке"),
    ),
    SettingsCat(
        "account", "Аккаунт и перенос", LIcons.Person,
        listOf("Аккаунт", "Синхронизация", "Last.fm", "Скробблинг", "Экспорт", "Импорт", "Перенос на ПК", "Архив"),
    ),
    SettingsCat(
        "system", "Система и обновления", LIcons.Refresh,
        listOf("Обновления", "Проверять при запуске", "Работа в фоне", "Экономия батареи", "Автозапуск", "О приложении", "Версия"),
    ),
)

/** Строка раздела на главной странице настроек. */
@Composable
fun SettingsCatRow(c: SettingsCat, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LocalAccent.current.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(c.icon, null, Modifier.size(22.dp), tint = LocalAccent.current) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                c.items.take(4).joinToString(", "),
                fontSize = 12.sp,
                color = LC.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(LIcons.Back, null, Modifier.size(18.dp).graphicsLayer { rotationZ = 180f }, tint = LC.Faint)
    }
}

/** Поле поиска по настройкам. */
@Composable
fun SettingsSearch(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(LC.Glass)
            .border(1.dp, LC.Border, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LIcons.Search, null, Modifier.size(20.dp), tint = LC.Dim)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск по настройкам", fontSize = 15.sp, color = LC.Faint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = LC.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(LocalAccent.current),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                LIcons.Close,
                "Очистить",
                Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { onChange("") },
                tint = LC.Dim,
            )
        }
    }
}

/** Совпадения поиска: (раздел, пункт). */
fun settingsMatches(q: String): List<Pair<SettingsCat, String>> {
    val s = q.trim().lowercase()
    if (s.isEmpty()) return emptyList()
    val out = mutableListOf<Pair<SettingsCat, String>>()
    SETTINGS_CATS.forEach { c ->
        if (c.title.lowercase().contains(s)) out += c to c.title
        c.items.filter { it.lowercase().contains(s) }.forEach { out += c to it }
    }
    return out.distinct()
}

@Composable
fun SettingsMatchRow(cat: SettingsCat, item: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(cat.icon, null, Modifier.size(20.dp), tint = LocalAccent.current)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (item != cat.title) Text(cat.title, fontSize = 12.sp, color = LC.Dim)
        }
    }
}

@Composable
fun SettingsCatHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(LC.Glass)
                .clickable(onClickLabel = "Назад", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(LIcons.Back, "Назад", Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(2.dp))
}

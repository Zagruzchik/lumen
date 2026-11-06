package app.lumen.player.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.IconBtn
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.components.glass
import app.lumen.player.ui.components.pluralRu
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent

private fun folderLabel(uri: String): String {
    val seg = Uri.decode(Uri.parse(uri).lastPathSegment ?: uri)
    val path = seg.substringAfter(':', seg)
    val volume = seg.substringBefore(':', "")
    val where = if (volume == "primary" || volume.isEmpty()) "Память телефона" else "SD-карта"
    return if (path.isBlank()) where else "$where › $path"
}

@Composable
fun SettingsScreen(vm: MainViewModel, bottomPad: Dp) {
    val ctx = LocalContext.current
    val folders by vm.folderList.collectAsStateWithLifecycle()
    val skipShort by vm.skipShort.collectAsStateWithLifecycle()
    val online by vm.onlineLyrics.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addFolder(uri.toString())
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }

        item {
            Card {
                Text("Библиотека", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${pluralRu(tracks.size, "трек", "трека", "треков")} в медиатеке и выбранных папках",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Пересканировать", LIcons.Refresh, { vm.rescan() }, Modifier.weight(1f), primary = false)
                    if (scanning) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    "Скрывать короткие треки",
                    "Голосовые и звуки короче 30 секунд не попадут в библиотеку",
                    skipShort,
                ) { vm.setSkipShort(it) }
            }
        }

        item {
            Card {
                Text("Папки с музыкой", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Музыка из системной медиатеки находится сама. Добавь папку, если треки лежат там, где Android их не видит.",
                    fontSize = 13.sp,
                    color = LC.Dim,
                )
                Spacer(Modifier.height(10.dp))
                folders.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LIcons.Folder, null, Modifier.size(20.dp), tint = LocalAccent.current)
                        Spacer(Modifier.width(10.dp))
                        Text(folderLabel(f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconBtn(LIcons.Trash, "Убрать папку", { vm.removeFolder(f) }, glass = false, size = 40.dp, iconSize = 18.dp, tint = LC.Dim)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PillButton("Добавить папку", LIcons.Plus, { pickFolder.launch(null) }, Modifier.fillMaxWidth())
            }
        }

        item {
            Card {
                Text("Тексты песен", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ToggleRow(
                    "Искать тексты онлайн",
                    "Если рядом с треком нет .lrc, текст подтянется из LRCLIB (бесплатно, без регистрации)",
                    online,
                ) { vm.setOnlineLyrics(it) }
            }
        }

        item {
            Card {
                Text("О приложении", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Lumen 0.1.0 · MVP", fontSize = 14.sp, color = LC.Dim)
                Text("Тексты песен: lrclib.net", fontSize = 14.sp, color = LC.Dim)
                Text("Никакой рекламы и слежки — всё работает на телефоне.", fontSize = 14.sp, color = LC.Dim)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) { content() }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = LC.Dim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = LC.Dim,
                uncheckedTrackColor = Color(0x1FFFFFFF),
                uncheckedBorderColor = Color(0x33FFFFFF),
            ),
        )
    }
}

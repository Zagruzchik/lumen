package app.lumen.player.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.player.data.TrackEntity
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.Cover
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.launch

/**
 * Свои теги и обложка трека. Файлы не трогаем: правки хранятся в Lumen,
 * переживают пересканирование и попадают в архив экспорта.
 */
@Composable
fun EditTagsSheet(vm: MainViewModel, t: TrackEntity, onClose: () -> Unit, sheetModifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(t.uri) { mutableStateOf(t.title) }
    var artist by remember(t.uri) { mutableStateOf(t.artist) }
    var album by remember(t.uri) { mutableStateOf(t.album) }
    var albumArtist by remember(t.uri) { mutableStateOf(t.albumArtist) }
    var year by remember(t.uri) { mutableStateOf(if (t.year > 0) t.year.toString() else "") }
    var covers by remember(t.uri) { mutableStateOf<List<String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var busyCover by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busyCover = true
            vm.setCoverFromGallery(t, uri) { ok ->
                busyCover = false
                if (!ok) Toast.makeText(ctx, "Не получилось открыть картинку", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
        )
        Column(
            sheetModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp)
                .navigationBarsPadding()
                .imePadding()
                .clip(RoundedCornerShape(30.dp))
                .background(app.lumen.player.ui.theme.LT.sheetBrush())
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(30.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("Теги и обложка", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("Изменения сохраняются в Lumen — сами файлы не трогаются", fontSize = 13.sp, color = LC.Dim)
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Cover(t.uri, Modifier.size(96.dp), corner = 18.dp, sizePx = 384)
                    if (busyCover) CircularProgressIndicator(Modifier.size(28.dp), color = LocalAccent.current, strokeWidth = 2.5.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    PillButton("Найти в Deezer", LIcons.Search, {
                        searching = true
                        scope.launch {
                            covers = vm.findCovers(t.copy(title = title, artist = artist, album = album))
                            searching = false
                            if (covers.isNullOrEmpty()) Toast.makeText(ctx, "Ничего не нашлось — проверь исполнителя и альбом", Toast.LENGTH_SHORT).show()
                        }
                    }, Modifier.fillMaxWidth())
                    PillButton("Из галереи", LIcons.Plus, {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.fillMaxWidth(), primary = false)
                }
            }
            AnimatedVisibility(searching || !covers.isNullOrEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 12.dp)) {
                    if (searching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = LocalAccent.current, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Ищу обложки…", fontSize = 14.sp, color = LC.Dim)
                        }
                    } else {
                        Text("Выбери обложку — она встанет на весь альбом", fontSize = 13.sp, color = LC.Dim)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(covers.orEmpty()) { url ->
                                RemoteCover(
                                    url,
                                    url,
                                    RoundedCornerShape(14.dp),
                                    Modifier
                                        .size(92.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            busyCover = true
                                            vm.setCover(t, url) { ok ->
                                                busyCover = false
                                                Toast.makeText(ctx, if (ok) "Обложка обновлена" else "Не удалось скачать обложку", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TagField("Название", title) { title = it }
            TagField("Исполнитель", artist) { artist = it }
            TagField("Альбом", album) { album = it }
            TagField("Исполнитель альбома", albumArtist) { albumArtist = it }
            TagField("Год", year, number = true) { year = it.filter(Char::isDigit).take(4) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Вернуть обложку", LIcons.Refresh, {
                    vm.setCover(t, null) { }
                }, Modifier.weight(1f), primary = false)
                PillButton("Сохранить", LIcons.Check, {
                    vm.saveTags(t, title, artist, album, albumArtist, year.toIntOrNull())
                    Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
                    onClose()
                }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TagField(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(label, fontSize = 12.sp, color = LC.Dim, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = LC.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(LocalAccent.current),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x1AFFFFFF))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

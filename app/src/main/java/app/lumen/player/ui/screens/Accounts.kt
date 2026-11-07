package app.lumen.player.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.components.PillButton
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent

@Composable
private fun Field(value: String, hint: String, password: Boolean = false, email: Boolean = false, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, LC.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(hint, fontSize = 15.sp, color = LC.Faint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = LC.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(LocalAccent.current),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    password -> KeyboardType.Password
                    email -> KeyboardType.Email
                    else -> KeyboardType.Ascii
                }
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Status(text: String?, error: Boolean) {
    if (text == null) return
    Text(text, fontSize = 13.sp, color = if (error) Color(0xFFFF8A8A) else LocalAccent.current, modifier = Modifier.padding(top = 8.dp))
}

/** Аккаунт Lumen: один вход — и любимое, плейлисты, «не нравится» и счётчики общие с ПК. */
@Composable
fun SyncSection(vm: MainViewModel) {
    val st by vm.syncState.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val df = androidx.compose.runtime.remember { java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale("ru")) }
    Text("Синхронизация с ПК", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text(
        "Любимое, «Не нравится», плейлисты (и умные) и счётчики прослушиваний — общие с Lumen на компьютере. " +
            "Треки узнаются по исполнителю, названию и длительности, поэтому пути к файлам могут отличаться.",
        fontSize = 13.sp,
        color = LC.Dim,
    )
    Spacer(Modifier.height(12.dp))
    if (st.email == null) {
        Field(email, "Почта", email = true) { email = it.trim() }
        Field(pass, "Пароль (от 6 символов)", password = true) { pass = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Войти", null, { if (email.isNotBlank() && pass.isNotBlank()) vm.syncSignIn(email, pass, create = false) }, Modifier.weight(1f))
            PillButton("Создать аккаунт", null, { if (email.isNotBlank() && pass.length >= 6) vm.syncSignIn(email, pass, create = true) }, Modifier.weight(1f), primary = false)
        }
        Text("Тот же аккаунт используй в Lumen на ПК.", fontSize = 12.sp, color = LC.Faint, modifier = Modifier.padding(top = 6.dp))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.email.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (st.lastSyncAt > 0) "Последняя синхронизация: ${df.format(java.util.Date(st.lastSyncAt))}" else "Ещё не синхронизировано",
                    fontSize = 12.sp,
                    color = LC.Dim,
                )
            }
            if (st.busy) CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Синхронизировать", LIcons.Refresh, { if (!st.busy) vm.syncNow() }, Modifier.weight(1f))
            PillButton("Выйти", null, { vm.syncSignOut() }, Modifier.weight(0.6f), primary = false)
        }
        Text("Синхронизация идёт сама при открытии приложения (не чаще раза в 15 минут).", fontSize = 12.sp, color = LC.Faint, modifier = Modifier.padding(top = 6.dp))
    }
    if (st.busy && st.email == null) {
        Spacer(Modifier.height(8.dp))
        CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
    Status(st.message, st.error)
}

/** Last.fm: свой ключ API → вход → скробблинг. */
@Composable
fun LastFmSection(vm: MainViewModel) {
    val st by vm.lastFm.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    var key by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Last.fm", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    st.user != null -> "Скробблинг как ${st.user}" + if (st.pending > 0) " · ждут отправки: ${st.pending}" else ""
                    st.hasKeys -> "Ключ сохранён — осталось войти"
                    else -> "Отправляй прослушивания в свой профиль Last.fm"
                },
                fontSize = 13.sp,
                color = LC.Dim,
            )
        }
        if (st.user != null) {
            Switch(
                checked = st.enabled,
                onCheckedChange = { vm.lastFmEnabled(it) },
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
    Spacer(Modifier.height(10.dp))
    when {
        !st.hasKeys -> {
            Text(
                "1. Открой last.fm/api/account/create (нужен вход в Last.fm).\n" +
                    "2. Название — любое, например «Lumen», остальные поля можно оставить пустыми.\n" +
                    "3. Скопируй сюда «API key» и «Shared secret».",
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(8.dp))
            PillButton("Открыть last.fm/api", null, {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/api/account/create")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, Modifier.fillMaxWidth(), primary = false)
            Spacer(Modifier.height(8.dp))
            Field(key, "API key") { key = it.trim() }
            Field(secret, "Shared secret") { secret = it.trim() }
            Spacer(Modifier.height(8.dp))
            PillButton("Сохранить ключ", LIcons.Check, { if (key.length >= 20 && secret.length >= 20) vm.lastFmSetKeys(key, secret) }, Modifier.fillMaxWidth())
        }
        st.user == null -> {
            Field(user, "Логин Last.fm") { user = it.trim() }
            Field(pass, "Пароль", password = true) { pass = it }
            Text("Пароль не сохраняется — Lumen получает только ключ сессии.", fontSize = 12.sp, color = LC.Faint, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Войти", null, { if (user.isNotBlank() && pass.isNotBlank()) vm.lastFmLogin(user, pass) }, Modifier.weight(1f))
                PillButton("Другой ключ", null, { vm.lastFmSetKeys("", "") }, Modifier.weight(1f), primary = false)
            }
        }
        else -> {
            Text("Трек засчитывается, когда прозвучал наполовину или 4 минуты. Без интернета — копится и уйдёт позже.", fontSize = 12.sp, color = LC.Faint)
            Spacer(Modifier.height(8.dp))
            PillButton("Выйти из Last.fm", null, { vm.lastFmLogout() }, Modifier.fillMaxWidth(), primary = false)
        }
    }
    Status(st.error, true)
    Spacer(Modifier.width(1.dp))
}

package app.lumen.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.components.LIcons
import app.lumen.player.ui.theme.LC
import app.lumen.player.ui.theme.LocalAccent
import kotlinx.coroutines.delay

private enum class AuthMode { SignIn, Register, Forgot }

/**
 * Обязательный вход: Lumen хранит плейлисты, лайки и треки в аккаунте.
 * Шаги: вход / регистрация → «Проверь почту» (код или ссылка) → готово.
 * Ссылка «Забыл пароль» ведёт обратно в приложение, где задаётся новый пароль.
 */
@Composable
fun AuthGate(vm: MainViewModel) {
    val st by vm.syncState.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    var mode by rememberSaveable { mutableStateOf(if (vm.existingUser) AuthMode.SignIn else AuthMode.Register) }
    var email by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    // Регистрация ждёт подтверждения почты / вход по ссылке «Забыл пароль».
    val step = when {
        st.needNewPassword -> "newpass"
        st.pendingEmail != null -> "code"
        else -> mode.name
    }

    // Пока экран входа открыт, «назад» не выпускает в приложение — только между шагами.
    BackHandler {
        when {
            st.pendingEmail != null -> vm.authCancelPending()
            mode == AuthMode.Forgot -> mode = AuthMode.SignIn
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(LC.Bg)
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        AuthBackdrop(accent)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Logo(accent)
            Spacer(Modifier.height(14.dp))
            Text("Lumen", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Text(
                "Музыка, которая всегда с тобой",
                fontSize = 15.sp,
                color = LC.Dim,
            )
            Spacer(Modifier.height(22.dp))

            AnimatedVisibility(vm.existingUser && step != "newpass", enter = fadeIn(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(accent.copy(alpha = 0.13f))
                        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .padding(14.dp),
                ) {
                    Icon(LIcons.Info, null, Modifier.size(22.dp), tint = accent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Теперь в Lumen есть аккаунт", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Войди или зарегистрируйся, чтобы плейлисты, лайки и скачанные треки сохранялись и переезжали на новый телефон. " +
                                "Твоя библиотека на месте — ничего не пропадёт.",
                            fontSize = 13.sp,
                            color = LC.Dim,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            // Карточка с формой
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(LC.GlassStrong)
                    .border(1.dp, LC.Border, RoundedCornerShape(28.dp))
                    .padding(20.dp),
            ) {
                AnimatedContent(
                    step,
                    transitionSpec = {
                        val fwd = order(targetState) >= order(initialState)
                        (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { if (fwd) it / 4 else -it / 4 } + fadeIn(tween(220, 60))) togetherWith
                            (slideOutHorizontally(tween(200)) { if (fwd) -it / 6 else it / 6 } + fadeOut(tween(140)))
                    },
                    label = "authStep",
                ) { s ->
                    Column {
                        when (s) {
                            "code" -> CodeStep(vm, st.pendingEmail.orEmpty(), st.busy, accent)
                            "newpass" -> NewPassStep(vm, st.busy, accent)
                            AuthMode.Forgot.name -> ForgotStep(vm, email, { email = it }, st.busy, accent) { mode = AuthMode.SignIn }
                            else -> {
                                Tabs(mode == AuthMode.Register, accent) { reg ->
                                    mode = if (reg) AuthMode.Register else AuthMode.SignIn
                                }
                                Spacer(Modifier.height(18.dp))
                                CredForm(
                                    vm = vm,
                                    register = mode == AuthMode.Register,
                                    email = email,
                                    onEmail = { email = it },
                                    pass = pass,
                                    onPass = { pass = it },
                                    busy = st.busy,
                                    accent = accent,
                                    onForgot = { mode = AuthMode.Forgot },
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(st.message != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    var kept by remember { mutableStateOf("") }
                    st.message?.let { kept = it }
                    Text(
                        kept,
                        fontSize = 13.sp,
                        color = if (st.error) Color(0xFFFF8A8A) else accent,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Аккаунт общий с Lumen на компьютере. Мы храним только почту и твою библиотеку — без рекламы и слежки.",
                fontSize = 12.sp,
                color = LC.Faint,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun order(s: String) = when (s) {
    AuthMode.SignIn.name, AuthMode.Register.name -> 0
    AuthMode.Forgot.name -> 1
    "code" -> 2
    else -> 3
}

/** Мягкие цветные пятна, медленно плывущие за формой. */
@Composable
private fun AuthBackdrop(accent: Color) {
    val t by rememberInfiniteTransition(label = "authBg").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "authBgT",
    )
    val second = Color(0xFF7B5CFF)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun blob(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        blob(accent.copy(alpha = 0.42f), w * (0.15f + 0.25f * t), h * (0.12f + 0.06f * t), w * 0.85f)
        blob(second.copy(alpha = 0.32f), w * (0.95f - 0.3f * t), h * (0.42f + 0.1f * t), w * 0.8f)
        blob(accent.copy(alpha = 0.18f), w * (0.3f + 0.4f * t), h * (0.95f - 0.08f * t), w * 0.9f)
    }
}

@Composable
private fun Logo(accent: Color) {
    val pulse by rememberInfiniteTransition(label = "logo").animateFloat(
        1f,
        1.06f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "logoPulse",
    )
    Box(
        Modifier
            .size(84.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(accent, Color(0xFFFF5F7E)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LIcons.Note, null, Modifier.size(42.dp), tint = Color.White)
    }
}

/** Переключатель «Вход / Регистрация» с плавающей подложкой. */
@Composable
private fun Tabs(register: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LC.ink(0x14)),
    ) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (register) half else 0.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "tab")
        Box(
            Modifier
                .offset(x = x)
                .width(half)
                .height(46.dp)
                .padding(4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent),
        )
        Row(Modifier.fillMaxSize()) {
            listOf("Вход" to false, "Регистрация" to true).forEach { (label, reg) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onChange(reg) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (reg == register) LC.OnAccent else LC.Dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    icon: ImageVector,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    onIme: () -> Unit = {},
) {
    var show by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LC.ink(0x10))
            .border(1.dp, LC.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = LC.Dim)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, fontSize = 16.sp, color = LC.Faint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = LC.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(LocalAccent.current),
                visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime),
                keyboardActions = KeyboardActions(onAny = { onIme() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (password) {
            Icon(
                if (show) LIcons.EyeOff else LIcons.Eye,
                if (show) "Скрыть пароль" else "Показать пароль",
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { show = !show },
                tint = LC.Dim,
            )
        }
    }
}

@Composable
private fun MainButton(text: String, busy: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) accent else accent.copy(alpha = 0.35f))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(busy, label = "btn") { b ->
            if (b) CircularProgressIndicator(Modifier.size(24.dp), color = LC.OnAccent, strokeWidth = 2.5.dp)
            else Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LC.OnAccent)
        }
    }
}

private fun validEmail(e: String) = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$").matches(e.trim())

@Composable
private fun CredForm(
    vm: MainViewModel,
    register: Boolean,
    email: String,
    onEmail: (String) -> Unit,
    pass: String,
    onPass: (String) -> Unit,
    busy: Boolean,
    accent: Color,
    onForgot: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val ok = validEmail(email) && pass.length >= 6
    val submit = {
        focus.clearFocus()
        if (ok) if (register) vm.authRegister(email.trim(), pass) else vm.authSignIn(email.trim(), pass)
    }
    Text(
        if (register) "Создай аккаунт — это займёт минуту" else "С возвращением!",
        fontSize = 20.sp,
        fontWeight = FontWeight.ExtraBold,
    )
    Spacer(Modifier.height(10.dp))
    AuthField(email, { onEmail(it.trim()) }, "Почта", LIcons.Mail, keyboard = KeyboardType.Email)
    AuthField(
        pass,
        onPass,
        if (register) "Пароль — от 6 символов" else "Пароль",
        LIcons.Lock,
        password = true,
        ime = ImeAction.Done,
        onIme = { submit() },
    )
    MainButton(if (register) "Зарегистрироваться" else "Войти", busy, ok, accent) { submit() }
    if (!register) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Забыл пароль?",
            fontSize = 14.sp,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onForgot)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        }
    } else {
        Text(
            "Пришлём письмо с кодом — так мы убедимся, что почта твоя.",
            fontSize = 12.sp,
            color = LC.Faint,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun CodeStep(vm: MainViewModel, email: String, busy: Boolean, accent: Color) {
    var code by rememberSaveable { mutableStateOf("") }
    var wait by remember { mutableIntStateOf(60) }
    LaunchedEffect(wait) {
        if (wait > 0) {
            delay(1000)
            wait--
        }
    }
    // Шесть цифр набраны — проверяем сразу, без лишней кнопки.
    LaunchedEffect(code) { if (code.length == 6 && !busy) vm.authVerify(email, code) }

    Box(
        Modifier.size(56.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(LIcons.Mail, null, Modifier.size(28.dp), tint = accent) }
    Spacer(Modifier.height(12.dp))
    Text("Проверь почту", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Мы отправили письмо на $email. Введи код из него или нажми кнопку в письме на этом телефоне — Lumen откроется сам.",
        fontSize = 14.sp,
        color = LC.Dim,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(16.dp))
    CodeBoxes(code, accent) { code = it.filter(Char::isDigit).take(6) }
    MainButton("Подтвердить", busy, code.length == 6, accent) { vm.authVerify(email, code) }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "Изменить почту",
            fontSize = 14.sp,
            color = LC.Dim,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.authCancelPending() }.padding(6.dp),
        )
        Text(
            if (wait > 0) "Ещё раз через $wait с" else "Отправить ещё раз",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (wait > 0) LC.Faint else accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = wait == 0 && !busy) { vm.authResend(email); wait = 60 }
                .padding(6.dp),
        )
    }
    Text("Нет письма? Загляни в «Спам» и «Промоакции».", fontSize = 12.sp, color = LC.Faint, modifier = Modifier.padding(top = 4.dp))
}

/** Шесть ячеек под код; под ними — обычное поле ввода цифр. */
@Composable
private fun CodeBoxes(code: String, accent: Color, onChange: (String) -> Unit) {
    BasicTextField(
        value = code,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        val ch = code.getOrNull(i)
                        val active = i == code.length
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(LC.ink(0x10))
                                .border(if (active) 2.dp else 1.dp, if (active) accent else LC.Border, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(ch?.toString() ?: "", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
                Box(Modifier.matchParentSize()) { inner() }
            }
        },
    )
}

@Composable
private fun ForgotStep(vm: MainViewModel, email: String, onEmail: (String) -> Unit, busy: Boolean, accent: Color, onBack: () -> Unit) {
    Text("Новый пароль", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Пришлём письмо со ссылкой. Открой её на этом телефоне — Lumen предложит задать новый пароль.",
        fontSize = 14.sp,
        color = LC.Dim,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(12.dp))
    AuthField(email, { onEmail(it.trim()) }, "Почта", LIcons.Mail, keyboard = KeyboardType.Email, ime = ImeAction.Done, onIme = {
        if (validEmail(email)) vm.authRecover(email)
    })
    MainButton("Отправить письмо", busy, validEmail(email), accent) { vm.authRecover(email) }
    Text(
        "← Назад ко входу",
        fontSize = 14.sp,
        color = LC.Dim,
        modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(6.dp),
    )
}

@Composable
private fun NewPassStep(vm: MainViewModel, busy: Boolean, accent: Color) {
    var p by rememberSaveable { mutableStateOf("") }
    Text("Придумай новый пароль", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(4.dp))
    Text("Ты вошёл по ссылке из письма. Осталось задать пароль для следующих входов.", fontSize = 14.sp, color = LC.Dim, lineHeight = 19.sp)
    Spacer(Modifier.height(12.dp))
    AuthField(p, { p = it }, "Новый пароль — от 6 символов", LIcons.Lock, password = true, ime = ImeAction.Done, onIme = {
        if (p.length >= 6) vm.authSetPassword(p)
    })
    MainButton("Сохранить пароль", busy, p.length >= 6, accent) { vm.authSetPassword(p) }
}

package store.vpn312.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent { AppTheme { Root(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        ConfigShare.cleanup(this)
        vm.refreshConfig()
        if (vm.ui.value.loggedIn) vm.refresh()
    }

    /** vpn312://login?t=<токен> — ссылка «войти в приложение» из бота. */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "vpn312" && data.host == "login") {
            data.getQueryParameter("t")?.let { vm.openLink(it) }
        }
    }
}

// ═══════════════════════ тема ═══════════════════════

private val Brand = Color(0xFF2F5BEA)
private val Good = Color(0xFF1E9E5A)
private val Warn = Color(0xFFE0A100)
private val Bad = Color(0xFFD64545)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF7EA2FF), onPrimary = Color(0xFF0B1B4D),
        background = Color(0xFF0F1420), surface = Color(0xFF161C2B), surfaceVariant = Color(0xFF1E2638),
    ) else lightColorScheme(
        primary = Brand, onPrimary = Color.White,
        background = Color(0xFFF5F8FF), surface = Color.White, surfaceVariant = Color(0xFFEAF0FE),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun healthColor(h: String) = when (h) {
    "ok" -> Good; "slow" -> Warn; "flaky" -> Color(0xFFE07B00); "down" -> Bad; else -> Color.Gray
}

private fun healthText(h: String) = when (h) {
    "ok" -> "работает"; "slow" -> "медленно"; "flaky" -> "с перебоями"; "down" -> "не работает"; else -> "нет данных"
}

// ═══════════════════════ корень ═══════════════════════

enum class Tab(val title: String) { HOME("Главная"), DEVICES("Устройства"), PAY("Оплата"), SETTINGS("Настройки") }

@Composable
fun Root(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snack.showSnackbar(it); vm.toast(null) }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                ui.config.updateRequired -> UpdateRequiredScreen(vm, ui)
                !ui.loggedIn -> LoginScreen(vm, ui)
                ui.needPassword -> SetPasswordScreen(vm, ui)
                else -> MainScreen(vm, ui)
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (ui.pendingLink != null) {
        AlertDialog(
            onDismissRequest = { vm.confirmLink(false) },
            title = { Text("Войти по ссылке из бота?") },
            text = {
                Text(
                    "Ссылка откроет аккаунт того Telegram, в котором её получили. " +
                        (if (ui.loggedIn) "Сейчас открыт аккаунт «${ui.me?.display ?: "—"}» — если ссылка от другого " +
                            "аккаунта, VPN на этом телефоне отключится. " else "") +
                        "Если вы не нажимали «Войти в приложение» в боте сами — нажмите «Отмена»."
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmLink(true) }) { Text("Войти") } },
            dismissButton = { TextButton(onClick = { vm.confirmLink(false) }) { Text("Отмена") } },
        )
    }
}

// ═══════════════════════ вход ═══════════════════════

@Composable
fun LoginScreen(vm: AppViewModel, ui: UiState) {
    val busy = ui.busy
    val cfg = ui.config
    var register by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var linkDialog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Brand), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("VPN312", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Ваш VPN — в одно касание", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        if (cfg.feature("register")) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
            listOf(false to "Вход", true to "Регистрация").forEach { (r, t) ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (register == r) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { register = r }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(t, fontWeight = if (register == r) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(login, { login = it }, label = { Text("Логин") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (register) vm.register(login, pass) else vm.login(login, pass) },
            enabled = !busy && login.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        ) { Text(if (register) "Создать аккаунт" else "Войти", fontSize = 16.sp) }
        Spacer(Modifier.height(12.dp))
        Text(
            if (register) cfg.text("register_hint", "Аккаунт общий с сайтом vpn312.store.")
            else cfg.text("login_hint", "Логин и пароль — те же, что на сайте vpn312.store."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (cfg.feature("link_login")) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(cfg.text("tg_title", "Пользовались только Telegram-ботом?"), fontWeight = FontWeight.SemiBold)
            Text(
                cfg.text("tg_hint", "В боте: «📅 Моя подписка» → «🌐 Привязать к веб-версии». Скопируйте ссылку и вставьте сюда."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            TextButton(onClick = { linkDialog = true }) { Text("Войти по ссылке из бота") }
        }
        cfg.announcement?.let { Spacer(Modifier.height(12.dp)); AnnouncementCard(it, null) }
    }
    if (linkDialog) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("Ссылка из бота") },
            text = {
                OutlinedTextField(link, { link = it }, label = { Text("https://vpn312.store/auth/tg/…") },
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = link.isNotBlank(), onClick = { linkDialog = false; vm.loginByLink(link) }) { Text("Войти") }
            },
            dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun SetPasswordScreen(vm: AppViewModel, ui: UiState) {
    var login by rememberSaveable { mutableStateOf(ui.me?.display?.filter { it.isLetterOrDigit() || it in "_.-" } ?: "") }
    var pass by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Задайте логин и пароль", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Вы вошли по ссылке из Telegram. Чтобы входить в приложение и на сайт, даже если Telegram " +
                "не откроется, придумайте логин и пароль.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(login, { login = it }, label = { Text("Логин (латиница, цифры)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Пароль, от 6 символов") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.setPassword(login, pass) }, enabled = !ui.busy && login.length >= 3 && pass.length >= 6,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Сохранить") }
        TextButton(onClick = { vm.skipPassword() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Позже") }
    }
}

// ═══════════════════════ основной экран ═══════════════════════

@Composable
fun MainScreen(vm: AppViewModel, ui: UiState) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.HOME -> HomeScreen(vm, ui) { tab = it }
                Tab.DEVICES -> DevicesScreen(vm, ui)
                Tab.PAY -> PayScreen(vm, ui)
                Tab.SETTINGS -> SettingsScreen(vm, ui)
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t, onClick = { tab = t },
                    icon = {
                        Icon(when (t) {
                            Tab.HOME -> Icons.Filled.Home; Tab.DEVICES -> Icons.AutoMirrored.Filled.List
                            Tab.PAY -> Icons.Filled.ShoppingCart; Tab.SETTINGS -> Icons.Filled.Settings
                        }, null)
                    },
                    label = { Text(t.title) },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

private fun daysLeft(sec: Long) = (sec + 86399) / 86400

private fun fmtDate(iso: String?): String =
    iso?.take(10)?.split("-")?.let { if (it.size == 3) "${it[2]}.${it[1]}.${it[0]}" else iso } ?: "—"

@Composable
fun HomeScreen(vm: AppViewModel, ui: UiState, go: (Tab) -> Unit) {
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val me = ui.me
    val sub = me?.subscription
    val device = me?.devices?.firstOrNull { it.id == ui.selectedId }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val vpnPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.connect() else vm.toast("Без разрешения Android VPN не включить")
    }
    fun connect() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        val prep = VpnService.prepare(ctx)
        if (prep != null) vpnPerm.launch(prep) else vm.connect()
    }
    var confirmMove by remember { mutableStateOf<Region?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.config.updateAvailable) UpdateCard(vm, ui)
        ui.config.announcement?.takeIf { it.id != ui.dismissedAnnouncement }?.let { a ->
            AnnouncementCard(a) { vm.dismissAnnouncement(a.id) }
        }
        // подписка
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(me?.display ?: "VPN312", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    val line = when {
                        sub == null -> "Подписки пока нет"
                        !sub.active -> "Подписка закончилась ${fmtDate(sub.expiresAtMsk)}"
                        sub.isTrial -> "Тест до ${fmtDate(sub.expiresAtMsk)} · осталось ${daysLeft(sub.secondsLeft)} дн."
                        else -> "До ${fmtDate(sub.expiresAtMsk)} · осталось ${daysLeft(sub.secondsLeft)} дн."
                    }
                    Text(line, color = if (sub?.active == true) MaterialTheme.colorScheme.onSurfaceVariant else Bad, fontSize = 14.sp)
                }
                if (sub != null) {
                    Text(
                        if (sub.tier == "platinum") "Platinum" else "Premium",
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sub.tier == "platinum") Color(0xFF2B2B3A) else Brand.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        color = if (sub.tier == "platinum") Color(0xFFE8D9A8) else Brand, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (sub == null || !sub.active || daysLeft(sub.secondsLeft) <= 3) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { go(Tab.PAY) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (sub?.active == true) "Продлить" else "Оплатить подписку")
                }
            }
        }

        // кнопка подключения
        val on = vpn.phase == VpnStatus.Phase.ON
        val working = vpn.phase in setOf(VpnStatus.Phase.CONNECTING, VpnStatus.Phase.SWITCHING, VpnStatus.Phase.CHECKING)
        val color by animateColorAsState(when {
            on -> Good; working -> Warn; else -> Brand
        }, label = "btn")
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(172.dp).clip(CircleShape).background(color.copy(alpha = 0.14f))
                    .clickable(enabled = device != null || vpn.phase != VpnStatus.Phase.OFF) {
                        if (vpn.phase == VpnStatus.Phase.OFF || vpn.phase == VpnStatus.Phase.ERROR) connect() else vm.disconnect()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(128.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Text(
                        when { on -> "ВКЛ"; working -> "…"; else -> "ВЫКЛ" },
                        color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when (vpn.phase) {
                    VpnStatus.Phase.ON -> "Защищено · ${Regions.flag(vpn.region)} ${Regions.title(vpn.region)}"
                    VpnStatus.Phase.CONNECTING -> "Подключение…"
                    VpnStatus.Phase.SWITCHING -> "Смена сервера…"
                    VpnStatus.Phase.CHECKING -> "Проверка связи…"
                    VpnStatus.Phase.ERROR -> "Ошибка"
                    VpnStatus.Phase.OFF -> if (device == null) "Выберите устройство" else "VPN выключен"
                },
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            val details = vpn.note ?: listOfNotNull(
                vpn.latencyMs?.takeIf { on }?.let { "отклик $it мс" },
                vpn.speedMbps?.takeIf { on }?.let { "%.1f Мбит/с".format(it) },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                textAlign = TextAlign.Center)
        }

        // устройство
        SectionCard(Modifier.clickable { go(Tab.DEVICES) }) {
            Text("Устройство на этом телефоне", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (device != null) {
                Text("${device.number}. ${device.name}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("${Regions.flag(device.region)} ${Regions.title(device.region)}", fontSize = 14.sp)
            } else {
                Text("Не выбрано — нажмите, чтобы выбрать или создать", color = Brand)
            }
        }

        // серверы и автосмена
        if (me != null && device != null) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Автосмена сервера", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (me.autoSwitchAllowed) ui.config.text("auto_switch_hint", "Сервер не отвечает или медленный — приложение само перенесёт устройство на рабочий")
                            else "На тестовом периоде сервер не меняется",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ui.autoSwitch && me.autoSwitchAllowed, enabled = me.autoSwitchAllowed,
                        onCheckedChange = { vm.setAutoSwitch(it) })
                }
                Spacer(Modifier.height(12.dp))
                Text("Серверы", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                me.regions.forEach { r ->
                    val current = r.code == device.region
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !current && me.autoSwitchAllowed && !ui.busy) { confirmMove = r }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.flag, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title + if (current) " · сейчас" else "",
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                            Text(healthText(r.health) + (r.speedMbps?.let { " · %.0f Мбит/с".format(it) } ?: ""),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(healthColor(r.health)))
                    }
                }
                ui.lastEvent?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    confirmMove?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmMove = null },
            title = { Text("Перейти на ${r.title}?") },
            text = {
                Text("«${device?.name}»: " + ui.config.text("move_warning", "устройство переедет на этот сервер."))
            },
            confirmButton = { TextButton(onClick = { vm.moveTo(r.code); confirmMove = null }) { Text("Перейти") } },
            dismissButton = { TextButton(onClick = { confirmMove = null }) { Text("Отмена") } },
        )
    }
}

// ═══════════════════════ устройства ═══════════════════════

@Composable
fun DevicesScreen(vm: AppViewModel, ui: UiState) {
    val me = ui.me ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var add by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Device?>(null) }
    var del by remember { mutableStateOf<Device?>(null) }
    var shareFor by remember { mutableStateOf<Pair<Device, String>?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Устройства", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "${me.devices.size} из ${me.subscription?.maxDevices ?: 5}. Выберите, какое из них работает на этом телефоне.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            )
        }
        items(me.devices, key = { it.id }) { d ->
            val here = d.id == ui.selectedId
            var menu by remember { mutableStateOf(false) }
            Card(
                Modifier.fillMaxWidth().then(if (here) Modifier.border(2.dp, Brand, RoundedCornerShape(18.dp)) else Modifier),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${d.number}. ${d.name}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${Regions.flag(d.region)} ${Regions.title(d.region)}" +
                                (if (!d.regionLive) " · регион выведен" else "") +
                                (if (here) " · на этом телефоне" else ""),
                            fontSize = 13.sp, color = if (here) Brand else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!here && d.regionLive) {
                        TextButton(onClick = { vm.selectDevice(d) }, enabled = !ui.busy) { Text("Использовать") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = d })
                            if (d.regionLive) DropdownMenuItem(text = { Text("Конфиг для другого устройства") }, onClick = {
                                menu = false
                                vm.fetchConfig(d) { conf -> shareFor = d to conf }
                            })
                            DropdownMenuItem(text = { Text("Удалить", color = Bad) }, onClick = { menu = false; del = d })
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(onClick = { add = true }, enabled = me.canAddDevice && !ui.busy,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить устройство")
            }
            me.canAddReason?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
    if (add) {
        var name by remember { mutableStateOf("${Build.MODEL}".take(40)) }
        var region by remember { mutableStateOf("auto") }
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("Новое устройство") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it.take(50) }, label = { Text("Название") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    (listOf("auto" to "Автовыбор — лучший сейчас") + me.regions.map { it.code to "${it.flag} ${it.title}" })
                        .forEach { (code, label) ->
                            Row(Modifier.fillMaxWidth().clickable { region = code }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(region == code, { region = code }); Text(label)
                            }
                        }
                }
            },
            confirmButton = { TextButton(onClick = { add = false; vm.addDevice(region, name) }) { Text("Создать") } },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Отмена") } },
        )
    }
    rename?.let { d ->
        var name by remember { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Название устройства") },
            text = { OutlinedTextField(name, { name = it.take(50) }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.renameDevice(d, name); rename = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Отмена") } },
        )
    }
    shareFor?.let { (d, conf) -> ConfigShareDialog(vm, d, conf, here = d.id == ui.selectedId) { shareFor = null } }
    del?.let { d ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("Удалить «${d.name}»?") },
            text = { Text("Конфиг перестанет работать на всех устройствах, где он стоит. Это нельзя отменить.") },
            confirmButton = { TextButton(onClick = { vm.deleteDevice(d); del = null }) { Text("Удалить", color = Bad) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ConfigShareDialog(vm: AppViewModel, d: Device, conf: String, here: Boolean, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val qr = remember(conf) { ConfigShare.qr(conf).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("«${d.name}» на другое устройство") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr, contentDescription = "QR-код конфига",
                    modifier = Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
                Spacer(Modifier.height(10.dp))
                Text("Телефон: в приложении WireGuard нажмите «+» → «Сканировать QR-код».\n" +
                    "Компьютер: «Файлом» и импорт файла в WireGuard.", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Не показывайте код посторонним — по нему подключаются под вашим устройством." +
                    if (here) " Этот конфиг стоит на этом телефоне: одновременно на двух устройствах он работать не будет." else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!ConfigShare.shareFile(ctx, d, conf)) {
                    ConfigShare.copy(ctx, conf)
                    vm.toast("Отправить некуда — конфиг скопирован в буфер обмена")
                }
            }) { Text("Файлом") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { ConfigShare.copy(ctx, conf); vm.toast("Конфиг скопирован") }) { Text("Скопировать") }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        },
    )
}

// ═══════════════════════ оплата ═══════════════════════

private fun monthsLabel(m: Int) = when (m) { 1 -> "1 месяц"; 3 -> "3 месяца"; 6 -> "6 месяцев"; else -> "$m месяцев" }

@Composable
fun PayScreen(vm: AppViewModel, ui: UiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.loadTariffs(); vm.refreshConfig() }
    val t = ui.tariffs
    val methods = ui.config.payMethods
    var tier by rememberSaveable { mutableStateOf(ui.me?.subscription?.tier ?: "premium") }
    var months by rememberSaveable { mutableStateOf(1) }
    val open: (String) -> Unit = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    fun pay(m: PayMethod, kind: String) {
        when (m.type) {
            "sbp" -> if (kind == "upgrade") vm.payUpgrade(open) else vm.paySbp(months, tier, open)
            "crypto" -> m.coin?.let { vm.cryptoQuote(it, kind, months, tier) }
            "web" -> vm.payOnSite(if (kind == "upgrade") "upgrade" else (m.to ?: "pay"), open)
            "url" -> m.link?.let(open)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Оплата", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (t == null) {
            Text("Загружаю тарифы…", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column
        }
        t.discountPercent?.let {
            SectionCard {
                Text("Скидка $it%", fontWeight = FontWeight.Bold, color = Good, fontSize = 18.sp)
                t.discountTitle?.let { s -> Text(s, fontSize = 14.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
            listOf("premium" to "Premium · 5 устройств", "platinum" to "Platinum · 10 устройств").forEach { (k, label) ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (tier == k) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { tier = k }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 13.sp, fontWeight = if (tier == k) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        (if (tier == "platinum") t.platinum else t.premium).forEach { x ->
            val sel = x.months == months
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(if (sel) 2.dp else 1.dp, if (sel) Brand else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .clickable { months = x.months }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(monthsLabel(x.months), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (x.price != x.priceBase) {
                    Text("${x.priceBase} ₽", textDecoration = TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("${x.price} ₽", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        Text("Способ оплаты", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (methods.isEmpty()) Text("Оплата временно недоступна — напишите в поддержку.", color = Bad)
        methods.forEach { m -> MethodButton(m, ui.busy) { pay(m, "renew") } }
        methods.firstOrNull { it.type == "sbp" }?.let {
            Text(ui.config.text("sbp_hint", "Откроется страница оплаты. После оплаты вернитесь в приложение."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (t.upgradePrice != null) {
            SectionCard {
                Text("Перейти на Platinum", fontWeight = FontWeight.SemiBold)
                Text("10 устройств вместо 5. Срок подписки не меняется, доплата ${t.upgradePrice} ₽.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                methods.filter { it.type != "url" }.forEach { m ->
                    OutlinedButton(onClick = { pay(m, "upgrade") }, enabled = !ui.busy, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) { Text("${m.title} · ${t.upgradePrice} ₽") }
                }
            }
        }
    }
    ui.crypto?.let { CryptoDialog(vm, ui, it) }
}

@Composable
private fun MethodButton(m: PayMethod, busy: Boolean, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (m.type == "sbp") Brand else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (m.type == "sbp") Brand else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (m.type == "sbp") Color.White else MaterialTheme.colorScheme.onSurface
        Column(Modifier.weight(1f)) {
            Text(m.title, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            m.subtitle?.let { Text(it, color = fg.copy(alpha = 0.75f), fontSize = 13.sp) }
        }
        if (m.link != null && m.linkTitle != null && m.type != "url") {
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.link))) } }) {
                Text(m.linkTitle, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun CryptoDialog(vm: AppViewModel, ui: UiState, q: CryptoQuote) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var txid by remember { mutableStateOf("") }
    fun copy(label: String, v: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, v))
        vm.toast("$label скопирован")
    }
    val coinName = if (q.coin == "btc") "BTC" else "LTC"
    AlertDialog(
        onDismissRequest = { vm.cryptoClose() },
        title = { Text(if (q.kind == "upgrade") "Доплата за Platinum · $coinName" else "Оплата $coinName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сумма (${q.priceRub} ₽)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${q.amount} $coinName", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copy("Сумма", q.amount) }) { Text("Копировать") }
                }
                Text("Адрес", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q.address, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row {
                    TextButton(onClick = { copy("Адрес", q.address) }) { Text("Копировать адрес") }
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q.uri))) }
                            .onFailure { vm.toast("Кошелёк на телефоне не найден — скопируйте адрес") }
                    }) { Text("Открыть кошелёк") }
                }
                Text(ui.config.text("crypto_hint", "Когда у перевода будет 2 подтверждения, вставьте TXID."),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(txid, { txid = it.trim() }, label = { Text("TXID перевода") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                ui.cryptoError?.let { Text(it, color = Bad, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = txid.length >= 10 && !ui.busy, onClick = { vm.cryptoCheck(txid) }) { Text("Проверить оплату") }
        },
        dismissButton = { TextButton(onClick = { vm.cryptoClose() }) { Text("Закрыть") } },
    )
}

// ═══════════════════════ объявление и обновление ═══════════════════════

@Composable
fun AnnouncementCard(a: Announcement, onDismiss: (() -> Unit)?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val color = when (a.level) { "error" -> Bad; "warn" -> Warn; else -> Brand }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Скрыть", fontSize = 13.sp) }
            }
            if (a.text.isNotEmpty()) Text(a.text, fontSize = 14.sp)
            if (a.link != null) TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) }
            }) { Text(a.linkTitle ?: "Подробнее") }
        }
    }
}

@Composable
fun UpdateCard(vm: AppViewModel, ui: UiState) {
    val st by vm.update.collectAsStateWithLifecycle()
    val rel = ui.config.release
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Good.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Доступно обновление ${rel.latestName}", fontWeight = FontWeight.SemiBold)
            if (rel.notes.isNotEmpty()) Text(rel.notes, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            when (val s = st) {
                is Updater.State.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Скачиваю… ${(s.progress * 100).toInt()}%", fontSize = 13.sp)
                }
                Updater.State.Installing -> Text("Устанавливаю…", fontSize = 13.sp)
                else -> {
                    if (s is Updater.State.Failed) Text(s.message, color = Bad, fontSize = 13.sp)
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                            vm.toast("Разрешите VPN312 устанавливать приложения и нажмите «Обновить» ещё раз")
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${ctx.packageName}")))
                        } else vm.startUpdate()
                    }, shape = RoundedCornerShape(12.dp)) { Text("Обновить") }
                }
            }
        }
    }
}

@Composable
fun UpdateRequiredScreen(vm: AppViewModel, ui: UiState) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Нужно обновить приложение", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Эта версия больше не поддерживается. Обновление скачается и установится отсюда же.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (ui.config.release.apkUrl != null) UpdateCard(vm, ui)
        else Text("Скачайте новую версию на сайте: ${ui.config.link("site", BuildConfig.SITE_URL)}")
    }
}

// ═══════════════════════ настройки ═══════════════════════

@Composable
fun SettingsScreen(vm: AppViewModel, ui: UiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pwDialog by remember { mutableStateOf(false) }
    var logSend by remember { mutableStateOf(false) }
    var logShow by remember { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }
    val me = ui.me
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SectionCard {
            Text("Аккаунт", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(me?.display ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Логин: ${me?.login ?: "не задан"}" + (me?.telegram?.let { " · Telegram: $it" } ?: ""), fontSize = 14.sp)
            if (me?.hasPassword == false) {
                Text("Без логина и пароля войти можно только через Telegram-бота.", fontSize = 13.sp, color = Bad)
                TextButton(onClick = { vm.askPassword() }) { Text("Задать логин и пароль") }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Включать VPN при запуске телефона", fontWeight = FontWeight.SemiBold)
                    Text("Если перед выключением VPN был включён", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(ui.connectOnBoot, { vm.setConnectOnBoot(it) })
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) {
                Text("Постоянный VPN — настройки Android")
            }
            Text("Включите там для VPN312 «Постоянную VPN» и «Блокировать соединения без VPN»: Android сам " +
                "будет держать подключение, и ни один запрос не уйдёт мимо VPN — даже в доли секунды, " +
                "пока приложение переключает сервер.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard {
            if (me?.hasPassword == true) TextButton(onClick = { pwDialog = true }) { Text("Сменить пароль") }
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(ui.config.link("support", BuildConfig.SUPPORT_URL)))) } }) { Text("Поддержка") }
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(ui.config.link("site", BuildConfig.SITE_URL)))) } }) { Text("Сайт") }
            TextButton(onClick = { logoutDialog = true }) { Text("Выйти из аккаунта", color = Bad) }
        }
        SectionCard {
            Text("Журнал ошибок", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Если что-то не работает — отправьте журнал в поддержку и назовите номер отчёта. " +
                "Ключи, пароли и сайты, которые вы открываете, в журнал не попадают.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Отправлять отчёт о сбое автоматически", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("Если приложение упадёт, при следующем запуске журнал уйдёт в поддержку сам",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(ui.crashReports, { vm.setCrashReports(it) })
            }
            Row {
                TextButton(onClick = { logSend = true }, enabled = !ui.busy) { Text("Отправить") }
                TextButton(onClick = { logShow = true }) { Text("Показать") }
                TextButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Журнал VPN312 ${BuildConfig.VERSION_NAME}")
                        putExtra(Intent.EXTRA_TEXT, AppLog.tail(60_000))
                    }, "Журнал VPN312"))
                }) { Text("Поделиться") }
            }
        }
        if (ui.config.help.isNotEmpty()) SectionCard {
            Text("Помощь", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            ui.config.help.forEach { (q, a) ->
                var open by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp)) {
                    Text((if (open) "▾ " else "▸ ") + q, fontWeight = FontWeight.Medium)
                    if (open) Text(a, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ui.config.updateAvailable) UpdateCard(vm, ui)
        TextButton(onClick = { vm.refreshConfig(force = true); vm.toast("Проверяю обновления…") },
            modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Проверить обновления") }
        Text("VPN312 для Android · версия ${BuildConfig.VERSION_NAME}", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                // только отладочная сборка: долгое нажатие роняет приложение — проверка отчёта о падении
                detectTapGestures(onLongPress = { if (BuildConfig.DEBUG) throw RuntimeException("Тестовое падение (отладка)") })
            })
    }
    if (pwDialog) {
        var old by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwDialog = false },
            title = { Text("Смена пароля") },
            text = {
                Column {
                    OutlinedTextField(old, { old = it }, label = { Text("Старый пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(new, { new = it }, label = { Text("Новый, от 6 символов") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = old.isNotEmpty() && new.length >= 6, onClick = { vm.changePassword(old, new); pwDialog = false }) { Text("Сменить") }
            },
            dismissButton = { TextButton(onClick = { pwDialog = false }) { Text("Отмена") } },
        )
    }
    if (logSend) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { logSend = false },
            title = { Text("Отправить журнал") },
            text = {
                Column {
                    Text("Опишите в двух словах, что случилось и когда — так поддержке будет проще.", fontSize = 14.sp)
                    OutlinedTextField(note, { note = it.take(1000) }, label = { Text("Что случилось") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.sendLog(note); logSend = false }) { Text("Отправить") } },
            dismissButton = { TextButton(onClick = { logSend = false }) { Text("Отмена") } },
        )
    }
    if (logShow) {
        val text = remember { AppLog.tail(30_000).ifEmpty { "Журнал пуст" } }
        AlertDialog(
            onDismissRequest = { logShow = false },
            title = { Text("Журнал") },
            text = {
                Text(text, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState(Int.MAX_VALUE)))
            },
            confirmButton = { TextButton(onClick = { logShow = false }) { Text("Закрыть") } },
            dismissButton = { TextButton(onClick = { AppLog.clear(); logShow = false; vm.toast("Журнал очищен") }) { Text("Очистить") } },
        )
    }
    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text("Выйти?") },
            text = { Text("VPN на этом телефоне отключится. Устройства в аккаунте останутся.") },
            confirmButton = { TextButton(onClick = { vm.logout(); logoutDialog = false }) { Text("Выйти", color = Bad) } },
            dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("Отмена") } },
        )
    }
}

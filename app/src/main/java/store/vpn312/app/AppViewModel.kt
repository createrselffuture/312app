package store.vpn312.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Tariff(val months: Int, val price: Int, val priceBase: Int, val discount: Int?)

data class Tariffs(
    val premium: List<Tariff>, val platinum: List<Tariff>, val discountTitle: String?,
    val discountPercent: Int?, val currentTier: String, val upgradePrice: Int?, val upgradeWhy: String?,
)

data class CryptoQuote(
    val coin: String, val kind: String, val address: String, val amount: String, val uri: String,
    val priceRub: Int, val quote: String,
)

data class UiState(
    val loggedIn: Boolean = false,
    val me: Me? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val tariffs: Tariffs? = null,
    val needPassword: Boolean = false,
    val passwordSkipped: Boolean = false,
    val selectedId: Long = -1,
    val autoSwitch: Boolean = true,
    val connectOnBoot: Boolean = false,
    val lastEvent: String? = null,
    val config: RemoteConfig = RemoteConfig.DEFAULT,
    val dismissedAnnouncement: String? = null,
    val crypto: CryptoQuote? = null,
    val crashReports: Boolean = true,
    val cryptoError: String? = null,
    /** Ссылка входа из бота, пришедшая, когда уже открыт аккаунт: ждём подтверждения. */
    val pendingLink: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val prefs = app.prefs
    private val api = app.api

    private val _ui = MutableStateFlow(
        UiState(
            loggedIn = prefs.token != null,
            me = prefs.meCache?.let { runCatching { Me.parse(JSONObject(it)) }.getOrNull() },
            selectedId = prefs.deviceId,
            autoSwitch = prefs.autoSwitch,
            connectOnBoot = prefs.connectOnBoot,
            lastEvent = prefs.lastEvent,
            config = app.config,
            dismissedAnnouncement = prefs.dismissedAnnouncement,
            crashReports = prefs.crashReports,
        )
    )
    val ui: StateFlow<UiState> = _ui
    val vpn = VpnStatus.flow

    init {
        refreshConfig(force = true)
        if (prefs.token != null) refresh()
        // прошлый запуск упал — отчёт уходит сам, если не выключено в настройках приложения
        // (или сервером: features.crash_reports)
        if (AppLog.hasPendingCrash()) viewModelScope.launch {
            if (prefs.crashReports && app.config.feature("crash_reports")) AppLog.send("crash") else AppLog.clearPendingCrash()
        }
        // сервис сам перенёс устройство на другой сервер — обновляем экран из его ответа
        viewModelScope.launch {
            VpnStatus.flow.collect { st ->
                val dev = _ui.value.me?.devices?.firstOrNull { it.id == prefs.deviceId }
                if (dev != null && st.region != null && st.region != dev.region) {
                    prefs.meCache?.let { c -> runCatching { applyMe(JSONObject(c)) } }
                }
                if (prefs.lastEvent != _ui.value.lastEvent) _ui.update { it.copy(lastEvent = prefs.lastEvent) }
            }
        }
    }

    fun toast(msg: String?) = _ui.update { it.copy(message = msg) }

    private fun applyMe(j: JSONObject) {
        prefs.meCache = j.toString()
        val me = Me.parse(j, _ui.value.me?.regions ?: emptyList())
        // устройство телефона удалили на сайте или в боте — забываем его
        if (prefs.deviceId > 0 && me.devices.none { it.id == prefs.deviceId }) {
            if (prefs.wantConnected) VpnControlService.disconnect(app)
            prefs.clearDevice()
        } else {
            me.devices.firstOrNull { it.id == prefs.deviceId }?.let { prefs.deviceName = it.name }
        }
        _ui.update {
            it.copy(me = me, loggedIn = true, selectedId = prefs.deviceId,
                needPassword = !me.hasPassword && !it.passwordSkipped, lastEvent = prefs.lastEvent)
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: ApiError) {
                AppLog.w("ui", "ошибка: ${e.status} ${e.code} ${e.message}")
                if (e.code == "unauthorized") forgetSession()
                toast(e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("ui", "сбой", e)
                toast(e.message ?: "Ошибка")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun forgetSession() {
        prefs.token = null
        _ui.update { it.copy(loggedIn = false) }
    }

    // ═══════════════════════ вход ═══════════════════════

    fun login(login: String, password: String) = launchBusy {
        val r = api.post("/auth/login", JSONObject().put("login", login.trim()).put("password", password), auth = false)
        prefs.token = r.getString("token")
        applyMe(r)
        afterLogin()
    }

    fun register(login: String, password: String) = launchBusy {
        val r = api.post("/auth/register", JSONObject().put("login", login.trim()).put("password", password), auth = false)
        prefs.token = r.getString("token")
        applyMe(r)
        toast("Аккаунт создан")
    }

    /**
     * Ссылка из бота (vpn312://login?t=…). Открыть её может любой сайт или приложение, поэтому
     * сначала всегда спрашиваем — иначе можно молча войти в чужой аккаунт.
     */
    fun openLink(link: String) {
        _ui.update { it.copy(pendingLink = link) }
    }

    fun confirmLink(ok: Boolean) {
        val link = _ui.value.pendingLink
        _ui.update { it.copy(pendingLink = null) }
        if (ok && link != null) loginByLink(link)
    }

    fun loginByLink(link: String) = launchBusy {
        val r = api.post("/auth/link", JSONObject().put("link", link.trim()), auth = false)
        val newId = r.getJSONObject("profile").getLong("id")
        val oldId = _ui.value.me?.profileId
        if (oldId != null && oldId != newId) {
            // другой аккаунт: VPN и устройство прежнего аккаунта здесь больше не наши
            AppLog.i("auth", "смена аккаунта по ссылке из бота")
            if (prefs.wantConnected) VpnControlService.disconnect(app)
            prefs.clearDevice()
            prefs.lastEvent = null
        }
        prefs.token = r.getString("token")
        applyMe(r)
        afterLogin()
    }

    fun setPassword(login: String, password: String) = launchBusy {
        val r = api.post("/auth/set_password", JSONObject().put("login", login.trim()).put("password", password))
        applyMe(r)
        toast("Готово: теперь входите по логину и паролю, Telegram не нужен")
    }

    fun skipPassword() = _ui.update { it.copy(needPassword = false, passwordSkipped = true) }

    fun askPassword() = _ui.update { it.copy(needPassword = true, passwordSkipped = false) }

    fun changePassword(old: String, new: String) = launchBusy {
        api.post("/auth/change_password", JSONObject().put("old_password", old).put("new_password", new))
        toast("Пароль изменён. Другие устройства вышли из аккаунта.")
    }

    fun logout() = launchBusy {
        if (prefs.wantConnected) VpnControlService.disconnect(app)
        runCatching { api.post("/auth/logout") }
        prefs.clearAll()
        _ui.value = UiState()
    }

    /** Одно устройство у аккаунта — сразу выбираем его для телефона. */
    private suspend fun afterLogin() {
        val me = _ui.value.me ?: return
        if (prefs.deviceId <= 0 && me.devices.count { it.regionLive } == 1) {
            selectDeviceNow(me.devices.first { it.regionLive })
        }
    }

    // ═══════════════════════ конфиг сервера ═══════════════════════

    private var lastConfigTry = 0L

    /** Свежий конфиг: способы оплаты, тексты, объявление, версия. Не чаще раза в 5 минут. */
    fun refreshConfig(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastConfigTry < 5 * 60_000L) return
        lastConfigTry = now
        viewModelScope.launch {
            RemoteConfig.refresh(app)?.let { c ->
                prefs.lastConfigFetch = now
                _ui.update { it.copy(config = c) }
            }
        }
    }

    fun dismissAnnouncement(id: String) {
        prefs.dismissedAnnouncement = id
        _ui.update { it.copy(dismissedAnnouncement = id) }
    }

    val update = Updater.state

    fun startUpdate() {
        val rel = _ui.value.config.release
        viewModelScope.launch { Updater.downloadAndInstall(app, rel) }
    }

    fun refresh() = viewModelScope.launch {
        val tokenAtStart = prefs.token
        try {
            val me = api.get("/me")
            // пока шёл запрос, вошли в другой аккаунт — этот ответ уже чужой
            if (prefs.token != tokenAtStart) return@launch
            applyMe(me)
            // устройство перенесли на другой сервер не отсюда (бот, сайт, другой телефон с тем же
            // конфигом) — берём новый конфиг, иначе туннель стучится в старый сервер
            val dev = _ui.value.me?.devices?.firstOrNull { it.id == prefs.deviceId }
            if (dev != null && dev.regionLive && prefs.deviceConfig != null && dev.region != prefs.deviceRegion) {
                prefs.deviceConfig = api.get("/devices/${dev.id}/config").getString("config")
                prefs.deviceRegion = dev.region
                if (prefs.wantConnected) VpnControlService.connect(app)
            }
        } catch (e: ApiError) {
            // «не авторизован» относится к старому токену, если за это время вошли заново
            if (e.code == "unauthorized" && prefs.token == tokenAtStart) forgetSession()
        } catch (e: Exception) {
            // без сети показываем то, что в кэше
        }
    }

    // ═══════════════════════ устройства ═══════════════════════

    fun selectDevice(dev: Device) = launchBusy { selectDeviceNow(dev) }

    private suspend fun selectDeviceNow(dev: Device) {
        val conf = api.get("/devices/${dev.id}/config").getString("config")
        val wasOn = prefs.wantConnected
        prefs.deviceId = dev.id
        prefs.deviceName = dev.name
        prefs.deviceRegion = dev.region
        prefs.deviceConfig = conf
        _ui.update { it.copy(selectedId = dev.id) }
        if (wasOn) VpnControlService.connect(app)
        toast("На этом телефоне: ${dev.name}")
    }

    fun addDevice(region: String, name: String) = launchBusy {
        val r = api.post("/devices", JSONObject().put("region", region).put("name", name.trim()))
        applyMe(r)
        val id = r.getLong("peer_id")
        if (prefs.deviceId <= 0) {
            _ui.value.me?.devices?.firstOrNull { it.id == id }?.let { selectDeviceNow(it) }
        } else {
            toast("Устройство добавлено")
        }
    }

    fun renameDevice(dev: Device, name: String) = launchBusy {
        applyMe(api.post("/devices/${dev.id}/rename", JSONObject().put("name", name.trim())))
    }

    fun deleteDevice(dev: Device) = launchBusy {
        if (dev.id == prefs.deviceId) {
            if (prefs.wantConnected) VpnControlService.disconnect(app)
            prefs.clearDevice()
        }
        applyMe(api.delete("/devices/${dev.id}"))
        toast("Устройство удалено")
    }

    /** Конфиг устройства для переноса на другое: с индикатором загрузки и текстом ошибки. */
    fun fetchConfig(dev: Device, onReady: (String) -> Unit) = launchBusy {
        val conf = api.get("/devices/${dev.id}/config").getString("config")
        AppLog.i("share", "конфиг устройства ${dev.id} загружен для переноса")
        withContext(Dispatchers.Main) { onReady(conf) }
    }

    suspend fun configFor(dev: Device): String? = try {
        api.get("/devices/${dev.id}/config").getString("config")
    } catch (e: ApiError) {
        toast(e.message); null
    }

    /** Ручная смена сервера для устройства этого телефона (тот же ключ). */
    fun moveTo(region: String) = launchBusy {
        val conf = prefs.deviceConfig ?: throw ApiError(0, "no_device", "Сначала выберите устройство")
        val priv = VpnControlService.privateKeyOf(conf) ?: throw ApiError(0, "bad_key", "Конфиг повреждён")
        val on = prefs.wantConnected
        // через сломанный туннель API может быть недоступен — сначала пробуем как есть
        val r = try {
            api.post("/devices/${prefs.deviceId}/move", JSONObject().put("region", region).put("private_key", priv)
                .put("reason", "manual"))
        } catch (e: ApiError) {
            if (e.code != "network" || !on) throw e
            // туннель не выключаем: к API — через сеть телефона мимо своего VPN,
            // а если её не нашлось — заглушка (трафик телефона мимо VPN не уходит)
            val net = DirectNet.find(app)
            if (net == null) withContext(Dispatchers.IO) { app.tunnels.blackhole(conf) }
            try {
                api.post("/devices/${prefs.deviceId}/move", JSONObject().put("region", region).put("private_key", priv)
                    .put("reason", "manual"), net = net)
            } catch (e2: Exception) {
                if (app.tunnels.isBlackhole) withContext(Dispatchers.IO) { runCatching { app.tunnels.up(conf) } }
                throw e2
            }
        }
        val newConf = r.optString("config").ifEmpty { null }
        if (newConf == null) {
            if (app.tunnels.isBlackhole) withContext(Dispatchers.IO) { runCatching { app.tunnels.up(conf) } }
            throw ApiError(0, "bad_response", "Сервер не прислал конфиг — попробуйте ещё раз")
        }
        prefs.deviceConfig = newConf
        prefs.deviceRegion = region
        // рабочий туннель на новый сервер поднимет сервис — и заглушку тоже снимет он
        if (on) VpnControlService.connect(app)
        applyMe(r)
        toast("Сервер: ${Regions.title(region)}")
    }

    fun setAutoSwitch(v: Boolean) {
        prefs.autoSwitch = v; _ui.update { it.copy(autoSwitch = v) }
    }

    fun setConnectOnBoot(v: Boolean) {
        prefs.connectOnBoot = v; _ui.update { it.copy(connectOnBoot = v) }
    }

    fun setCrashReports(v: Boolean) {
        prefs.crashReports = v; _ui.update { it.copy(crashReports = v) }
        if (!v) AppLog.clearPendingCrash()
    }

    fun connect() {
        if (prefs.deviceConfig == null) {
            toast("Выберите устройство для этого телефона"); return
        }
        VpnControlService.connect(app)
    }

    fun disconnect() = VpnControlService.disconnect(app)

    // ═══════════════════════ оплата ═══════════════════════

    fun loadTariffs() = launchBusy {
        val r = api.get("/pay/tariffs")
        fun list(k: String) = r.optJSONArray(k).objects().map {
            Tariff(it.getInt("months"), it.getInt("price"), it.getInt("price_base"),
                if (it.isNull("discount_percent")) null else it.optInt("discount_percent"))
        }
        val d = r.optJSONObject("discount")
        val up = r.optJSONObject("upgrade")
        _ui.update {
            it.copy(tariffs = Tariffs(
                premium = list("premium"), platinum = list("platinum"),
                discountTitle = d?.optString("title"), discountPercent = d?.optInt("percent"),
                currentTier = r.optString("current_tier", "premium"),
                upgradePrice = up?.optInt("price"),
                upgradeWhy = r.optString("upgrade_unavailable").takeIf { !r.isNull("upgrade_unavailable") },
            ))
        }
    }

    fun paySbp(months: Int, tier: String, open: (String) -> Unit) = launchBusy {
        val r = api.post("/pay/sbp", JSONObject().put("kind", "renew").put("months", months).put("tier", tier))
        withContext(Dispatchers.Main) { open(r.getString("payment_url")) }
    }

    fun payUpgrade(open: (String) -> Unit) = launchBusy {
        val r = api.post("/pay/sbp", JSONObject().put("kind", "upgrade"))
        withContext(Dispatchers.Main) { open(r.getString("payment_url")) }
    }

    fun cryptoQuote(coin: String, kind: String, months: Int, tier: String) = launchBusy {
        val r = api.post("/pay/crypto/quote", JSONObject().put("coin", coin).put("kind", kind)
            .put("months", months).put("tier", tier))
        _ui.update {
            it.copy(cryptoError = null, crypto = CryptoQuote(
                coin = r.getString("coin"), kind = kind, address = r.getString("address"),
                amount = r.getString("amount"), uri = r.getString("uri"), priceRub = r.getInt("price_rub"),
                quote = r.getString("quote"),
            ))
        }
    }

    fun cryptoCheck(txid: String) {
        val q = _ui.value.crypto ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, cryptoError = null) }
            try {
                val r = api.post("/pay/crypto/check", JSONObject().put("quote", q.quote).put("txid", txid.trim()))
                applyMe(r)
                _ui.update { it.copy(crypto = null) }
                toast("Оплата получена — подписка обновлена")
            } catch (e: ApiError) {
                if (e.code == "unauthorized") forgetSession()
                _ui.update { it.copy(cryptoError = e.message) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun sendLog(note: String) = launchBusy {
        val id = AppLog.send("manual", note.ifBlank { null })
        toast(if (id != null) "Журнал отправлен. Номер для поддержки: $id" else "Не удалось отправить — попробуйте позже или нажмите «Поделиться»")
    }

    fun cryptoClose() = _ui.update { it.copy(crypto = null, cryptoError = null) }

    fun payOnSite(to: String, open: (String) -> Unit) = launchBusy {
        val r = api.get("/pay/web_link?to=$to")
        withContext(Dispatchers.Main) { open(r.getString("url")) }
    }
}

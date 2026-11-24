package store.vpn312.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Что сейчас с VPN — для экрана и уведомления. */
object VpnStatus {
    enum class Phase { OFF, CONNECTING, ON, CHECKING, SWITCHING, ERROR }

    data class State(
        val phase: Phase = Phase.OFF,
        val region: String? = null,
        val latencyMs: Long? = null,
        val speedMbps: Double? = null,
        val note: String? = null,
    )

    private val _flow = MutableStateFlow(State())
    val flow: StateFlow<State> = _flow
    fun set(s: State) { _flow.value = s }
    val value get() = _flow.value
}

/**
 * Держит туннель и следит, что через него есть интернет. При «автосмене» сам переносит
 * устройство на другой сервер — тот же ключ (POST /devices/{id}/move), без участия юзера:
 *  - недоступность: 3 неудачные проверки подряд (~25 с) → перенос на лучший сервер по данным
 *    мониторинга сервера, туннель на новый сервер, проверка. Пока идёт перенос, туннель не трогаем
 *    (трафик телефона остаётся в VPN), а само приложение ходит к API через сеть телефона мимо
 *    своего VPN ([DirectNet]). Если такой сети не нашлось — вместо туннеля заглушка
 *    ([TunnelManager.blackhole]), из которой приложение исключено;
 *  - скорость: два замера подряд ниже порога, а у другого сервера по монитору заметно лучше.
 */
class VpnControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val app get() = App.instance
    private val sw get() = App.instance.config.switching
    private val failedRegions = mutableMapOf<String, Long>()
    private val pool = Executors.newCachedThreadPool()
    private var lastSpeedSwitch = 0L
    private var lastFailNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                AppLog.i("vpn", "отключение по команде")
                app.prefs.wantConnected = false
                loop?.cancel()
                loop = null
                scope.launch {
                    runCatching { app.tunnels.down() }
                    VpnStatus.set(VpnStatus.State())
                    // stopSelf(startId): если за это время пришла команда «подключить», сервис живёт
                    if (loop == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_CONNECT или перезапуск системой
                if (intent == null && !app.prefs.wantConnected) {
                    stopSelf(startId); return START_NOT_STICKY
                }
                startFg("Подключение…")
                app.prefs.wantConnected = true
                loop?.cancel()
                loop = scope.launch {
                    try {
                        run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // сбой цикла не должен ронять процесс: вместе с ним упал бы и туннель
                        AppLog.e("vpn", "цикл VPN упал", e)
                        // не оставлять телефон на заглушке без интернета
                        if (app.tunnels.isBlackhole) app.prefs.deviceConfig?.let { c -> runCatching { app.tunnels.up(c) } }
                        setStatus(VpnStatus.Phase.CHECKING, note = "Внутренняя ошибка проверки: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        pool.shutdownNow()
        super.onDestroy()
    }

    // ═══════════════════════ основной цикл ═══════════════════════

    private suspend fun run() {
        val conf = app.prefs.deviceConfig
        if (conf == null) {
            setStatus(VpnStatus.Phase.ERROR, note = "Выберите устройство в приложении")
            app.prefs.wantConnected = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        setStatus(VpnStatus.Phase.CONNECTING)
        AppLog.i("vpn", "подключение: устройство ${app.prefs.deviceId}, сервер ${app.prefs.deviceRegion}, автосмена ${app.prefs.autoSwitch}")
        try {
            app.tunnels.up(conf)
        } catch (e: Exception) {
            AppLog.e("vpn", "туннель не поднялся", e)
            setStatus(VpnStatus.Phase.ERROR, note = "Не удалось включить VPN: ${e.message ?: e.javaClass.simpleName}")
            app.prefs.wantConnected = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        var fails = 0
        var slow = 0
        var lastSpeed = 0L
        var first = true
        while (true) {
            delay(if (first) 2_000 else if (fails > 0) 5_000 else sw.probeIntervalS * 1000L)
            // конфиг сервера (пороги, адреса проверок, способы оплаты) — раз в 6 часов
            if (System.currentTimeMillis() - app.prefs.lastConfigFetch > 6 * 3600_000L) {
                app.prefs.lastConfigFetch = System.currentTimeMillis()
                RemoteConfig.refresh(app)
            }
            first = false
            currentCoroutineContext().ensureActive()
            if (!app.tunnels.isUp()) {
                // туннель погасили снаружи (другой VPN, отзыв разрешения)
                AppLog.w("vpn", "туннель погашен снаружи (другой VPN или отзыв разрешения)")
                setStatus(VpnStatus.Phase.OFF, note = "VPN выключен системой или другим приложением")
                app.prefs.wantConnected = false
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
            }
            val lat = probe()
            currentCoroutineContext().ensureActive()
            if (lat != null) {
                fails = 0
                setStatus(VpnStatus.Phase.ON, latency = lat)
            } else {
                fails++
                val st = app.tunnels.stats()
                val hsAgo = st?.handshakeMillis?.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000 }
                AppLog.w("vpn", "проверка через ${app.prefs.deviceRegion} не прошла ($fails/${sw.failsToSwitch}); " +
                    "хендшейк ${hsAgo?.let { "$it с назад" } ?: "не было"}, rx=${st?.rx} tx=${st?.tx}")
                setStatus(VpnStatus.Phase.CHECKING, note = "Сервер не отвечает, проверяю ($fails из ${sw.failsToSwitch})")
            }
            if (fails >= sw.failsToSwitch) {
                if (autoSwitchOn()) {
                    switchServer("unavailable")
                } else {
                    setStatus(VpnStatus.Phase.CHECKING,
                        note = "Через сервер нет интернета. Включите автосмену или выберите другой сервер.")
                    notifyOnce("Через сервер ${Regions.title(app.prefs.deviceRegion)} нет интернета",
                        "Включите автосмену сервера или выберите другой регион в приложении")
                }
                fails = 0
                continue
            }
            // скорость: раз в 20 мин на безлимитной сети, раз в час на мобильной
            val every = (if (isMetered()) sw.speedEveryMobileMin else sw.speedEveryWifiMin) * 60_000L
            val now = SystemClock.elapsedRealtime()
            if (fails == 0 && (lastSpeed == 0L || now - lastSpeed > every)) {
                lastSpeed = now
                val mbps = measureSpeed()
                currentCoroutineContext().ensureActive()
                AppLog.i("vpn", "скорость через ${app.prefs.deviceRegion}: ${mbps?.let { "%.1f Мбит/с".format(it) } ?: "замер не удался"}")
                if (mbps != null) {
                    setStatus(VpnStatus.Phase.ON, latency = VpnStatus.value.latencyMs, speed = mbps)
                    slow = if (mbps < sw.slowMbps) slow + 1 else 0
                    if (slow >= 2 && autoSwitchOn() && now - lastSpeedSwitch > sw.speedSwitchCooldownMin * 60_000L) {
                        lastSpeed = 0L // после переезда замерить снова сразу
                        if (switchServer("slow", currentMbps = mbps)) lastSpeedSwitch = now
                        slow = 0
                    }
                }
            }
        }
    }

    private fun autoSwitchOn(): Boolean {
        if (!app.prefs.autoSwitch || app.prefs.deviceId <= 0 || !app.config.feature("auto_switch")) return false
        val me = app.prefs.meCache?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return true
        return me.optBoolean("auto_switch_allowed", true)
    }

    // ═══════════════════════ смена сервера ═══════════════════════

    /** → true, если переехали на другой сервер и через него есть интернет. */
    private suspend fun switchServer(reason: String, currentMbps: Double? = null): Boolean {
        val prefs = app.prefs
        val oldConf = prefs.deviceConfig ?: return false
        val current = prefs.deviceRegion
        val priv = privateKeyOf(oldConf) ?: return false
        val now = SystemClock.elapsedRealtime()
        setStatus(VpnStatus.Phase.SWITCHING, note = if (reason == "slow") "Медленно — ищу сервер быстрее…" else "Ищу рабочий сервер…")
        AppLog.w("switch", "смена сервера: причина $reason, сейчас $current")
        val broken = reason == "unavailable"
        if (broken) current?.let { failedRegions[it] = now }
        // туннель не трогаем: к API приложение идёт через сеть телефона мимо своего VPN
        val net = DirectNet.find(this)
        if (net != null) AppLog.i("switch", "к API — через сеть телефона мимо VPN, туннель не трогаю")
        if (broken && net == null) {
            AppLog.w("switch", "сеть без VPN не найдена — ставлю заглушку")
            runCatching { app.tunnels.blackhole(oldConf) }
                .onFailure { AppLog.e("switch", "заглушка не поднялась, остаюсь на прежнем туннеле", it) }
        }
        val regions = try {
            Me.parseRegions(app.api.get("/regions", auth = false, net = net).getJSONArray("regions"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // нет связи даже напрямую — скорее всего, у телефона нет интернета: ничего не трогаем
            AppLog.w("switch", "API недоступен напрямую — у телефона нет интернета? Сервер не меняю (${e.javaClass.simpleName})")
            currentCoroutineContext().ensureActive()
            if (app.tunnels.isBlackhole && prefs.wantConnected) runCatching { app.tunnels.up(oldConf) }
            setStatus(VpnStatus.Phase.CHECKING, note = "Нет интернета у телефона или сервер VPN312 недоступен")
            delay(30_000)
            return false
        }
        val curSpeed = regions.firstOrNull { it.code == current }?.speedMbps
        val candidates = regions
            .filter { it.code != current && it.health != "down" }
            .filter { (failedRegions[it.code] ?: 0L).let { t -> t == 0L || now - t > sw.failedRegionCooldownMin * 60_000L } }
            .filter { reason != "slow" || (it.health == "ok" &&
                (it.speedMbps ?: 0.0) >= maxOf(sw.minAltMbps, sw.altSpeedFactor * (curSpeed ?: currentMbps ?: 0.0))) }
            .sortedWith(compareBy<Region> { Regions.rank[it.health] ?: 9 }.thenByDescending { it.speedMbps ?: 0.0 })
        AppLog.i("switch", "кандидаты: " + candidates.joinToString { "${it.code}(${it.health}, ${it.speedMbps ?: "?"})" }.ifEmpty { "нет" })
        for (cand in candidates.take(2)) {
            try {
                val res = app.api.post("/devices/${prefs.deviceId}/move", JSONObject()
                    .put("region", cand.code).put("private_key", priv).put("reason", reason), net = net)
                val newConf = res.getString("config")
                prefs.deviceConfig = newConf
                prefs.deviceRegion = cand.code
                prefs.meCache = res.toString()
                currentCoroutineContext().ensureActive()
                if (!prefs.wantConnected) return false
                app.tunnels.up(newConf)
                setStatus(VpnStatus.Phase.CHECKING, note = "Проверяю сервер «${Regions.title(cand.code)}»…")
                var ok = false
                repeat(6) {
                    if (!ok) {
                        delay(3_000)
                        if (probe() != null) ok = true
                    }
                }
                AppLog.i("switch", "перенос на ${cand.code}: ${if (ok) "интернет есть" else "через него тоже нет интернета"}")
                if (ok) {
                    val text = "${Regions.title(current)} → ${Regions.title(cand.code)}" +
                        if (reason == "slow") " (было медленно)" else " (сервер не отвечал)"
                    prefs.lastEvent = "Сервер сменён: $text"
                    notifyEvent("Сервер сменён автоматически", text)
                    setStatus(VpnStatus.Phase.ON)
                    return true
                }
                failedRegions[cand.code] = SystemClock.elapsedRealtime()
                // туннель на этот сервер не работает; без сети мимо VPN — снова заглушка
                if (net == null) runCatching { app.tunnels.blackhole(newConf) }
                    .onFailure { AppLog.e("switch", "заглушка не поднялась", it) }
            } catch (e: ApiError) {
                AppLog.w("switch", "перенос на ${cand.code} отклонён: ${e.status} ${e.code} ${e.message}")
                failedRegions[cand.code] = SystemClock.elapsedRealtime()
                if (e.code in setOf("trial", "inactive", "bad_key", "not_found", "unauthorized")) {
                    setStatus(VpnStatus.Phase.CHECKING, note = e.message)
                    break
                }
            } catch (e: CancellationException) {
                AppLog.i("switch", "смена сервера прервана: VPN отключён")
                throw e
            } catch (e: Exception) {
                AppLog.e("switch", "перенос на ${cand.code} упал", e)
                failedRegions[cand.code] = SystemClock.elapsedRealtime()
            }
        }
        // ничего не нашли — остаёмся на последнем конфиге и пробуем позже
        val conf = prefs.deviceConfig ?: oldConf
        currentCoroutineContext().ensureActive()
        if (prefs.wantConnected) runCatching {
            // туннель уже на этом конфиге — не пересоздаём: каждое пересоздание — доли секунды без VPN
            if (app.tunnels.isBlackhole || !app.tunnels.isUp() || app.tunnels.upConfig != conf) app.tunnels.up(conf)
        }
        if (broken) {
            setStatus(VpnStatus.Phase.CHECKING, note = "Рабочий сервер пока не найден — повторю через 2 минуты")
            notifyOnce("Нет рабочего сервера", "Приложение продолжает искать сервер")
            delay(120_000)
        }
        return false
    }

    // ═══════════════════════ проверки ═══════════════════════

    /**
     * HTTPS-запрос через туннель (приложение само идёт через VPN). → мс или null.
     * Жёсткий потолок: когда туннель мёртв, системный DNS-резолвер висит десятки секунд,
     * а у HttpURLConnection на резолв таймаута нет.
     */
    private fun probe(): Long? = withHardTimeout(9_000) { probeOnce() }

    private fun <T> withHardTimeout(ms: Long, block: () -> T?): T? {
        val f = try {
            pool.submit<T?> { block() }
        } catch (e: RejectedExecutionException) {
            return null // сервис уже остановлен
        }
        return try {
            f.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            f.cancel(true); null
        }
    }

    private fun probeOnce(): Long? {
        for (u in sw.probeUrls) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
                c.instanceFollowRedirects = false
                val code = c.responseCode
                c.disconnect()
                if (code == 204 || code == 200) return SystemClock.elapsedRealtime() - t0
            } catch (e: Exception) {
                // следующий адрес
            }
        }
        return null
    }

    private fun measureSpeed(): Double? = withHardTimeout(25_000) { measureSpeedOnce() }

    private fun measureSpeedOnce(): Double? = try {
        val c = URL(sw.speedUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 15000; c.useCaches = false
        val t0 = SystemClock.elapsedRealtime()
        var n = 0L
        c.inputStream.use { s ->
            val buf = ByteArray(16384)
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                n += r
            }
        }
        c.disconnect()
        val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        if (n < 200_000 || sec <= 0) null else n * 8 / sec / 1_000_000
    } catch (e: Exception) {
        null
    }

    private fun isMetered(): Boolean =
        getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    // ═══════════════════════ статус и уведомления ═══════════════════════

    private fun setStatus(phase: VpnStatus.Phase, latency: Long? = null, speed: Double? = null, note: String? = null) {
        val prev = VpnStatus.value
        val s = VpnStatus.State(
            phase = phase,
            region = app.prefs.deviceRegion,
            latencyMs = latency ?: if (phase == VpnStatus.Phase.ON) prev.latencyMs else null,
            speedMbps = speed ?: prev.speedMbps,
            note = note,
        )
        VpnStatus.set(s)
        val title = when (phase) {
            VpnStatus.Phase.ON -> "VPN включён · ${Regions.flag(s.region)} ${Regions.title(s.region)}"
            VpnStatus.Phase.CONNECTING -> "Подключение…"
            VpnStatus.Phase.SWITCHING -> "Смена сервера…"
            VpnStatus.Phase.CHECKING -> "VPN включён · проверка связи"
            else -> "VPN312"
        }
        val text = note ?: listOfNotNull(
            s.latencyMs?.let { "отклик $it мс" },
            s.speedMbps?.let { "%.1f Мбит/с".format(it) },
            if (app.prefs.autoSwitch) "автосмена вкл" else null,
        ).joinToString(" · ")
        if (phase != VpnStatus.Phase.OFF && phase != VpnStatus.Phase.ERROR && app.prefs.wantConnected) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(title, text))
        }
    }

    private fun startFg(text: String) {
        val n = buildNotification("VPN312", text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val off = PendingIntent.getService(this, 1,
            Intent(this, VpnControlService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, App.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", off).build())
            .build()
    }

    private fun notifyEvent(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, App.CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_EVENT_ID, n)
    }

    private fun notifyOnce(title: String, text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailNotify < 30 * 60_000L && lastFailNotify != 0L) return
        lastFailNotify = now
        notifyEvent(title, text)
    }

    companion object {
        const val ACTION_CONNECT = "store.vpn312.app.CONNECT"
        const val ACTION_DISCONNECT = "store.vpn312.app.DISCONNECT"
        private const val TAG = "VPN312"
        private const val NOTIF_ID = 312
        private const val NOTIF_EVENT_ID = 313

        fun connect(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, VpnControlService::class.java).setAction(ACTION_CONNECT))
        }

        fun disconnect(ctx: Context) {
            ctx.startService(Intent(ctx, VpnControlService::class.java).setAction(ACTION_DISCONNECT))
        }

        fun privateKeyOf(conf: String): String? =
            conf.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("PrivateKey") }
                ?.substringAfter("=")?.trim()
    }
}

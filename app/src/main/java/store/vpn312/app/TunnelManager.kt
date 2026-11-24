package store.vpn312.app

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream

/** Обёртка над WireGuard GoBackend: один туннель «vpn312». Вызовы блокирующие — только из IO. */
class TunnelManager(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val backend: GoBackend by lazy { GoBackend(appCtx) }

    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state

    /** Поднята «заглушка» (см. [blackhole]), а не рабочий туннель. */
    @Volatile
    var isBlackhole = false
        private set

    /** Конфиг, с которым сейчас поднят рабочий туннель (null — выключен или заглушка). */
    @Volatile
    var upConfig: String? = null
        private set

    private val tunnel = object : Tunnel {
        override fun getName() = "vpn312"
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = newState
        }
    }

    @Synchronized
    fun up(configText: String) {
        val cfg = Config.parse(ByteArrayInputStream(withIpv6Route(configText).toByteArray(Charsets.UTF_8)))
        _state.value = backend.setState(tunnel, Tunnel.State.UP, cfg)
        isBlackhole = false
        upConfig = configText
    }

    @Synchronized
    fun down() {
        _state.value = backend.setState(tunnel, Tunnel.State.DOWN, null)
        isBlackhole = false
        upConfig = null
    }

    /**
     * Запасной путь, когда сети без VPN не нашлось ([DirectNet]).
     * «Заглушка» на время смены сервера: VPN остаётся включённым, весь трафик телефона по-прежнему
     * уходит в туннель, но у туннеля нет рабочего сервера — пакеты просто отбрасываются, мимо VPN
     * ничего не идёт. Само приложение исключено из VPN (ExcludedApplications), поэтому до API оно
     * достаёт напрямую и может перенести устройство на другой сервер.
     *
     * Когда Android пересоздаёт туннель (рабочий → заглушка → новый рабочий), остаётся окно в доли
     * секунды без VPN. Закрывает его только «Постоянный VPN» с блокировкой соединений без VPN
     * в настройках Android.
     */
    @Synchronized
    fun blackhole(configText: String) {
        _state.value = backend.setState(tunnel, Tunnel.State.UP, Config.parse(
            ByteArrayInputStream(blackholeConfig(configText).toByteArray(Charsets.UTF_8))))
        isBlackhole = true
        upConfig = null
    }

    fun isUp(): Boolean = try {
        backend.getState(tunnel) == Tunnel.State.UP
    } catch (e: Exception) {
        false
    }

    /** [Interface] рабочего конфига + исключение самого приложения + сервер-пустышка без адреса. */
    private fun blackholeConfig(conf: String): String {
        val iface = mutableListOf<String>()
        var inInterface = false
        for (line in conf.lines()) {
            val t = line.trim()
            if (t.startsWith("[")) inInterface = t.equals("[Interface]", ignoreCase = true)
            else if (inInterface && t.isNotEmpty() &&
                !t.startsWith("ExcludedApplications", ignoreCase = true) &&
                !t.startsWith("IncludedApplications", ignoreCase = true)
            ) iface += t
        }
        return buildString {
            append("[Interface]\n")
            iface.forEach { append(it).append('\n') }
            append("ExcludedApplications = ").append(appCtx.packageName).append('\n')
            append("\n[Peer]\n")
            append("PublicKey = ").append(DUMMY_PEER).append('\n')
            append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        }
    }

    /**
     * В конфигах с сервера AllowedIPs = 0.0.0.0/0 — только IPv4. На Android всё, что не попало
     * в маршруты VPN, идёт мимо туннеля: в мобильной сети с IPv6 часть сайтов открывалась бы
     * напрямую. Добавляем ::/0 — IPv6 уходит в туннель, сервер его отбрасывает (IPv6 у серверов нет),
     * и приложения переходят на IPv4 через VPN.
     */
    private fun withIpv6Route(conf: String): String = conf.lines().joinToString("\n") { line ->
        val t = line.trim()
        if (t.startsWith("AllowedIPs", ignoreCase = true) && t.contains("0.0.0.0/0") && !t.contains("::/0")) {
            "$line, ::/0"
        } else line
    }

    data class Stats(val rx: Long, val tx: Long, val handshakeMillis: Long)

    fun stats(): Stats? = try {
        val s = backend.getStatistics(tunnel)
        val hs = s.peers().maxOfOrNull { s.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
        Stats(s.totalRx(), s.totalTx(), hs)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Случайный ключ несуществующего сервера: у заглушки нет адреса, пакеты никуда не уходят. */
        private val DUMMY_PEER: String by lazy { KeyPair().publicKey.toBase64() }
    }
}

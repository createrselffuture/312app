package store.vpn312.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Сеть телефона без VPN (Wi‑Fi или мобильная). Во время смены сервера приложение ходит к API
 * через неё, а туннель не трогает: трафик остальных приложений остаётся в VPN.
 * Привязать соединение к сети в обход своего VPN Android разрешает только приложению,
 * которое этот VPN держит.
 */
object DirectNet {
    fun find(ctx: Context): Network? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks
            .mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) ->
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            // сначала проверенная системой сеть, среди них — безлимитная (Wi‑Fi)
            .maxByOrNull { (_, c) ->
                (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 1 else 0)
            }?.first
    } catch (e: Exception) {
        AppLog.w("net", "сеть без VPN не найдена: ${e.javaClass.simpleName}")
        null
    }
}

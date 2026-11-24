package store.vpn312.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wireguard.android.backend.GoBackend

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var api: Api
    lateinit var tunnels: TunnelManager
    @Volatile var config: RemoteConfig = RemoteConfig.DEFAULT

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        AppLog.i("app", "старт ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        prefs = Prefs(this)
        config = RemoteConfig.load(prefs)
        api = Api(prefs)
        tunnels = TunnelManager(this)
        ConfigShare.cleanup(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Состояние VPN", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Смена сервера и сбои", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // «Постоянный VPN» в настройках Android: система сама поднимает наш VpnService
        GoBackend.setAlwaysOnCallback {
            if (prefs.deviceConfig != null) VpnControlService.connect(this)
        }
    }

    companion object {
        const val CH_STATUS = "vpn_status"
        const val CH_EVENTS = "vpn_events"
        lateinit var instance: App
            private set
    }
}

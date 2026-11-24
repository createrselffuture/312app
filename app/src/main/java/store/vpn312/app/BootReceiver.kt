package store.vpn312.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.VpnService

/** Включение телефона / обновление приложения: поднять VPN, если юзер так настроил. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = App.instance.prefs
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) notifyUpdated(context)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED && prefs.connectOnBoot
        val update = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && prefs.wantConnected
        if ((boot || update) && prefs.wantConnected && prefs.deviceConfig != null &&
            VpnService.prepare(context) == null
        ) {
            VpnControlService.connect(context)
        }
    }

    /** После самообновления Android закрывает приложение — даём кнопку открыть его снова. */
    private fun notifyUpdated(ctx: Context) {
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, App.CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("VPN312 обновлён до ${BuildConfig.VERSION_NAME}")
            .setContentText("Нажмите, чтобы открыть")
            .setContentIntent(open).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(314, n)
    }
}

package store.vpn312.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление без Google Play: скачать APK по ссылке из конфига (только https), сверить sha256
 * с конфигом (без sha256 не ставим) и отдать системному установщику. Android каждый раз
 * показывает своё окно подтверждения и сверяет подпись: APK с другим ключом не встанет.
 */
object Updater {
    sealed class State {
        data object Idle : State()
        data class Downloading(val progress: Float) : State()
        data object Installing : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    fun set(s: State) { _state.value = s }

    suspend fun downloadAndInstall(ctx: Context, rel: Release) = withContext(Dispatchers.IO) {
        val url = rel.apkUrl ?: run { set(State.Failed("Ссылка на обновление не указана")); return@withContext }
        if (!url.startsWith("https://")) { set(State.Failed("Ссылка на обновление не через https — не скачиваю")); return@withContext }
        val expected = rel.apkSha256?.takeIf { it.length == 64 }
            ?: run { set(State.Failed("Сервер не прислал контрольную сумму обновления — не скачиваю")); return@withContext }
        val file = File(ctx.cacheDir, "vpn312-update.apk")
        try {
            set(State.Downloading(0f))
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "VPN312-Android/${BuildConfig.VERSION_NAME}")
            if (c.responseCode != 200) throw Exception("Сервер ответил ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.apkSize
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) set(State.Downloading((done.toFloat() / total).coerceIn(0f, 1f)))
                    }
                }
            }
            c.disconnect()
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(expected, ignoreCase = true)) {
                file.delete()
                throw Exception("Файл скачался с ошибкой — попробуйте ещё раз")
            }
            set(State.Installing)
            install(ctx, file)
        } catch (e: Exception) {
            AppLog.w("update", "обновление до ${rel.latestName} не скачалось: ${e.message}")
            set(State.Failed(e.message ?: "Не удалось скачать обновление"))
        }
    }

    private fun install(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            // каждое обновление — только после системного окна подтверждения
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("vpn312.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.set(Updater.State.Idle)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.w("update", "установка не прошла: статус $status ${msg ?: ""}")
                Updater.set(Updater.State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Установка отменена"
                    else "Не удалось установить обновление${msg?.let { ": $it" } ?: ""}"))
            }
        }
    }
}

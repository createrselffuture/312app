package store.vpn312.app

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал приложения для разбора проблем: файл в памяти приложения (2 × 400 КБ по кругу),
 * падения приложения со стеком, отправка в поддержку (POST /logs). Ключи WireGuard, токены и
 * пароли в журнал не попадают — redact() вычищает их ещё до записи.
 */
object AppLog {
    private const val MAX_BYTES = 400_000L
    private val lock = Any()
    private var dir: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val file get() = dir?.let { File(it, "app.log") }
    private val oldFile get() = dir?.let { File(it, "app.1.log") }
    private val crashMark get() = dir?.let { File(it, "crash.pending") }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                e("CRASH", "приложение упало в потоке ${t.name}", e)
                crashMark?.writeText(System.currentTimeMillis().toString())
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val text = redact(msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        when (level) {
            "E" -> Log.e("VPN312/$tag", text)
            "W" -> Log.w("VPN312/$tag", text)
            else -> Log.i("VPN312/$tag", text)
        }
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) {
                    oldFile?.delete(); f.renameTo(oldFile!!)
                }
                f.appendText("${fmt.format(Date())} $level $tag: $text\n")
            } catch (_: Exception) {
            }
        }
    }

    private val keyRe = Regex("[A-Za-z0-9+/]{42,43}=")
    private val tokenRe = Regex("v312_[A-Za-z0-9_-]+")
    private val privRe = Regex("(?i)(PrivateKey\\s*=\\s*)\\S+")
    private val pwdRe = Regex("(?i)(\"(?:password|old_password|new_password|private_key)\"\\s*:\\s*)\"[^\"]*\"")

    /** Вычистить секреты: ключи WireGuard, токен сессии, пароли. */
    fun redact(s: String): String = s
        .replace(privRe, "$1[скрыт]")
        .replace(pwdRe, "$1\"[скрыт]\"")
        .replace(tokenRe, "v312_[скрыт]")
        .replace(keyRe, "[ключ]")

    fun tail(maxChars: Int = 150_000): String = synchronized(lock) {
        val all = (oldFile?.takeIf { it.exists() }?.readText() ?: "") + (file?.takeIf { it.exists() }?.readText() ?: "")
        if (all.length > maxChars) all.takeLast(maxChars) else all
    }

    fun clear() = synchronized(lock) { file?.delete(); oldFile?.delete(); Unit }

    fun hasPendingCrash() = crashMark?.exists() == true
    fun clearPendingCrash() { crashMark?.delete() }

    /** Свой logcat (сюда пишет и WireGuard: хендшейки, ошибки туннеля). Чужие приложения не видны. */
    private fun ownLogcat(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "600", "--pid=${Process.myPid()}"))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.destroy()
        redact(out.takeLast(120_000))
    } catch (e: Exception) {
        "logcat недоступен: ${e.javaClass.simpleName}"
    }

    fun buildReport(kind: String, note: String?): JSONObject {
        val app = App.instance
        val vpn = VpnStatus.value
        return JSONObject()
            .put("kind", kind)
            .put("note", note?.take(2000))
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("vpn", "${vpn.phase} ${vpn.region ?: "-"} ${vpn.note ?: ""}".trim())
            .put("device_id", app.prefs.deviceId)
            .put("region", app.prefs.deviceRegion)
            .put("auto_switch", app.prefs.autoSwitch)
            .put("config_version", app.config.version)
            .put("api_base", app.prefs.apiBase)
            .put("log", tail() + "\n----- logcat -----\n" + ownLogcat())
    }

    /** → номер отчёта или null. Без входа тоже работает (тогда отчёт без профиля). */
    suspend fun send(kind: String, note: String? = null): String? = try {
        val r = App.instance.api.post("/logs", buildReport(kind, note))
        if (kind == "crash") clearPendingCrash()
        i("log", "отчёт отправлен: ${r.optString("report_id")} ($kind)")
        r.optString("report_id").ifEmpty { null }
    } catch (e: Exception) {
        w("log", "отчёт не отправлен: ${e.message}")
        null
    }
}

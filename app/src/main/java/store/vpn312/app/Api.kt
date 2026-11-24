package store.vpn312.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.net.Network
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiError(val status: Int, val code: String, override val message: String) : Exception(message)

/**
 * Клиент API приложения. Адресов несколько (основной через Cloudflare и запасные):
 * пробуем по очереди, начиная с последнего ответившего. Сервер ответил ошибкой — не перебираем.
 */
class Api(private val prefs: Prefs) {
    private val bases: List<String> =
        BuildConfig.API_URLS.split(';').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    /** [net] — сеть, через которую идти (например, [DirectNet] мимо своего VPN); null — как обычно. */
    suspend fun get(path: String, auth: Boolean = true, net: Network? = null) = call("GET", path, null, auth, net)
    suspend fun post(path: String, body: JSONObject = JSONObject(), auth: Boolean = true, net: Network? = null) =
        call("POST", path, body, auth, net)
    suspend fun delete(path: String) = call("DELETE", path, null, true)

    suspend fun call(method: String, path: String, body: JSONObject?, auth: Boolean, net: Network? = null): JSONObject =
        withContext(Dispatchers.IO) {
            // последний ответивший → адреса из конфига сервера (только https) → встроенные в сборку
            val remote = runCatching { App.instance.config.apiUrls }.getOrDefault(emptyList())
                .map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }
            val saved = prefs.apiBase?.takeIf { it in bases || it.startsWith("https://") }
            val order = (listOfNotNull(saved) + remote + bases).distinct()
            for (base in order) {
                try {
                    val r = request(base, method, path, body, auth, net)
                    if (prefs.apiBase != base) prefs.apiBase = base
                    return@withContext r
                } catch (e: ApiError) {
                    if (e.status >= 500 || e.status == 0) AppLog.w("api", "$method $path → ${e.status} ${e.code}: ${e.message}")
                    throw e
                } catch (e: IOException) {
                    // этот адрес недоступен — пробуем следующий
                    AppLog.w("api", "$method $path через $base${if (net != null) " (мимо VPN)" else ""}: ${e.javaClass.simpleName} ${e.message ?: ""}")
                }
            }
            AppLog.w("api", "$method $path: ни один адрес API не ответил (${order.size})")
            throw ApiError(0, "network", "Нет связи с сервером VPN312. Проверьте интернет.")
        }

    private fun request(base: String, method: String, path: String, body: JSONObject?, auth: Boolean, net: Network?): JSONObject {
        val url = URL(base + path)
        val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 40000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "VPN312-Android/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("X-App-Version", BuildConfig.VERSION_CODE.toString())
            if (auth) prefs.token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                // HTML от Cloudflare/nginx (502/522…) — считаем адрес недоступным
                if (code >= 500 || code in 200..299) throw IOException("bad body $code")
                throw ApiError(code, "http", "Ошибка сервера ($code)")
            }
            if (!json.optBoolean("ok", false)) {
                throw ApiError(code, json.optString("code", "error"),
                    json.optString("error", "Ошибка сервера ($code)"))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }
}

// ═══════════════════════ модели ═══════════════════════

data class Subscription(
    val active: Boolean, val status: String, val tier: String, val isTrial: Boolean,
    val expiresAtMsk: String?, val secondsLeft: Long, val maxDevices: Int,
)

data class Device(
    val id: Long, val number: Int, val name: String, val region: String,
    val regionLive: Boolean, val status: String,
)

data class Region(
    val code: String, val title: String, val flag: String, val health: String,
    val speedMbps: Double?, val exit: String,
)

data class Me(
    val profileId: Long, val login: String?, val hasPassword: Boolean, val display: String,
    val telegram: String?, val subscription: Subscription?, val devices: List<Device>,
    val canAddDevice: Boolean, val canAddReason: String?, val autoSwitchAllowed: Boolean,
    val regions: List<Region>,
) {
    companion object {
        fun parse(j: JSONObject, regionsFallback: List<Region> = emptyList()): Me {
            val p = j.getJSONObject("profile")
            val s = j.optJSONObject("subscription")
            val sub = s?.let {
                Subscription(
                    active = it.optBoolean("active"), status = it.optString("status"),
                    tier = it.optString("tier", "premium"), isTrial = it.optBoolean("is_trial"),
                    expiresAtMsk = it.optString("expires_at_msk").ifEmpty { null },
                    secondsLeft = it.optLong("seconds_left"), maxDevices = it.optInt("max_devices", 5),
                )
            }
            val devs = j.optJSONArray("devices").objects().map {
                Device(
                    id = it.getLong("id"), number = it.optInt("number"), name = it.optString("name"),
                    region = it.optString("region"), regionLive = it.optBoolean("region_live", true),
                    status = it.optString("status", "active"),
                )
            }
            val regs = j.optJSONArray("regions")?.let { parseRegions(it) } ?: regionsFallback
            return Me(
                profileId = p.getLong("id"), login = p.optString("login").ifEmpty { null }.takeIf { !p.isNull("login") },
                hasPassword = p.optBoolean("has_password"), display = p.optString("display"),
                telegram = p.optString("telegram").takeIf { !p.isNull("telegram") && it.isNotEmpty() },
                subscription = sub, devices = devs, canAddDevice = j.optBoolean("can_add_device"),
                canAddReason = j.optString("can_add_reason").takeIf { !j.isNull("can_add_reason") && it.isNotEmpty() },
                autoSwitchAllowed = j.optBoolean("auto_switch_allowed"), regions = regs,
            )
        }

        fun parseRegions(a: JSONArray): List<Region> = a.objects().map {
            Region(
                code = it.getString("code"), title = it.optString("title"), flag = it.optString("flag"),
                health = it.optString("health", "unknown"),
                speedMbps = if (it.isNull("speed_mbps")) null else it.optDouble("speed_mbps"),
                exit = it.optString("exit"),
            )
        }
    }
}

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

object Regions {
    val titles = mapOf("nl" to "Нидерланды", "nl-2" to "Нидерланды-2", "de" to "Германия")
    val flags = mapOf("nl" to "🇳🇱", "nl-2" to "🇳🇱", "de" to "🇩🇪")
    fun title(code: String?) = titles[code] ?: (code ?: "—")
    fun flag(code: String?) = flags[code] ?: "🌐"
    val rank = mapOf("ok" to 0, "slow" to 1, "unknown" to 2, "flaky" to 3, "down" to 4)
}

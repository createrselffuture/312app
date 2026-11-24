package store.vpn312.app

import org.json.JSONObject

/**
 * Конфиг с сервера (GET /config): адреса API, способы оплаты, тексты, объявление, помощь,
 * настройки автосмены, версия и APK. Кэшируется — без сети работает последний полученный,
 * до первого получения — встроенные умолчания (те же, что на сервере).
 */
data class PayMethod(
    val id: String, val type: String, val title: String, val subtitle: String?,
    val coin: String?, val to: String?, val link: String?, val linkTitle: String?, val enabled: Boolean,
)

data class Announcement(
    val id: String, val level: String, val title: String, val text: String, val link: String?, val linkTitle: String?,
)

data class Switching(
    val probeUrls: List<String>, val speedUrl: String, val probeIntervalS: Int, val failsToSwitch: Int,
    val slowMbps: Double, val speedEveryWifiMin: Int, val speedEveryMobileMin: Int,
    val speedSwitchCooldownMin: Int, val failedRegionCooldownMin: Int, val minAltMbps: Double, val altSpeedFactor: Double,
)

data class Release(
    val minVersion: Int, val latestVersion: Int, val latestName: String, val notes: String,
    val apkUrl: String?, val apkSha256: String?, val apkSize: Long,
)

class RemoteConfig(val json: JSONObject) {
    val version: String = json.optString("version")
    val apiUrls: List<String> = json.optJSONArray("api_urls")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        ?: emptyList()

    private val texts = json.optJSONObject("texts") ?: JSONObject()
    fun text(key: String, fallback: String): String = texts.optString(key).ifEmpty { fallback }

    private val links = json.optJSONObject("links") ?: JSONObject()
    fun link(key: String, fallback: String): String = links.optString(key).ifEmpty { fallback }

    private val features = json.optJSONObject("features") ?: JSONObject()
    fun feature(key: String) = features.optBoolean(key, true)

    val announcement: Announcement? = json.optJSONObject("announcement")?.let {
        Announcement(it.optString("id"), it.optString("level", "info"), it.optString("title"), it.optString("text"),
            it.optString("link").ifEmpty { null }, it.optString("link_title").ifEmpty { null })
    }

    val help: List<Pair<String, String>> = json.optJSONArray("help").objects().map { it.optString("q") to it.optString("a") }

    val payMethods: List<PayMethod> = json.optJSONArray("payment_methods").objects().map {
        PayMethod(
            id = it.optString("id"), type = it.optString("type"), title = it.optString("title"),
            subtitle = it.optString("subtitle").ifEmpty { null }, coin = it.optString("coin").ifEmpty { null },
            to = it.optString("to").ifEmpty { null }, link = it.optString("link").ifEmpty { null },
            linkTitle = it.optString("link_title").ifEmpty { null }, enabled = it.optBoolean("enabled", true),
        )
    }.filter { it.enabled }

    val switching: Switching = (json.optJSONObject("switching") ?: JSONObject()).let { s ->
        Switching(
            probeUrls = s.optJSONArray("probe_urls")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?.takeIf { it.isNotEmpty() } ?: listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204"),
            speedUrl = s.optString("speed_url").ifEmpty { "https://speed.cloudflare.com/__down?bytes=1000000" },
            probeIntervalS = s.optInt("probe_interval_s", 15).coerceIn(5, 300),
            failsToSwitch = s.optInt("fails_to_switch", 3).coerceIn(1, 20),
            slowMbps = s.optDouble("slow_mbps", 1.0),
            speedEveryWifiMin = s.optInt("speed_every_wifi_min", 20).coerceAtLeast(1),
            speedEveryMobileMin = s.optInt("speed_every_mobile_min", 60).coerceAtLeast(1),
            speedSwitchCooldownMin = s.optInt("speed_switch_cooldown_min", 30).coerceAtLeast(1),
            failedRegionCooldownMin = s.optInt("failed_region_cooldown_min", 10).coerceAtLeast(1),
            minAltMbps = s.optDouble("min_alt_mbps", 5.0),
            altSpeedFactor = s.optDouble("alt_speed_factor", 3.0),
        )
    }

    val release: Release = (json.optJSONObject("release") ?: JSONObject()).let { r ->
        Release(
            minVersion = r.optInt("min_version", 1), latestVersion = r.optInt("latest_version", 1),
            latestName = r.optString("latest_name", ""), notes = r.optString("notes", ""),
            apkUrl = if (r.isNull("apk_url")) null else r.optString("apk_url").ifEmpty { null },
            apkSha256 = if (r.isNull("apk_sha256")) null else r.optString("apk_sha256").ifEmpty { null },
            apkSize = r.optLong("apk_size", 0),
        )
    }

    val updateAvailable get() = release.latestVersion > BuildConfig.VERSION_CODE && release.apkUrl != null
    val updateRequired get() = release.minVersion > BuildConfig.VERSION_CODE

    companion object {
        /** Пока сервер не ответил ни разу. */
        val DEFAULT = RemoteConfig(JSONObject().put("payment_methods", org.json.JSONArray()
            .put(JSONObject().put("id", "sbp").put("type", "sbp").put("title", "СБП").put("subtitle", "Через приложение банка"))
            .put(JSONObject().put("id", "site").put("type", "web").put("to", "pay").put("title", "Другие способы — на сайте"))))

        fun load(prefs: Prefs): RemoteConfig =
            prefs.configCache?.let { runCatching { RemoteConfig(JSONObject(it)) }.getOrNull() } ?: DEFAULT

        /** Скачать свежий конфиг; сбой — остаётся прежний. */
        suspend fun refresh(app: App): RemoteConfig? = try {
            val c = app.api.get("/config", auth = false).getJSONObject("config")
            app.prefs.configCache = c.toString()
            RemoteConfig(c).also { app.config = it }
        } catch (e: Exception) {
            null
        }
    }
}

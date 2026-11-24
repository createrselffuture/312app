package store.vpn312.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Шифрование секретов (токен, конфиг с приватным ключом) ключом из Android Keystore. */
object SecureBox {
    private const val ALIAS = "vpn312_box_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(c.iv) + ":" + b64(ct)
    }

    fun decrypt(s: String): String? = try {
        val (iv, ct) = s.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
        String(c.doFinal(unb64(ct)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}

/** Настройки и состояние приложения. Секреты — через SecureBox. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("vpn312", Context.MODE_PRIVATE)

    init {
        // до 1.3 кэш профиля лежал открытым и вместе с токеном / конфигом — переносим и стираем
        sp.getString("me_cache", null)?.let { old ->
            meCache = old
            sp.edit().remove("me_cache").apply()
        }
    }

    private fun secret(k: String): String? = sp.getString(k, null)?.let { SecureBox.decrypt(it) }
    private fun setSecret(k: String, v: String?) {
        sp.edit().apply { if (v == null) remove(k) else putString(k, SecureBox.encrypt(v)) }.apply()
    }

    var token: String?
        get() = secret("token")
        set(v) = setSecret("token", v)

    /** Устройство из списка юзера, которым пользуется этот телефон. */
    var deviceId: Long
        get() = sp.getLong("device_id", -1L)
        set(v) = sp.edit().putLong("device_id", v).apply()

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(v) = sp.edit().putString("device_name", v).apply()

    var deviceConfig: String?
        get() = secret("device_config")
        set(v) = setSecret("device_config", v)

    var deviceRegion: String?
        get() = sp.getString("device_region", null)
        set(v) = sp.edit().putString("device_region", v).apply()

    var autoSwitch: Boolean
        get() = sp.getBoolean("auto_switch", true)
        set(v) = sp.edit().putBoolean("auto_switch", v).apply()

    /** Отправлять отчёт о сбое автоматически при следующем запуске. */
    var crashReports: Boolean
        get() = sp.getBoolean("crash_reports", true)
        set(v) = sp.edit().putBoolean("crash_reports", v).apply()

    var connectOnBoot: Boolean
        get() = sp.getBoolean("connect_on_boot", false)
        set(v) = sp.edit().putBoolean("connect_on_boot", v).apply()

    /** Юзер хочет, чтобы VPN был включён (для автозапуска и восстановления). */
    var wantConnected: Boolean
        get() = sp.getBoolean("want_connected", false)
        set(v) = sp.edit().putBoolean("want_connected", v).apply()

    /** Последний ответивший адрес API. */
    var apiBase: String?
        get() = sp.getString("api_base", null)
        set(v) = sp.edit().putString("api_base", v).apply()

    /**
     * Последний ответ /me — чтобы экран не был пустым без сети. Хранится зашифрованным и без
     * токена и конфига устройства (они бывают в ответах входа и переноса на другой сервер).
     */
    var meCache: String?
        get() = secret("me_cache_enc")
        set(v) = setSecret("me_cache_enc", v?.let { sanitizeMe(it) })

    private fun sanitizeMe(json: String): String? = try {
        val j = org.json.JSONObject(json)
        listOf("token", "config", "private_key").forEach { j.remove(it) }
        j.toString()
    } catch (e: Exception) {
        null
    }

    var configCache: String?
        get() = sp.getString("config_cache", null)
        set(v) = sp.edit().putString("config_cache", v).apply()

    var dismissedAnnouncement: String?
        get() = sp.getString("dismissed_ann", null)
        set(v) = sp.edit().putString("dismissed_ann", v).apply()

    var lastConfigFetch: Long
        get() = sp.getLong("last_config_fetch", 0L)
        set(v) = sp.edit().putLong("last_config_fetch", v).apply()

    var lastEvent: String?
        get() = sp.getString("last_event", null)
        set(v) = sp.edit().putString("last_event", v).apply()

    fun clearDevice() {
        deviceId = -1L; deviceName = null; deviceConfig = null; deviceRegion = null
    }

    /** Выход из аккаунта: всё, кроме конфига приложения и рабочего адреса API. */
    fun clearAll() {
        val cfg = configCache; val base = apiBase
        sp.edit().clear().apply()
        configCache = cfg; apiBase = base
    }
}

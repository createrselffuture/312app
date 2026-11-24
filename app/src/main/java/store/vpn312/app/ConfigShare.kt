package store.vpn312.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** Перенос конфига на другое устройство: QR, файл .conf, буфер обмена. */
object ConfigShare {

    /** Имя туннеля в WireGuard берётся из имени файла: латиница, цифры, до 15 символов. */
    fun fileName(dev: Device) = "vpn312-${dev.number}.conf"

    fun qr(text: String, size: Int = 720): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** В буфер — с пометкой «секрет»: Android 13+ не покажет содержимое во всплывающем превью. */
    fun copy(ctx: Context, conf: String) {
        val clip = ClipData.newPlainText("Конфиг WireGuard", conf)
        val extras = PersistableBundle()
        if (Build.VERSION.SDK_INT >= 33) extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        else extras.putBoolean("android.content.extra.IS_SENSITIVE", true)
        clip.description.extras = extras
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    /**
     * Файл .conf для «Поделиться» живёт в кэше, пока его забирает другое приложение. Удаляем его
     * через 10 минут: при каждом открытии приложения и при старте его процесса.
     */
    fun cleanup(ctx: Context, maxAgeMs: Long = 10 * 60_000L) {
        val now = System.currentTimeMillis()
        File(ctx.cacheDir, "share").listFiles()?.forEach { f ->
            if (now - f.lastModified() > maxAgeMs) f.delete()
        }
    }

    /** Файл .conf через системное «Поделиться». → false, если отправить некуда. */
    fun shareFile(ctx: Context, dev: Device, conf: String): Boolean {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // старые конфиги в кэше не копим
        val f = File(dir, fileName(dev)).apply { writeText(conf) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VPN312 — ${dev.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            ctx.startActivity(Intent.createChooser(send, "Конфиг WireGuard"))
            true
        } catch (e: ActivityNotFoundException) {
            AppLog.w("share", "отправить файл некуда: ${e.message}")
            false
        }
    }
}

package app.bluepop

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothClass.Device as D
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update
import java.io.File

data class PermState(val bt: Boolean, val overlay: Boolean, val notif: Boolean, val battery: Boolean) {
    companion object { fun read(c: Context) = PermState(Perm.bt(c), Perm.overlay(c), Perm.notif(c), Perm.battery(c)) }
}

object Perm {
    fun bt(c: Context) = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    fun overlay(c: Context) = Settings.canDrawOverlays(c)
    fun notif(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun battery(c: Context) = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    fun runtimeList(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}

object ServiceCtl {
    const val TEST = "app.bluepop.TEST"
    fun sync(c: Context) {
        Store.init(c)
        val on = Store.settings.value.enabled && Perm.bt(c)
        val i = Intent(c, BluePopService::class.java)
        runCatching { if (on) ContextCompat.startForegroundService(c, i) else c.stopService(i) }
    }
    fun test(c: Context, address: String?) {
        runCatching {
            ContextCompat.startForegroundService(c, Intent(c, BluePopService::class.java).setAction(TEST).putExtra("addr", address))
        }
    }
}

object Sound {
    fun play(c: Context, uri: String?) {
        runCatching {
            val u = uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(c, u)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        }
    }
    fun vibrate(c: Context) {
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else c.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 18, 70, 30), -1))
        }
    }
}

@Suppress("DEPRECATION")
fun Intent.pickedRingtone(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    else getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

/** Copies a picked image into app storage (downsampled) and returns its path. */
fun saveDeviceImage(ctx: Context, uri: Uri, address: String): String? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (bounds.outWidth / s > 600) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        val f = File(ctx.filesDir, "dev_${address.replace(":", "")}_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f.absolutePath
    }.getOrNull()
}

@SuppressLint("MissingPermission")
object Bt {
    fun name(d: BluetoothDevice): String = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Bluetooth device"

    fun autoIcon(d: BluetoothDevice): String = runCatching {
        val k = d.bluetoothClass ?: return@runCatching "bluetooth"
        when (k.deviceClass) {
            D.AUDIO_VIDEO_HEADPHONES -> "headphones"
            D.AUDIO_VIDEO_WEARABLE_HEADSET, D.AUDIO_VIDEO_HANDSFREE -> "headset"
            D.AUDIO_VIDEO_LOUDSPEAKER, D.AUDIO_VIDEO_PORTABLE_AUDIO, D.AUDIO_VIDEO_HIFI_AUDIO -> "speaker"
            D.AUDIO_VIDEO_CAR_AUDIO -> "car"
            D.WEARABLE_WRIST_WATCH -> "watch"
            D.PERIPHERAL_KEYBOARD, D.PERIPHERAL_KEYBOARD_POINTING -> "keyboard"
            D.PERIPHERAL_POINTING -> "mouse"
            else -> "bluetooth"
        }
    }.getOrDefault("bluetooth")

    fun isAudioOrWearable(d: BluetoothDevice): Boolean {
        val m = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
        return m == BluetoothClass.Device.Major.AUDIO_VIDEO || m == BluetoothClass.Device.Major.WEARABLE
    }

    /**
     * Best-effort battery read. Android has no public API for this, so we try the hidden
     * getBatteryLevel() and the untethered left/right/case metadata keys via reflection.
     * Anything the OS does not expose stays null — values are never invented.
     */
    fun battery(d: BluetoothDevice): BatteryInfo {
        val lvl = runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int }
            .getOrNull()?.takeIf { it in 0..100 }
        fun meta(key: Int): Int? = runCatching {
            val m = BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType)
            val b = m.invoke(d, key) as? ByteArray ?: return null
            String(b).trim().toInt().takeIf { it in 0..100 }
        }.getOrNull()
        return BatteryInfo(lvl, meta(10), meta(11), meta(12)) // 10/11/12 = left / right / case battery
    }

    /** Fills Store.live with already-connected audio devices (A2DP + headset profiles). */
    fun seed(ctx: Context) {
        if (!Perm.bt(ctx)) return
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { p ->
            runCatching {
                ad.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val devs = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                        devs.forEach { Store.upsertSeen(it.address, name(it), autoIcon(it)) }
                        Store.live.update { cur -> cur + devs.associate { it.address to (cur[it.address] ?: battery(it)) } }
                        ad.closeProfileProxy(profile, proxy)
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, p)
            }
        }
    }
}

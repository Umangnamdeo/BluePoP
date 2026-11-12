package app.bluepop

import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

private const val CH_SVC = "bluepop_service"
private const val CH_POP = "bluepop_popup"
private const val ACTION_BATT = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
private const val EXTRA_BATT = "android.bluetooth.device.extra.BATTERY_LEVEL"

/**
 * Foreground service (type connectedDevice). Required because ACL/battery broadcasts cannot be
 * received from the manifest on modern Android, so something must stay alive to listen.
 * It does nothing between events — no polling, no timers.
 */
class BluePopService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: Overlay
    private val lastConnect = HashMap<String, Long>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handle(i)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        overlay = Overlay(this, scope)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Listening", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_POP, "Connection popups", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(this, CH_SVC)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BluePop is listening")
            .setContentText("Waiting for Bluetooth connections")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            ServiceCompat.startForeground(this, 1, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (e: Exception) { stopSelf(); return }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATT)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." + 76)
        }
        ContextCompat.registerReceiver(this, receiver, f,
            if (Build.VERSION.SDK_INT >= 31) android.Manifest.permission.BLUETOOTH_CONNECT else android.Manifest.permission.BLUETOOTH,
            null, ContextCompat.RECEIVER_EXPORTED) // sender must hold a BT permission: blocks spoofed events from other apps
        registered = true
        Bt.seed(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ServiceCtl.TEST) {
            test(intent.getStringExtra("addr"))
            if (!Store.settings.value.enabled) scope.launch { delay(Store.settings.value.displayMs + 3000L); stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) runCatching { unregisterReceiver(receiver) }
        if (::overlay.isInitialized) overlay.removeNow()
        scope.cancel()
        super.onDestroy()
    }

    // ---- event handling ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun handle(i: Intent) {
        val d = deviceOf(i) ?: return
        when (i.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> onConnect(d)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnect(d)
            ACTION_BATT -> {
                val l = i.getIntExtra(EXTRA_BATT, -1)
                if (l in 0..100) onBattery(d, BatteryInfo(level = l))
            }
            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> parseVendor(i)?.let { onBattery(d, it) }
        }
    }

    /** Apple-style +IPHONEACCEV battery report (key 1 = 0..9 -> 10..100%). Only real reports are used. */
    @Suppress("DEPRECATION")
    private fun parseVendor(i: Intent): BatteryInfo? {
        if (i.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) != "+IPHONEACCEV") return null
        val args = i.getSerializableExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*> ?: return null
        val n = args.getOrNull(0) as? Int ?: return null
        var k = 1
        repeat(n) {
            val key = args.getOrNull(k) as? Int
            val v = args.getOrNull(k + 1) as? Int
            if (key == 1 && v != null && v in 0..9) return BatteryInfo(level = (v + 1) * 10)
            k += 2
        }
        return null
    }

    private fun allowed(d: BluetoothDevice) = !Store.settings.value.onlyAudio || Bt.isAudioOrWearable(d)

    private fun event(a: String, t: EventType, b: BatteryInfo): PopupEvent {
        val r = Store.devices.value[a]
        return PopupEvent(a, r?.display ?: "Bluetooth device", t, b, r?.iconKey ?: r?.autoIcon ?: "bluetooth", r?.imagePath)
    }

    private fun onConnect(d: BluetoothDevice) {
        val a = d.address
        val now = SystemClock.elapsedRealtime()
        if (now - (lastConnect[a] ?: -10_000L) < 3000) return
        lastConnect[a] = now
        if (!allowed(d)) return
        val known = (Store.devices.value[a]?.lastSeen ?: 0L) > 0L
        Store.upsertSeen(a, Bt.name(d), Bt.autoIcon(d))
        val bat = Bt.battery(d)
        Store.live.update { it + (a to bat) }
        val e = event(a, if (known) EventType.RECONNECTED else EventType.CONNECTED, bat)
        Store.addHistory(HistoryEntry(e.name, a, e.type, System.currentTimeMillis(), bat.level))
        present(e)
        // Battery data usually shows up a moment after the link is up: re-read a few times, then stop.
        scope.launch { repeat(3) { delay(2500); val b = Bt.battery(d); if (b.any) onBattery(d, b) } }
    }

    private fun onDisconnect(d: BluetoothDevice) {
        val a = d.address
        if (!allowed(d)) return
        val wasLive = Store.live.value.containsKey(a)
        Store.live.update { it - a }
        if (!wasLive && Store.devices.value[a] == null) return
        val e = event(a, EventType.DISCONNECTED, BatteryInfo())
        Store.addHistory(HistoryEntry(e.name, a, e.type, System.currentTimeMillis()))
        present(e)
    }

    private fun onBattery(d: BluetoothDevice, p: BatteryInfo) {
        val a = d.address
        val cur = Store.live.value[a] ?: return
        val m = BatteryInfo(p.level ?: cur.level, p.left ?: cur.left, p.right ?: cur.right, p.case ?: cur.case)
        if (m == cur) return
        Store.live.update { it + (a to m) }
        val e = event(a, EventType.BATTERY, m)
        if (overlay.activeAddress == a) overlay.update(e) else if (!cur.any) present(e)
    }

    private fun test(addr: String?) {
        val r = addr?.let { Store.devices.value[it] }
        present(PopupEvent(addr ?: "test", r?.display ?: "Studio Buds", EventType.CONNECTED,
            BatteryInfo(82, 80, 84, 61), r?.iconKey ?: r?.autoIcon ?: "headphones", r?.imagePath), test = true)
    }

    private fun present(e: PopupEvent, test: Boolean = false) {
        val s = Store.settings.value
        if (!test) {
            if (!s.enabled || Store.devices.value[e.address]?.enabled == false) return
            val filter = getSystemService(NotificationManager::class.java).currentInterruptionFilter
            if (s.respectDnd && filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY) return
        }
        val c = Store.configFor(e.address)
        val shown = Settings.canDrawOverlays(this) && overlay.show(e, c, s.displayMs)
        if (!shown) postNote(e)
        val off = e.type == EventType.DISCONNECTED
        if (if (off) c.soundOffOn else c.soundOn) Sound.play(this, if (off) c.soundOffUri else c.soundUri)
        if (c.vibrate) Sound.vibrate(this)
    }

    /** Fallback when the overlay permission is missing: a heads-up notification. */
    private fun postNote(e: PopupEvent) {
        if (!Perm.notif(this)) return
        val txt = listOfNotNull(statusText(e), e.battery.level?.let { "$it%" }).joinToString(" · ")
        val n = NotificationCompat.Builder(this, CH_POP).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(e.name).setContentText(txt).setTimeoutAfter(6000).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(this).notify(e.address.hashCode(), n) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        if (Store.settings.value.autoStart) ServiceCtl.sync(c)
    }
}

// ---- Overlay window -------------------------------------------------------------------

/** Hosts the Compose popup in a TYPE_APPLICATION_OVERLAY window and drives show/hide. */
class Overlay(private val ctx: Context, private val scope: CoroutineScope) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val state = mutableStateOf<UiState?>(null)
    private var view: ComposeView? = null
    private var owner: ViewOwner? = null
    private var winKey: List<Any> = emptyList()
    private var job: Job? = null

    val activeAddress: String? get() = state.value?.takeIf { it.visible }?.event?.address

    fun show(e: PopupEvent, c: PopupConfig, holdMs: Int): Boolean {
        job?.cancel()
        val full = c.blur > 0 && Build.VERSION.SDK_INT >= 31
        val key = listOf(full, c.pos, c.offsetY, c.blur)
        if (view != null && key != winKey) removeNow()
        if (view == null && !attach(c, full)) return false
        winKey = key
        state.value = UiState(e, c, true, (state.value?.key ?: 0) + 1)
        job = scope.launch { delay(holdMs + animMs(c).toLong()); hide(c) }
        return true
    }

    fun update(e: PopupEvent) {
        val s = state.value ?: return
        state.value = s.copy(event = e.copy(type = s.event.type))
    }

    private suspend fun hide(c: PopupConfig) {
        state.value = state.value?.copy(visible = false)
        delay(animMs(c) + 100L)
        removeNow()
    }

    private fun dismiss() {
        val c = state.value?.cfg ?: return
        job?.cancel(); job = scope.launch { hide(c) }
    }

    private fun animMs(c: PopupConfig) = (c.durationMs / c.speed.coerceIn(0.25f, 3f)).toInt()

    private fun attach(c: PopupConfig, full: Boolean): Boolean {
        val o = ViewOwner().also { it.resume() }
        val v = ComposeView(ctx)
        v.setViewTreeLifecycleOwner(o)
        v.setViewTreeSavedStateRegistryOwner(o)
        v.setViewTreeViewModelStoreOwner(o)
        v.setContent { state.value?.let { PopupHost(it, fill = full, onTap = { dismiss() }) } }
        val d = ctx.resources.displayMetrics.density
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (full) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (full && Build.VERSION.SDK_INT >= 31) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val size = if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = when (c.pos) { Pos.TOP -> Gravity.TOP; Pos.CENTER -> Gravity.CENTER_VERTICAL; Pos.BOTTOM -> Gravity.BOTTOM } or Gravity.CENTER_HORIZONTAL
        if (!full && c.pos != Pos.CENTER) lp.y = ((c.offsetY - 20 - slideRoom(c)) * d).toInt()
        if (full && Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = (c.blur * 2.5f * d).toInt()
        return try {
            wm.addView(v, lp); view = v; owner = o; true
        } catch (e: Exception) { o.destroy(); false }
    }

    fun removeNow() {
        job?.cancel()
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        owner?.destroy()
        view = null; owner = null; state.value = null
    }
}

private class ViewOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lc = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    init { ssc.performRestore(null) }
    override val lifecycle: Lifecycle get() = lc
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    fun resume() { lc.currentState = Lifecycle.State.RESUMED }
    fun destroy() { lc.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}

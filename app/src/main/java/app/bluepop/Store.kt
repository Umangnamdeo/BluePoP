package app.bluepop

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Single source of truth. Small JSON blobs in SharedPreferences, exposed as StateFlows. */
object Store {
    private lateinit var sp: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    val settings = MutableStateFlow(AppSettings())
    val devices = MutableStateFlow<Map<String, DeviceRecord>>(emptyMap())
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    /** Currently connected devices (address -> last known battery). In-memory only. */
    val live = MutableStateFlow<Map<String, BatteryInfo>>(emptyMap())

    @Synchronized
    fun init(c: Context) {
        if (::sp.isInitialized) return
        sp = c.applicationContext.getSharedPreferences("bluepop", Context.MODE_PRIVATE)
        settings.value = read<AppSettings>("settings") ?: AppSettings()
        devices.value = (read<List<DeviceRecord>>("devices") ?: emptyList()).associateBy { it.address }
        history.value = read<List<HistoryEntry>>("history") ?: emptyList()
    }

    private inline fun <reified T> read(k: String): T? =
        runCatching { sp.getString(k, null)?.let { json.decodeFromString<T>(it) } }.getOrNull()

    private inline fun <reified T> write(k: String, v: T) {
        sp.edit().putString(k, json.encodeToString(v)).apply()
    }

    fun updateSettings(f: (AppSettings) -> AppSettings) { settings.update(f); write("settings", settings.value) }
    private fun saveDevices() = write("devices", devices.value.values.toList())

    fun updateDevice(a: String, f: (DeviceRecord) -> DeviceRecord) {
        devices.update { m -> m[a]?.let { m + (a to f(it)) } ?: m }; saveDevices()
    }

    fun upsertSeen(a: String, name: String, icon: String) {
        val now = System.currentTimeMillis()
        devices.update { m ->
            val o = m[a]
            m + (a to (o?.copy(name = name, lastSeen = now, autoIcon = icon) ?: DeviceRecord(a, name, lastSeen = now, autoIcon = icon)))
        }
        saveDevices()
    }

    fun forget(a: String) { devices.update { it - a }; saveDevices() }

    fun addHistory(h: HistoryEntry) {
        history.update { (listOf(h) + it).take(60) }; write("history", history.value)
    }

    fun configFor(a: String?): PopupConfig = a?.let { devices.value[it]?.config } ?: settings.value.config

    fun cfgFlow(t: String?): Flow<PopupConfig> =
        combine(settings, devices) { s, d -> if (t == null) s.config else d[t]?.config ?: s.config }

    /** Edit the global config (target == null) or one device's own config. */
    fun editConfig(t: String?, f: (PopupConfig) -> PopupConfig) {
        if (t == null) updateSettings { it.copy(config = f(it.config)) }
        else updateDevice(t) { it.copy(config = f(it.config ?: settings.value.config)) }
    }

    fun resetAll() {
        updateSettings { it.copy(config = PopupConfig()) }
        devices.update { m -> m.mapValues { it.value.copy(config = null) } }; saveDevices()
    }

    fun exportJson(): String =
        json.encodeToString(Backup(settings.value, devices.value.values.map { it.copy(imagePath = null) }))

    fun importJson(s: String): Boolean = runCatching {
        val b = json.decodeFromString<Backup>(s)
        updateSettings { b.settings.copy(enabled = it.enabled, autoUpdateCheck = it.autoUpdateCheck, lastUpdateCheck = it.lastUpdateCheck, displayMs = b.settings.displayMs.coerceIn(1000, 10000), config = b.settings.config.sane()) }
        devices.update { m ->
            val n = m.toMutableMap()
            b.devices.forEach { d -> val o = n[d.address]; n[d.address] = d.copy(imagePath = o?.imagePath, lastSeen = o?.lastSeen ?: d.lastSeen, config = d.config?.sane()) }
            n
        }
        saveDevices(); true
    }.getOrDefault(false)
}

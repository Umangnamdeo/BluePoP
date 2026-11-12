package app.bluepop

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

object UpdateConfig {
    /** TODO: host a version.json (see update/version.json) on your website and put its HTTPS address here. */
    const val URL = "https://example.com/bluepop/version.json"
    val configured: Boolean get() = URL.startsWith("https://") && !URL.contains("example.com")
}

@Serializable
data class UpdateInfo(val versionCode: Long, val versionName: String, val url: String, val notes: String = "")

sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    object UpToDate : UpdateResult
    object NotConfigured : UpdateResult
    object Failed : UpdateResult
}

object Updater {
    /** Set when a newer version is found; the app shows the dialog. */
    val pending = MutableStateFlow<UpdateInfo?>(null)

    @Suppress("DEPRECATION")
    fun installedCode(c: Context): Long {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }

    fun installedName(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    /** One small HTTPS GET. Nothing about the user or device is sent. */
    suspend fun check(c: Context): UpdateResult = withContext(Dispatchers.IO) {
        if (!UpdateConfig.configured) return@withContext UpdateResult.NotConfigured
        var con: HttpURLConnection? = null
        try {
            con = URL(UpdateConfig.URL).openConnection() as HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 8000
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 20_000) return@withContext UpdateResult.Failed
            val info = Json { ignoreUnknownKeys = true }.decodeFromString<UpdateInfo>(body)
            when {
                !info.url.startsWith("https://") -> UpdateResult.Failed   // never offer a non-HTTPS download
                info.versionCode > installedCode(c) -> UpdateResult.Available(info)
                else -> UpdateResult.UpToDate
            }
        } catch (e: Exception) { UpdateResult.Failed } finally { con?.disconnect() }
    }
}

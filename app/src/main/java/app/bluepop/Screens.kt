@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.bluepop

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

val Bg = Color(0xFF0A0A0C)
val Surface1 = Color(0xFF131317)
val Line = Color(0x1FFFFFFF)
val Ink = Color(0xFFF2F2F5)
val Muted = Color(0xFF8B8B96)
val Accent = Color(0xFF8CA6FF)

@Composable
fun BluePopTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, onPrimary = Bg, background = Bg, onBackground = Ink, surface = Bg, onSurface = Ink,
        surfaceVariant = Surface1, onSurfaceVariant = Muted, outline = Line, secondaryContainer = Color(0xFF22222A), onSecondaryContainer = Ink),
    content = content
)

@Composable
fun rememberPerms(): State<PermState> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf(PermState.read(ctx)) }
    val lo = LocalLifecycleOwner.current
    DisposableEffect(lo) {
        val ob = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) { st.value = PermState.read(ctx); ServiceCtl.sync(ctx); Bt.seed(ctx) }
        }
        lo.lifecycle.addObserver(ob)
        onDispose { lo.lifecycle.removeObserver(ob) }
    }
    return st
}

sealed interface Sub {
    data class Device(val a: String) : Sub
    data class DeviceStudio(val a: String) : Sub
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val perms by rememberPerms()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf<Sub?>(null) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ServiceCtl.sync(ctx) }
    val askRuntime = { req.launch(Perm.runtimeList()) }
    val pendingUpdate by Updater.pending.collectAsState()
    LaunchedEffect(Unit) {
        val st = Store.settings.value
        if (st.autoUpdateCheck && UpdateConfig.configured && System.currentTimeMillis() - st.lastUpdateCheck > 86_400_000L) {
            Store.updateSettings { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            (Updater.check(ctx) as? UpdateResult.Available)?.let { Updater.pending.value = it.info }
        }
    }
    pendingUpdate?.let { u ->
        AlertDialog(onDismissRequest = { Updater.pending.value = null },
            title = { Text("BluePop ${u.versionName} is available") },
            text = { Text(u.notes.ifBlank { "A new version is ready to download." }) },
            confirmButton = { TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) }; Updater.pending.value = null }) { Text("Download") } },
            dismissButton = { TextButton({ Updater.pending.value = null }) { Text("Later") } })
    }
    BackHandler(sub != null) { sub = (sub as? Sub.DeviceStudio)?.let { Sub.Device(it.a) } }
    Scaffold(containerColor = Bg, bottomBar = {
        if (sub == null) NavigationBar(containerColor = Bg) {
            listOf("Home" to Icons.Rounded.Home, "Studio" to Icons.Rounded.Tune, "Devices" to Icons.Rounded.Bluetooth, "Settings" to Icons.Rounded.Settings)
                .forEachIndexed { i, (l, ic) -> NavigationBarItem(tab == i, { tab = i }, { Icon(ic, l) }, label = { Text(l) }) }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = sub) {
                is Sub.Device -> DeviceDetail(s.a, { sub = Sub.DeviceStudio(s.a) }) { sub = null }
                is Sub.DeviceStudio -> Column { BackRow("Popup for this device") { sub = Sub.Device(s.a) }; Studio(s.a) }
                null -> Crossfade(tab, label = "tab") { t ->
                    when (t) {
                        0 -> HomeScreen(perms, askRuntime, { tab = 1 })
                        1 -> Column { Header("Studio"); Studio(null) }
                        2 -> DevicesScreen { addr: String -> sub = Sub.Device(addr) }
                        else -> SettingsScreen(perms, askRuntime)
                    }
                }
            }
        }
    }
}

@Composable
fun Header(t: String, trailing: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, Modifier.weight(1f), style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, color = Ink))
        trailing()
    }

@Composable
fun BackRow(t: String, onBack: () -> Unit) = Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    Text(t, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium))
}

@Composable
fun DeviceAvatar(rec: DeviceRecord?, size: Int = 44) {
    val bmp = rememberBitmap(rec?.imagePath)
    Box(Modifier.size(size.dp).clip(CircleShape).background(Surface1).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(IconKeys[rec?.iconKey ?: rec?.autoIcon ?: "bluetooth"] ?: Icons.Rounded.Bluetooth, null, tint = Accent, modifier = Modifier.size((size * 0.5f).dp))
    }
}

// ---------------- Home ----------------

@Composable
fun HomeScreen(perms: PermState, askRuntime: () -> Unit, goStudio: () -> Unit) {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsState()
    val live by Store.live.collectAsState()
    val devs by Store.devices.collectAsState()
    val hist by Store.history.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item {
            Header("BluePop") {
                Switch(s.enabled, { v -> Store.updateSettings { it.copy(enabled = v) }; ServiceCtl.sync(ctx) })
            }
            Text(if (!s.enabled) "Popups are off" else if (!perms.bt) "Bluetooth access needed" else "Listening for connections",
                Modifier.padding(horizontal = 20.dp), style = TextStyle(color = Muted, fontSize = 14.sp))
        }
        if (!perms.bt || !perms.overlay || !perms.notif) item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionLabel("FINISH SETUP")
                if (!perms.bt) SetupRow("Bluetooth access", "Lets BluePop see which device connected", askRuntime)
                if (!perms.overlay) SetupRow("Display over other apps", "Required to float the popup on screen") {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                if (!perms.notif) SetupRow("Notifications", "Used for the service and as a popup fallback", askRuntime)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("CONNECTED")
                if (live.isEmpty()) Text("No Bluetooth device connected.", style = TextStyle(color = Muted))
                live.forEach { (a, b) ->
                    val r = devs[a]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        DeviceAvatar(r, 48)
                        Column(Modifier.weight(1f)) {
                            Text(r?.display ?: "Bluetooth device", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            val line = when {
                                b.level != null -> "Battery ${b.level}%"
                                b.any -> listOfNotNull(b.left?.let { "L $it%" }, b.right?.let { "R $it%" }, b.case?.let { "Case $it%" }).joinToString("  ")
                                else -> "Battery not reported by this device"
                            }
                            Text(line, style = TextStyle(color = Muted, fontSize = 13.sp))
                        }
                        b.level?.let { Ring(it, 44.dp, PopupConfig(), 4.dp) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(horizontal = 20.dp)) { SectionLabel("POPUP STYLE") }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(PopupStyle.values().toList()) { st ->
                        val on = s.config.style == st
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, if (on) Accent else Line, RoundedCornerShape(14.dp))
                            .clickable { Store.updateSettings { it.copy(config = presetFor(st).keepFeedbackFrom(it.config)) } }
                            .padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(st.label, fontWeight = FontWeight.Medium, color = if (on) Accent else Ink)
                            Text(st.blurb, style = TextStyle(color = Muted, fontSize = 12.sp))
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(goStudio) { Text("Customize") }
                    OutlinedButton({
                        if (!perms.bt) askRuntime() else {
                            if (!perms.overlay) Toast.makeText(ctx, "Grant “Display over other apps” for the floating popup — showing a notification instead.", Toast.LENGTH_LONG).show()
                            ServiceCtl.test(ctx, null)
                        }
                    }) { Text("Test popup") }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("RECENT")
                if (hist.isEmpty()) Text("Connections will appear here.", style = TextStyle(color = Muted))
                hist.take(6).forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeviceAvatar(devs[h.address], 36)
                        Column(Modifier.weight(1f)) {
                            Text(devs[h.address]?.display ?: h.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.type.name.lowercase().replaceFirstChar(Char::uppercase) + " · " +
                                DateUtils.getRelativeTimeSpanString(h.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                style = TextStyle(color = Muted, fontSize = 12.sp))
                        }
                        h.battery?.let { Text("$it%", style = TextStyle(color = Muted, fontFamily = FontFamily.Monospace)) }
                    }
                }
            }
        }
        item {
            Text("Created by Umang Namdeo", Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = TextStyle(color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp))
        }
    }
}

@Composable
fun SetupRow(title: String, sub: String, onGrant: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(sub, style = TextStyle(color = Muted, fontSize = 12.sp)) }
        FilledTonalButton(onGrant) { Text("Grant") }
    }

// ---------------- Devices ----------------

@Composable
fun DevicesScreen(open: (String) -> Unit) {
    val devs by Store.devices.collectAsState()
    val live by Store.live.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Devices") }
        if (devs.isEmpty()) item {
            Text("Connect a Bluetooth device and it will appear here. You can then rename it, change its icon and give it its own popup.",
                Modifier.padding(20.dp), style = TextStyle(color = Muted))
        }
        items(devs.values.sortedByDescending { it.lastSeen }, key = { it.address }) { d ->
            Row(Modifier.fillMaxWidth().clickable { open(d.address) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DeviceAvatar(d)
                Column(Modifier.weight(1f)) {
                    Text(d.display, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(if (live.containsKey(d.address)) "Connected now" else "Last seen " + DateUtils.getRelativeTimeSpanString(d.lastSeen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                        style = TextStyle(color = if (live.containsKey(d.address)) Accent else Muted, fontSize = 12.sp))
                }
                Switch(d.enabled, { v -> Store.updateDevice(d.address) { it.copy(enabled = v) } })
            }
        }
    }
}

@Composable
fun DeviceDetail(address: String, onStudio: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val devs by Store.devices.collectAsState()
    val d = devs[address]
    if (d == null) { LaunchedEffect(Unit) { onBack() }; return }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) saveDeviceImage(ctx, u, address)?.let { p -> Store.updateDevice(address) { it.copy(imagePath = p) } }
            ?: Toast.makeText(ctx, "Could not load that image", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackRow(d.name, onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(d, 72)
                OutlinedTextField(d.customName ?: "", { v -> Store.updateDevice(address) { it.copy(customName = v) } }, Modifier.weight(1f),
                    label = { Text("Custom name") }, placeholder = { Text(d.name) }, singleLine = true)
            }
            SwitchRow("Show popup for this device", d.enabled) { v -> Store.updateDevice(address) { it.copy(enabled = v) } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("ICON")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(d.iconKey == null, { Store.updateDevice(address) { it.copy(iconKey = null) } }, { Text("Auto") })
                    IconKeys.forEach { (k, v) ->
                        FilterChip(d.iconKey == k, { Store.updateDevice(address) { it.copy(iconKey = k) } }, { Text(k.replaceFirstChar(Char::uppercase)) },
                            leadingIcon = { Icon(v, null, Modifier.size(18.dp)) })
                    }
                }
                Row {
                    TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose image") }
                    if (d.imagePath != null) TextButton({ Store.updateDevice(address) { it.copy(imagePath = null) } }) { Text("Remove image") }
                }
            }
            SwitchRow("Own popup design", d.config != null, "Off = uses your global design") { on ->
                Store.updateDevice(address) { it.copy(config = if (on) Store.settings.value.config else null) }
            }
            if (d.config != null) Button(onStudio) { Text("Customize this device’s popup") }
            TextButton({ Store.forget(address); onBack() }) { Text("Forget device", color = Color(0xFFFF6B6B)) }
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(perms: PermState, askRuntime: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val s by Store.settings.collectAsState()
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) runCatching { ctx.contentResolver.openOutputStream(u)?.use { it.write(Store.exportJson().toByteArray()) } }
            .onSuccess { toast("Theme backup saved") }.onFailure { toast("Backup failed") }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            val txt = runCatching { ctx.contentResolver.openInputStream(u)?.use { String(it.readBytes()) } }.getOrNull()
            toast(if (txt != null && Store.importJson(txt)) "Themes restored" else "Not a valid BluePop backup")
        }
    }
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Settings")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SwitchRow("Show popups", s.enabled) { v -> Store.updateSettings { it.copy(enabled = v) }; ServiceCtl.sync(ctx) }
            SwitchRow("Start after reboot", s.autoStart, "Restarts the listener when the phone boots") { v -> Store.updateSettings { it.copy(autoStart = v) } }
            SwitchRow("Audio and wearables only", s.onlyAudio, "Ignore keyboards, trackers and other accessories") { v -> Store.updateSettings { it.copy(onlyAudio = v) } }
            SwitchRow("Respect Do Not Disturb", s.respectDnd, "No popup, sound or vibration while DND is active") { v -> Store.updateSettings { it.copy(respectDnd = v) } }
            IntSlider("Display duration", s.displayMs / 100, 10..100, "") { v -> Store.updateSettings { it.copy(displayMs = v * 100) } }
            Text("Shown for ${"%.1f".format(s.displayMs / 1000f)} s after the opening animation.", style = TextStyle(color = Muted, fontSize = 12.sp))

            SectionLabel("ACCESS")
            PermLine("Bluetooth", perms.bt) { askRuntime() }
            PermLine("Display over other apps", perms.overlay) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
            PermLine("Notifications", perms.notif) { askRuntime() }
            PermLine("Unrestricted battery", perms.battery) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

            SectionLabel("UPDATES")
            Text("Installed version ${Updater.installedName(ctx)}")
            OutlinedButton({
                scope.launch {
                    checking = true; status = null
                    when (val r = Updater.check(ctx)) {
                        is UpdateResult.Available -> Updater.pending.value = r.info
                        UpdateResult.UpToDate -> status = "You’re on the latest version."
                        UpdateResult.NotConfigured -> status = "The update link isn’t set up in this build."
                        UpdateResult.Failed -> status = "Couldn’t check for updates. Check your connection and try again."
                    }
                    checking = false
                }
            }, enabled = !checking) { Text(if (checking) "Checking…" else "Check for updates") }
            status?.let { Text(it, style = TextStyle(color = Muted, fontSize = 13.sp)) }
            SwitchRow("Check automatically", s.autoUpdateCheck, "Once a day when you open the app. Downloads one small version file; nothing about you is sent.") { v ->
                Store.updateSettings { it.copy(autoUpdateCheck = v) }
            }

            SectionLabel("THEMES")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ export.launch("bluepop-themes.json") }) { Text("Back up") }
                OutlinedButton({ import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore") }
            }
            TextButton({ confirmReset = true }) { Text("Reset all customization", color = Color(0xFFFF6B6B)) }
            SectionLabel("ABOUT")
            Text("BluePop 1.0", fontWeight = FontWeight.Medium)
            Text("Created by Umang Namdeo", style = TextStyle(color = Accent, fontSize = 14.sp))
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("Reset customization?") }, text = { Text("Your global design and every per-device design go back to defaults.") },
        confirmButton = { TextButton({ Store.resetAll(); confirmReset = false; toast("Reset") }) { Text("Reset") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
}

@Composable
fun PermLine(label: String, ok: Boolean, onFix: () -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (ok) Text("Granted", style = TextStyle(color = Accent, fontSize = 13.sp)) else TextButton(onFix) { Text("Fix") }
    }

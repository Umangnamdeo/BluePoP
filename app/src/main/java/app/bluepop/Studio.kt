@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.bluepop

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Studio(target: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c by remember(target) { Store.cfgFlow(target) }.collectAsState(initial = Store.configFor(target))
    val rec = target?.let { Store.devices.value[it] }
    val ev = remember(target) {
        PopupEvent("sample", rec?.display ?: "Studio Buds", EventType.CONNECTED, BatteryInfo(82, 80, 84, 61),
            rec?.iconKey ?: rec?.autoIcon ?: "headphones", rec?.imagePath)
    }
    var key by remember { mutableIntStateOf(1) }
    var vis by remember { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    fun edit(f: (PopupConfig) -> PopupConfig) = Store.editConfig(target, f)

    Column(Modifier.fillMaxSize()) {
        // LIVE PREVIEW — the same composable the overlay window renders.
        Box(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(250.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF232B3F), Color(0xFF0E1118))))
        ) { PopupHost(UiState(ev, c, vis, key), fill = true, preview = true) }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ scope.launch { vis = false; delay((c.durationMs / c.speed).toLong() + 150); key++; vis = true } }) { Text("Replay") }
            TextButton({ ServiceCtl.test(ctx, target) }) { Text("Test on device") }
            TextButton({ edit { presetFor(it.style).keepFeedbackFrom(it) } }) { Text("Reset style") }
        }
        Text("Preview uses sample battery values.", style = TextStyle(color = Muted, fontSize = 11.sp), modifier = Modifier.padding(horizontal = 20.dp))
        ScrollableTabRow(tab, edgePadding = 12.dp, containerColor = Bg, divider = {}) {
            listOf("Style", "Look", "Type", "Motion", "Layout", "Sound").forEachIndexed { i, t ->
                Tab(tab == i, { tab = i }, text = { Text(t) })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (tab) {
                0 -> {
                    ChipRow("STYLE", PopupStyle.values().toList(), c.style, { it.label }) { st -> edit { presetFor(st).keepFeedbackFrom(it) } }
                    Text(c.style.blurb + ". Picking a style loads its defaults; tweak anything afterwards.", style = TextStyle(color = Muted, fontSize = 12.sp))
                    ChipRow("THEME", listOf(true, false), c.dark, { if (it) "Dark" else "Light" }) { d -> edit { it.themed(d) } }
                }
                1 -> {
                    ColorRow("BACKGROUND", c.bg) { v -> edit { it.copy(bg = v) } }
                    SwitchRow("Gradient background", c.gradient) { v -> edit { it.copy(gradient = v) } }
                    if (c.gradient) ColorRow("GRADIENT END", c.bg2) { v -> edit { it.copy(bg2 = v) } }
                    ColorRow("BORDER COLOUR", c.border) { v -> edit { it.copy(border = v) } }
                    ColorRow("ACCENT", c.accent) { v -> edit { it.copy(accent = v) } }
                    SliderRow("Transparency", c.alpha, 0.1f..1f, { "${(it * 100).toInt()}% opaque" }) { v -> edit { it.copy(alpha = v) } }
                    IntSlider("Blur behind popup", c.blur, 0..40, "") { v -> edit { it.copy(blur = v) } }
                    Text("Backdrop blur needs Android 12+ and blurs the screen behind the popup while it is visible.", style = TextStyle(color = Muted, fontSize = 12.sp))
                    IntSlider("Corner radius", c.radius, 0..48, "dp") { v -> edit { it.copy(radius = v) } }
                    SliderRow("Border thickness", c.borderW, 0f..6f, { "%.1f dp".format(it) }) { v -> edit { it.copy(borderW = v) } }
                    SliderRow("Shadow", c.shadow, 0f..1f, { "${(it * 100).toInt()}%" }) { v -> edit { it.copy(shadow = v) } }
                    IntSlider("Width", c.width, 160..420, "dp") { v -> edit { it.copy(width = v) } }
                    IntSlider("Height (0 = auto)", c.height, 0..260, "dp") { v -> edit { it.copy(height = v) } }
                    ChipRow("POSITION", Pos.values().toList(), c.pos, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(pos = v) } }
                    IntSlider("Distance from edge", c.offsetY, 0..200, "dp") { v -> edit { it.copy(offsetY = v) } }
                }
                2 -> {
                    ChipRow("FONT", FontKind.values().toList(), c.font, { it.label }) { v -> edit { it.copy(font = v) } }
                    IntSlider("Font size", c.fontSize, 11..26, "sp") { v -> edit { it.copy(fontSize = v) } }
                    IntSlider("Font weight", c.weight, 300..900, "") { v -> edit { it.copy(weight = (v / 100) * 100) } }
                    ColorRow("TEXT COLOUR", c.text) { v -> edit { it.copy(text = v) } }
                    ChipRow("DEVICE NAME ALIGNMENT", Align.values().toList(), c.align, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(align = v) } }
                }
                3 -> {
                    ChipRow("OPENING", AnimKind.values().toList(), c.openAnim, { it.label }) { v -> edit { it.copy(openAnim = v) } }
                    ChipRow("CLOSING", AnimKind.values().toList(), c.closeAnim, { it.label }) { v -> edit { it.copy(closeAnim = v) } }
                    IntSlider("Duration", c.durationMs, 150..1200, "ms") { v -> edit { it.copy(durationMs = v) } }
                    SliderRow("Speed", c.speed, 0.5f..2.5f, { "%.1f×".format(it) }) { v -> edit { it.copy(speed = v) } }
                    Text("Spring ignores duration and uses speed as stiffness.", style = TextStyle(color = Muted, fontSize = 12.sp))
                }
                4 -> {
                    Text("Image and battery placement apply to the Card and Custom styles. Every style honours compact mode, spacing and the battery indicator.",
                        style = TextStyle(color = Muted, fontSize = 12.sp))
                    SwitchRow("Horizontal layout", c.horizontal) { v -> edit { it.copy(horizontal = v) } }
                    ChipRow("DEVICE IMAGE", ImagePos.values().toList(), c.imagePos, { it.label }) { v -> edit { it.copy(imagePos = v) } }
                    ChipRow("BATTERY POSITION", BatPos.values().toList(), c.batPos, { it.label }) { v -> edit { it.copy(batPos = v) } }
                    ChipRow("BATTERY INDICATOR", BatStyle.values().toList(), c.batStyle, { it.label }) { v -> edit { it.copy(batStyle = v) } }
                    IntSlider("Spacing", c.spacing, 4..28, "dp") { v -> edit { it.copy(spacing = v) } }
                    SwitchRow("Compact mode", c.compact, "Hides battery details for a smaller popup") { v -> edit { it.copy(compact = v) } }
                }
                else -> {
                    SwitchRow("Connection sound", c.soundOn) { v -> edit { it.copy(soundOn = v) } }
                    SoundRow("Connect sound", c.soundUri) { u -> edit { it.copy(soundUri = u) } }
                    SwitchRow("Disconnection sound", c.soundOffOn) { v -> edit { it.copy(soundOffOn = v) } }
                    SoundRow("Disconnect sound", c.soundOffUri) { u -> edit { it.copy(soundOffUri = u) } }
                    SwitchRow("Vibration", c.vibrate) { v -> edit { it.copy(vibrate = v) } }
                    TextButton({ Sound.vibrate(ctx) }) { Text("Feel vibration") }
                }
            }
        }
    }
}

// ---------- controls ----------------------------------------------------------------------

@Composable
fun SectionLabel(t: String) = Text(t, style = TextStyle(color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace))

@Composable
fun <T> ChipRow(label: String, items: List<T>, sel: T, name: (T) -> String, onSel: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { FilterChip(it == sel, { onSel(it) }, { Text(name(it)) }) }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            if (sub != null) Text(sub, style = TextStyle(color = Muted, fontSize = 12.sp))
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label); Text(fmt(value), style = TextStyle(color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace))
        }
        Slider(value.coerceIn(range), onChange, valueRange = range)
    }
}

@Composable
fun IntSlider(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) =
    SliderRow(label, value.toFloat(), range.first.toFloat()..range.last.toFloat(), { "${it.toInt()}$unit" }) { onChange(it.toInt()) }

private val Swatches = listOf(0xFF121216, 0xFF000000, 0xFFFFFFFF, 0xFFF4F4F8, 0xFF7CE0B8, 0xFF8CA6FF, 0xFFFF4D4D, 0xFFFFB454, 0xFFE879F9, 0xFF34D399)

@Composable
fun ColorRow(label: String, value: Long, onChange: (Long) -> Unit) {
    var t by remember(value) { mutableStateOf("%06X".format((value and 0xFFFFFFL).toInt())) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatches.forEach { s ->
                    val on = (s and 0xFFFFFFL) == (value and 0xFFFFFFL)
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(s)).border(if (on) 2.dp else 1.dp, if (on) Accent else Line, CircleShape)
                        .clickable { onChange(((value and 0xFF000000L).takeIf { it != 0L } ?: 0xFF000000L) or (s and 0xFFFFFFL)) })
                }
            }
            OutlinedTextField(t, { n ->
                val h = n.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
                t = h
                if (h.length == 6) onChange(0xFF000000L or h.toLong(16))
            }, Modifier.width(112.dp), singleLine = true, prefix = { Text("#") },
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }
    }
}

@Composable
fun SoundRow(label: String, uri: String?, onPick: (String?) -> Unit) {
    val ctx = LocalContext.current
    val sys = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.pickedRingtone()
        if (r.resultCode == Activity.RESULT_OK && u != null) onPick(u.toString())
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPick(u.toString())
        }
    }
    Column {
        Text(label)
        Text(if (uri == null) "Default notification sound" else Uri.parse(uri).lastPathSegment ?: "Custom sound",
            style = TextStyle(color = Muted, fontSize = 12.sp))
        Row {
            TextButton({ sys.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)) }) { Text("System") }
            TextButton({ file.launch(arrayOf("audio/*")) }) { Text("File") }
            TextButton({ onPick(null) }) { Text("Default") }
            TextButton({ Sound.play(ctx, uri) }) { Text("Play") }
        }
    }
}

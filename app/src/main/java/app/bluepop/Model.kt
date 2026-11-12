package app.bluepop

import kotlinx.serialization.Serializable

enum class PopupStyle(val label: String, val blurb: String) {
    POD("Pod", "Rings per bud"), MONO("Mono", "Dot-matrix minimal"), CARD("Card", "Soft rounded card"),
    ISLAND("Island", "Expanding pill"), FLOAT("Float", "Slim chip"), GLASS("Glass", "Frosted layers"),
    CUSTOM("Custom", "Your own layout")
}
enum class AnimKind(val label: String) { SLIDE("Slide"), FADE("Fade"), SCALE("Scale"), SPRING("Spring"), SLIDE_FADE("Slide + fade") }
enum class Pos { TOP, CENTER, BOTTOM }
enum class BatStyle(val label: String) { RING("Ring"), BAR("Bar"), TEXT("Percent") }
enum class BatPos(val label: String) { BELOW("Below name"), END("At the end") }
enum class FontKind(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono"), CURSIVE("Cursive") }
enum class Align { START, CENTER, END }
enum class ImagePos(val label: String) { START("Start"), END("End"), TOP("Top") }
enum class EventType { CONNECTED, RECONNECTED, DISCONNECTED, BATTERY }

@Serializable
data class PopupConfig(
    val style: PopupStyle = PopupStyle.POD,
    val bg: Long = 0xFF121216, val bg2: Long = 0xFF262633, val gradient: Boolean = false,
    val alpha: Float = 0.97f, val blur: Int = 0, val radius: Int = 30,
    val borderW: Float = 0f, val border: Long = 0x40FFFFFF, val shadow: Float = 0.5f,
    val width: Int = 300, val height: Int = 0,
    val pos: Pos = Pos.TOP, val offsetY: Int = 40, val dark: Boolean = true,
    val font: FontKind = FontKind.SANS, val fontSize: Int = 16, val weight: Int = 600,
    val text: Long = 0xFFFFFFFF, val align: Align = Align.CENTER, val accent: Long = 0xFF7CE0B8,
    val openAnim: AnimKind = AnimKind.SPRING, val closeAnim: AnimKind = AnimKind.SLIDE_FADE,
    val durationMs: Int = 450, val speed: Float = 1f,
    val horizontal: Boolean = true, val imagePos: ImagePos = ImagePos.START, val batPos: BatPos = BatPos.BELOW,
    val batStyle: BatStyle = BatStyle.RING, val spacing: Int = 12, val compact: Boolean = false,
    val soundOn: Boolean = false, val soundOffOn: Boolean = false, val vibrate: Boolean = true,
    val soundUri: String? = null, val soundOffUri: String? = null
) {
    fun keepFeedbackFrom(o: PopupConfig) = copy(
        soundOn = o.soundOn, soundOffOn = o.soundOffOn, vibrate = o.vibrate,
        soundUri = o.soundUri, soundOffUri = o.soundOffUri
    )
}

fun PopupConfig.themed(dark: Boolean) =
    if (dark) copy(dark = true, bg = 0xFF121216, bg2 = 0xFF262633, text = 0xFFFFFFFF, border = 0x40FFFFFF)
    else copy(dark = false, bg = 0xFFF4F4F8, bg2 = 0xFFDADCE8, text = 0xFF16161A, border = 0x1F000000)

fun presetFor(s: PopupStyle): PopupConfig = when (s) {
    PopupStyle.POD -> PopupConfig(style = s)
    PopupStyle.MONO -> PopupConfig(style = s, bg = 0xFF000000, alpha = 1f, radius = 18, borderW = 1f, border = 0x55FFFFFF,
        font = FontKind.MONO, fontSize = 14, weight = 500, width = 300, shadow = 0.25f, align = Align.START,
        openAnim = AnimKind.FADE, closeAnim = AnimKind.FADE, durationMs = 320, accent = 0xFFFF4D4D)
    PopupStyle.CARD -> PopupConfig(style = s, bg = 0xFFF4F4F8, bg2 = 0xFFDADCE8, text = 0xFF16161A, dark = false,
        radius = 32, width = 340, shadow = 0.4f, align = Align.START, accent = 0xFF5B6CFF, openAnim = AnimKind.SLIDE_FADE)
    PopupStyle.ISLAND -> PopupConfig(style = s, bg = 0xFF000000, alpha = 1f, radius = 40, width = 320, offsetY = 12,
        fontSize = 14, shadow = 0.2f, align = Align.START, accent = 0xFF34D399, durationMs = 520)
    PopupStyle.FLOAT -> PopupConfig(style = s, bg = 0xFF1B1B20, alpha = 0.95f, radius = 50, width = 260, fontSize = 14,
        weight = 500, shadow = 0.35f, borderW = 1f, border = 0x26FFFFFF, openAnim = AnimKind.SCALE, durationMs = 300)
    PopupStyle.GLASS -> PopupConfig(style = s, bg = 0xFFFFFFFF, bg2 = 0xFF9FB4FF, gradient = true, alpha = 0.26f,
        blur = 24, radius = 36, width = 330, borderW = 1f, border = 0x70FFFFFF, shadow = 0.3f, align = Align.START,
        accent = 0xFFFFFFFF, openAnim = AnimKind.SCALE, durationMs = 520)
    PopupStyle.CUSTOM -> PopupConfig(style = s, align = Align.START, radius = 24, borderW = 1f, border = 0x33FFFFFF)
}

@Serializable
data class DeviceRecord(
    val address: String, val name: String, val customName: String? = null,
    val iconKey: String? = null, val imagePath: String? = null, val enabled: Boolean = true,
    val config: PopupConfig? = null, val lastSeen: Long = 0, val autoIcon: String = "bluetooth"
) { val display: String get() = customName?.takeIf { it.isNotBlank() } ?: name }

@Serializable
data class HistoryEntry(val name: String, val address: String, val type: EventType, val time: Long, val battery: Int? = null)

@Serializable
data class AppSettings(
    val enabled: Boolean = true, val autoStart: Boolean = true, val onlyAudio: Boolean = true,
    val respectDnd: Boolean = true, val displayMs: Int = 3500,
    val autoUpdateCheck: Boolean = true, val lastUpdateCheck: Long = 0, val config: PopupConfig = PopupConfig()
)

@Serializable
data class Backup(val settings: AppSettings, val devices: List<DeviceRecord>)

data class BatteryInfo(val level: Int? = null, val left: Int? = null, val right: Int? = null, val case: Int? = null) {
    val any: Boolean get() = level != null || left != null || right != null || case != null
}

data class PopupEvent(
    val address: String, val name: String, val type: EventType,
    val battery: BatteryInfo, val iconKey: String, val imagePath: String?
)

/** Clamps values so an imported or corrupted backup can never crash the popup. */
fun PopupConfig.sane() = copy(
    alpha = alpha.coerceIn(0.05f, 1f), blur = blur.coerceIn(0, 40), radius = radius.coerceIn(0, 60),
    borderW = borderW.coerceIn(0f, 8f), shadow = shadow.coerceIn(0f, 1f), width = width.coerceIn(120, 480),
    height = height.coerceIn(0, 300), offsetY = offsetY.coerceIn(0, 300), fontSize = fontSize.coerceIn(8, 32),
    weight = weight.coerceIn(100, 900), durationMs = durationMs.coerceIn(100, 3000), speed = speed.coerceIn(0.25f, 3f),
    spacing = spacing.coerceIn(0, 40),
    soundUri = soundUri?.takeIf { it.startsWith("content://") }, soundOffUri = soundOffUri?.takeIf { it.startsWith("content://") }
)

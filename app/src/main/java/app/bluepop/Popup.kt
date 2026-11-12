package app.bluepop

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------- helpers ----------------------------------------------------------------------

val IconKeys: Map<String, ImageVector> = linkedMapOf(
    "bluetooth" to Icons.Rounded.Bluetooth, "headphones" to Icons.Rounded.Headphones, "headset" to Icons.Rounded.Headset,
    "speaker" to Icons.Rounded.Speaker, "watch" to Icons.Rounded.Watch, "car" to Icons.Rounded.DirectionsCar,
    "keyboard" to Icons.Rounded.Keyboard, "mouse" to Icons.Rounded.Mouse, "gamepad" to Icons.Rounded.SportsEsports
)

val FontKind.family: FontFamily
    get() = when (this) {
        FontKind.SANS -> FontFamily.SansSerif; FontKind.SERIF -> FontFamily.Serif
        FontKind.MONO -> FontFamily.Monospace; FontKind.CURSIVE -> FontFamily.Cursive
    }

fun Align.h(): Alignment.Horizontal = when (this) {
    Align.START -> Alignment.Start; Align.CENTER -> Alignment.CenterHorizontally; Align.END -> Alignment.End
}
fun Align.ta(): TextAlign = when (this) { Align.START -> TextAlign.Start; Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End }

fun statusText(e: PopupEvent) = when (e.type) {
    EventType.CONNECTED -> "Connected"; EventType.RECONNECTED -> "Reconnected"
    EventType.DISCONNECTED -> "Disconnected"; EventType.BATTERY -> "Battery updated"
}

fun ts(c: PopupConfig, scale: Float = 1f, alpha: Float = 1f, wOff: Int = 0) = TextStyle(
    fontFamily = c.font.family, fontSize = (c.fontSize * scale).sp,
    fontWeight = FontWeight((c.weight + wOff).coerceIn(100, 900)),
    color = Color(c.text).copy(alpha = alpha), textAlign = c.align.ta()
)

/** Room (dp) the window keeps on the slide side so slide animations are not clipped by the window. */
fun slideRoom(c: PopupConfig) = if (c.pos == Pos.CENTER) 24 else minOf(c.offsetY, 64)

@Composable
fun rememberBitmap(path: String?): ImageBitmap? =
    remember(path) { path?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }

// ---------- host: animation + window content ---------------------------------------------

data class UiState(val event: PopupEvent, val cfg: PopupConfig, val visible: Boolean, val key: Int)

@Composable
fun PopupHost(s: UiState, fill: Boolean, preview: Boolean = false, onTap: () -> Unit = {}) {
    val c = s.cfg
    val speed = c.speed.coerceIn(0.25f, 3f)
    val dur = (c.durationMs / speed).toInt().coerceAtLeast(60)
    val p = remember(s.key) { Animatable(0f) }
    LaunchedEffect(s.key, s.visible) {
        if (s.visible) {
            if (c.openAnim == AnimKind.SPRING) p.animateTo(1f, spring(0.55f, Spring.StiffnessMediumLow * speed))
            else p.animateTo(1f, tween(dur, easing = FastOutSlowInEasing))
        } else p.animateTo(0f, tween(dur, easing = FastOutLinearInEasing))
    }
    val density = LocalDensity.current
    val room = slideRoom(c)
    val slide = with(density) {
        if (fill) ((if (c.height > 0) c.height else 150) + c.offsetY + 60).dp.toPx() else (room + 20).dp.toPx()
    }
    val dir = if (c.pos == Pos.BOTTOM) 1f else -1f
    val kind = if (s.visible) c.openAnim else c.closeAnim
    val alignment = when (c.pos) { Pos.TOP -> Alignment.TopCenter; Pos.CENTER -> Alignment.Center; Pos.BOTTOM -> Alignment.BottomCenter }
    val outer = if (fill) Modifier.fillMaxSize().padding(vertical = (if (preview) minOf(c.offsetY, 24) else c.offsetY).dp)
    else Modifier.padding(
        start = 20.dp, end = 20.dp,
        top = (20 + (if (c.pos != Pos.BOTTOM) room else 0)).dp,
        bottom = (20 + (if (c.pos == Pos.BOTTOM) room else 0)).dp
    )
    Box(outer, contentAlignment = alignment) {
        Box(
            Modifier
                .graphicsLayer {   // read inside the layer block: animates without recomposition
                    val t = p.value
                    var ty = 0f; var al = 1f; var sc = 1f
                    when (kind) {
                        AnimKind.SLIDE -> { ty = dir * slide * (1f - t); al = (t * 3f).coerceIn(0f, 1f) }
                        AnimKind.FADE -> al = t.coerceIn(0f, 1f)
                        AnimKind.SCALE -> { sc = 0.6f + 0.4f * t; al = t.coerceIn(0f, 1f) }
                        AnimKind.SPRING -> { sc = 0.55f + 0.45f * t; ty = dir * slide * 0.5f * (1f - t); al = (t * 4f).coerceIn(0f, 1f) }
                        AnimKind.SLIDE_FADE -> { ty = dir * slide * 0.5f * (1f - t); al = t.coerceIn(0f, 1f) }
                    }
                    translationY = ty; alpha = al; scaleX = sc; scaleY = sc
                    transformOrigin = TransformOrigin(0.5f, if (c.pos == Pos.BOTTOM) 1f else 0f)
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
        ) { BluePop(s.event, c) }
    }
}

@Composable
fun BluePop(e: PopupEvent, c: PopupConfig) {
    when (c.style) {
        PopupStyle.POD -> PodStyle(e, c)
        PopupStyle.MONO -> MonoStyle(e, c)
        PopupStyle.CARD, PopupStyle.CUSTOM -> FlexStyle(e, c)
        PopupStyle.ISLAND -> IslandStyle(e, c)
        PopupStyle.FLOAT -> FloatStyle(e, c)
        PopupStyle.GLASS -> GlassStyle(e, c)
    }
}

// ---------- building blocks ---------------------------------------------------------------

@Composable
fun Shell(c: PopupConfig, pad: Dp, width: Dp = c.width.dp, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(c.radius.dp)
    val brush: Brush = if (c.gradient) Brush.linearGradient(listOf(Color(c.bg), Color(c.bg2))) else SolidColor(Color(c.bg))
    var m = Modifier.width(width).then(if (c.height > 0) Modifier.height(c.height.dp) else Modifier)
        .shadow((c.shadow * 28f).dp, shape, false, Color.Black, Color.Black)
        .clip(shape).background(brush, shape, c.alpha)
    if (c.borderW > 0f) m = m.border(c.borderW.dp, Color(c.border), shape)
    Box(m.padding(pad), contentAlignment = Alignment.Center, content = content)
}

@Composable
fun DeviceBadge(e: PopupEvent, c: PopupConfig, size: Dp, shape: Shape = CircleShape) {
    val bmp = rememberBitmap(e.imagePath)
    Box(Modifier.size(size).clip(shape).background(Color(c.accent).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(IconKeys[e.iconKey] ?: Icons.Rounded.Bluetooth, null, tint = Color(c.accent), modifier = Modifier.size(size * 0.52f))
    }
}

private fun levelColor(level: Int, c: PopupConfig) = Color(if (level <= 15) 0xFFFF5A5A else c.accent)

@Composable
fun Ring(level: Int, dia: Dp, c: PopupConfig, stroke: Dp = 5.dp, showText: Boolean = true) {
    val sweep by animateFloatAsState(level / 100f, tween(700, easing = FastOutSlowInEasing), label = "ring")
    val track = Color(c.text).copy(alpha = 0.14f)
    val col = levelColor(level, c)
    Box(Modifier.size(dia), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val box = Size(this.size.width - s, this.size.height - s)
            drawArc(track, 0f, 360f, false, Offset(s / 2, s / 2), box, style = Stroke(s))
            drawArc(col, -90f, 360f * sweep, false, Offset(s / 2, s / 2), box, style = Stroke(s, cap = StrokeCap.Round))
        }
        if (showText) Text("$level", style = TextStyle(color = Color(c.text), fontSize = (dia.value * 0.31f).sp,
            fontWeight = FontWeight.Bold, fontFamily = c.font.family))
    }
}

@Composable
fun Bar(level: Int, c: PopupConfig, modifier: Modifier) {
    val f by animateFloatAsState(level / 100f, tween(700, easing = FastOutSlowInEasing), label = "bar")
    Box(modifier.height(6.dp).clip(CircleShape).background(Color(c.text).copy(alpha = 0.14f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(CircleShape).background(levelColor(level, c)))
    }
}

@Composable
fun BatteryView(level: Int, c: PopupConfig, ring: Dp) {
    when (c.batStyle) {
        BatStyle.RING -> Ring(level, ring, c)
        BatStyle.BAR -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Bar(level, c, Modifier.width(ring * 1.6f)); Text("$level%", style = ts(c, 0.78f, 0.8f))
        }
        BatStyle.TEXT -> Text("$level%", style = ts(c, 0.95f))
    }
}

/** Shows only what the device actually reported. */
@Composable
fun BatterySummary(b: BatteryInfo, c: PopupConfig, ring: Dp, reportNone: Boolean) {
    val lvl = b.level
    when {
        lvl != null -> BatteryView(lvl, c, ring)
        b.left != null || b.right != null || b.case != null -> Text(
            listOfNotNull(b.left?.let { "L $it%" }, b.right?.let { "R $it%" }, b.case?.let { "Case $it%" }).joinToString("  "),
            style = ts(c, 0.78f, 0.75f))
        reportNone -> Text("Battery not reported", style = ts(c, 0.72f, 0.45f))
    }
}

private fun disc(e: PopupEvent) = e.type == EventType.DISCONNECTED

// ---------- styles ------------------------------------------------------------------------

@Composable
fun PodStyle(e: PopupEvent, c: PopupConfig) {
    Shell(c, (c.spacing + 8).dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(c.spacing.dp)) {
            DeviceBadge(e, c, if (c.compact) 48.dp else 76.dp, RoundedCornerShape(24.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = c.align.h()) {
                Text(e.name, style = ts(c, 1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                Text(statusText(e), style = ts(c, 0.8f, 0.6f, -150), modifier = Modifier.fillMaxWidth())
            }
            if (!disc(e) && !c.compact) {
                val b = e.battery
                val parts = listOfNotNull(b.left?.let { "L" to it }, b.right?.let { "R" to it }, b.case?.let { "Case" to it })
                if (parts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    parts.forEach { (l, v) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Ring(v, 52.dp, c); Text(l, style = ts(c, 0.7f, 0.6f).copy(textAlign = TextAlign.Center))
                        }
                    }
                } else b.level?.let { v ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BatteryView(v, c, 60.dp)
                        if (c.batStyle == BatStyle.RING) Text("Battery", style = ts(c, 0.7f, 0.6f).copy(textAlign = TextAlign.Center))
                    }
                } ?: Text("Battery not reported", style = ts(c, 0.72f, 0.45f))
            }
        }
    }
}

@Composable
fun MonoStyle(e: PopupEvent, c: PopupConfig) {
    Shell(c, 18.dp) {
        Canvas(Modifier.matchParentSize()) {
            val st = 11.dp.toPx()
            var y = st / 2
            while (y < size.height) {
                var x = st / 2
                while (x < size.width) { drawCircle(Color(c.text).copy(alpha = 0.08f), 1.3f, Offset(x, y)); x += st }
                y += st
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((c.spacing / 2).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(if (disc(e)) Color(0xFFFF5A5A) else Color(c.accent), CircleShape))
                Text(statusText(e).uppercase(), style = ts(c, 0.72f, 0.7f))
            }
            Text(e.name.uppercase(), style = ts(c, 1.25f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            if (!disc(e) && !c.compact) {
                val lvl = e.battery.level
                if (lvl != null) {
                    Text("BATT $lvl%", style = ts(c, 0.72f, 0.7f), modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        val col = levelColor(lvl, c)
                        repeat(10) { i ->
                            Box(Modifier.weight(1f).height(8.dp).background(
                                if (i < (lvl + 5) / 10) col else Color(c.text).copy(alpha = 0.14f), RoundedCornerShape(2.dp)))
                        }
                    }
                } else BatterySummary(e.battery, c, 0.dp, true)
            }
        }
    }
}

/** Used by Card (fixed preset) and Custom (every layout option is user-controlled). */
@Composable
fun FlexStyle(e: PopupEvent, c: PopupConfig) {
    val off = disc(e)
    val badge: @Composable () -> Unit = {
        DeviceBadge(e, c, if (c.compact) 38.dp else 54.dp,
            if (c.style == PopupStyle.CUSTOM) RoundedCornerShape((c.radius / 2).coerceAtMost(20).dp) else CircleShape)
    }
    val texts: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = c.align.h()) {
            Text(e.name, style = ts(c), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Text(statusText(e), style = ts(c, 0.78f, 0.6f, -150), modifier = Modifier.fillMaxWidth())
            if (!off && c.batPos == BatPos.BELOW && !c.compact) {
                Spacer(Modifier.height(6.dp)); BatterySummary(e.battery, c, 38.dp, true)
            }
        }
    }
    val batEnd = !off && c.batPos == BatPos.END
    val bat: @Composable () -> Unit = { BatterySummary(e.battery, c, 46.dp, false) }
    Shell(c, (c.spacing + 6).dp) {
        if (c.horizontal) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(c.spacing.dp)) {
            if (c.imagePos != ImagePos.END) badge()
            Box(Modifier.weight(1f)) { texts() }
            if (batEnd) bat()
            if (c.imagePos == ImagePos.END) badge()
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(c.spacing.dp)) {
            if (c.imagePos != ImagePos.END) badge()
            texts()
            if (batEnd) bat()
            if (c.imagePos == ImagePos.END) badge()
        }
    }
}

@Composable
fun IslandStyle(e: PopupEvent, c: PopupConfig) {
    var open by remember(e.address, c.compact) { mutableStateOf(false) }
    LaunchedEffect(e.address, c.compact) { delay(380); open = !c.compact }
    val w by animateDpAsState(if (open) c.width.dp else minOf(c.width, 150).dp,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "island")
    Shell(c, 10.dp, width = w) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeviceBadge(e, c, 34.dp)
            Column(Modifier.weight(1f)) {
                Text(e.name, style = ts(c, 0.95f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedVisibility(open) { Text(statusText(e), style = ts(c, 0.75f, 0.6f, -150), maxLines = 1) }
            }
            val lvl = e.battery.level
            if (!disc(e) && lvl != null) AnimatedVisibility(open) { Ring(lvl, 32.dp, c, 3.dp) }
        }
    }
}

@Composable
fun FloatStyle(e: PopupEvent, c: PopupConfig) {
    Shell(c, 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val a by rememberInfiniteTransition(label = "pulse").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(8.dp).alpha(a).background(if (disc(e)) Color(0xFFFF5A5A) else Color(c.accent), CircleShape))
            val lvl = if (disc(e)) null else e.battery.level
            Text("${e.name}  ·  ${statusText(e)}" + (lvl?.let { "  ·  $it%" } ?: ""), style = ts(c, 0.9f).copy(textAlign = TextAlign.Center),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GlassStyle(e: PopupEvent, c: PopupConfig) {
    Shell(c, (c.spacing + 8).dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(c.spacing.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceBadge(e, c, if (c.compact) 40.dp else 56.dp, RoundedCornerShape(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = ts(c, 1.15f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                    Text(statusText(e), style = ts(c, 0.8f, 0.65f, -150), modifier = Modifier.fillMaxWidth())
                }
            }
            if (!disc(e) && !c.compact) {
                val b = e.battery
                b.level?.let { v ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Bar(v, c, Modifier.weight(1f)); Text("$v%", style = ts(c, 0.85f))
                    }
                }
                val chips = listOfNotNull(b.left?.let { "L $it%" }, b.right?.let { "R $it%" }, b.case?.let { "Case $it%" })
                if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach {
                        Text(it, style = ts(c, 0.75f, 0.9f), modifier = Modifier.clip(CircleShape)
                            .background(Color(c.text).copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                if (!b.any) Text("Battery not reported", style = ts(c, 0.72f, 0.5f))
            }
        }
    }
}

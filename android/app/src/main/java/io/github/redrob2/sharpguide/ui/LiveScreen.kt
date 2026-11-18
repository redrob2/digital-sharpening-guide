package io.github.redrob2.sharpguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.redrob2.sharpguide.AppPrefs
import io.github.redrob2.sharpguide.TenthsMode
import io.github.redrob2.sharpguide.ble.BleClient
import io.github.redrob2.sharpguide.ble.LinkState
import io.github.redrob2.sharpguide.protocol.AngleFormat
import io.github.redrob2.sharpguide.protocol.LiveFrame
import io.github.redrob2.sharpguide.protocol.StickColour
import io.github.redrob2.sharpguide.protocol.StickState

val OnAngleGreen = Color(0xFF00B000)
val TooLowBlue = Color(0xFF1E5BFF)
val TooHighRed = Color(0xFFD32222)
val Neutral = Color(0xFF2B2B2B)

fun StickColour.toColor(): Color = when (this) {
    StickColour.GREEN -> OnAngleGreen
    StickColour.BLUE -> TooLowBlue
    StickColour.RED -> TooHighRed
    StickColour.NONE -> Neutral
}

/** Approximate advance widths (in em) of the default sans digits, used to size the number. */
private fun widthEm(s: String): Float {
    var w = 0f
    for (c in s) w += when (c) { '.' -> 0.28f; '-' -> 0.4f; else -> 0.58f }
    return w
}

@Composable
fun LiveScreen(ble: BleClient, prefs: AppPrefs, immersive: Boolean, onDevices: () -> Unit) {
    val frame by ble.live.collectAsState()
    val link by ble.state.collectAsState()
    val device by ble.device.collectAsState()
    val ready = link == LinkState.READY
    val f = if (ready) frame else null

    val bg = f?.colour?.toColor() ?: Neutral
    val tenths = when (prefs.tenthsMode) {
        TenthsMode.FOLLOW_STICK -> f?.tenths ?: false
        TenthsMode.WHOLE -> false
        TenthsMode.TENTHS -> true
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = immersive,
            ) { prefs.immersive = false },
    ) {
        val minSide: Dp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val small = (minSide.value * 0.045f).coerceIn(14f, 44f).sp

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
            if (!immersive) TopControls(prefs, device?.name, link, onDevices)

            // The angle: fills the remaining area. No animation, no smoothing.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val text: String
                val template: String
                if (f != null && f.hasAngle) {
                    text = AngleFormat.format(f.angleCenti, tenths)
                    template = if (tenths) "88.8" else "88"
                } else {
                    text = when {
                        f == null -> "—"
                        f.state == StickState.ACTIVE -> "—"
                        else -> f.state.label
                    }
                    template = if (text.length <= 2) "88" else "8".repeat(text.length)
                }
                val density = LocalDensity.current
                val wPx = with(density) { maxWidth.toPx() } * 0.96f
                val hPx = with(density) { maxHeight.toPx() }
                val em = maxOf(widthEm(template), widthEm(text))
                val fontPx = minOf(hPx / 1.05f, wPx / em).coerceAtLeast(1f)
                val fontSize = with(density) { fontPx.toSp() }
                Text(
                    text = text,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        lineHeightStyle = LineHeightStyle(
                            LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }

            if (f != null) StatusRow(f, small)
            Spacer(Modifier.size(8.dp))
        }

        if (!ready) DisconnectedOverlay(link, device == null, onDevices)
    }
}

@Composable
private fun TopControls(prefs: AppPrefs, name: String?, link: LinkState, onDevices: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // The name gives way first on a narrow (portrait) screen; the two
        // actions on the right always stay on one line.
        TextButton(onClick = onDevices, modifier = Modifier.weight(1f, fill = false)) {
            val status = when (link) {
                LinkState.READY -> "connected"
                LinkState.IDLE -> "not connected"
                LinkState.CONNECTING, LinkState.SETTING_UP -> "connecting"
                LinkState.RECONNECTING -> "reconnecting"
            }
            Text(
                "${name ?: "Choose stick"} · $status",
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            prefs.tenthsMode = TenthsMode.entries[(prefs.tenthsMode.ordinal + 1) % TenthsMode.entries.size]
        }) { Text("Decimals: ${prefs.tenthsMode.label}", color = Color.White, maxLines = 1, softWrap = false) }
        TextButton(onClick = { prefs.immersive = true }) {
            Text("Fullscreen", color = Color.White, maxLines = 1, softWrap = false)
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(f: LiveFrame, size: androidx.compose.ui.unit.TextUnit) {
    val style = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.Medium)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (f.state != StickState.ACTIVE) {
            Text(
                f.state.label,
                style = style.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
            )
        }
        Text("Target ${AngleFormat.tenthsToString(f.targetTenths)}°", style = style)
        Text(f.tolerance.label, style = style)
        Text("Side ${if (f.sideB) "B" else "A"} · ${f.strokesCurrentSide} strokes", style = style)
        Text("${f.onAnglePct}% on angle", style = style)
        Text(battery(f), style = style)
    }
}

private fun battery(f: LiveFrame): String {
    val bars = f.batteryBars
    val body = if (bars < 0) "–" else "▮".repeat(bars.coerceAtMost(4)) + "▯".repeat(4 - bars.coerceIn(0, 4))
    return "Battery $body" + if (f.charging) " +" else ""
}

@Composable
private fun DisconnectedOverlay(link: LinkState, noDevice: Boolean, onDevices: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val msg = when {
                noDevice || link == LinkState.IDLE -> "Not connected"
                link == LinkState.CONNECTING -> "Connecting…"
                link == LinkState.SETTING_UP -> "Setting up…"
                else -> "Disconnected — reconnecting…"
            }
            Text(msg, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.size(16.dp))
            Button(onClick = onDevices) { Text(if (noDevice) "Find stick" else "Choose another stick") }
        }
    }
}

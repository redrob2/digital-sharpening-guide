package io.github.redrob2.sharpguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.redrob2.sharpguide.ble.BleClient
import io.github.redrob2.sharpguide.ble.LinkState
import io.github.redrob2.sharpguide.protocol.AngleFormat
import io.github.redrob2.sharpguide.protocol.StickSettings
import io.github.redrob2.sharpguide.protocol.Tolerance
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val PRESETS = listOf(12, 14, 15, 17, 20, 22, 25, 28)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(ble: BleClient) {
    val stick by ble.settings.collectAsState()
    val link by ble.state.collectAsState()
    val ready = link == LinkState.READY
    val scope = rememberCoroutineScope()

    // Local edit buffer, re-seeded whenever the stick notifies what it applied.
    val base = stick ?: StickSettings()
    var target by remember(stick) { mutableIntStateOf(base.targetTenths.coerceIn(50, 450)) }
    var tol by remember(stick) { mutableStateOf(base.tolerance) }
    var buzzer by remember(stick) { mutableStateOf(base.buzzer) }
    var steady by remember(stick) { mutableStateOf(base.steady) }
    var tenths by remember(stick) { mutableStateOf(base.tenths) }
    // 0 from the stick means its firmware has no brightness setting.
    val hasBrightness = base.brightness != 0
    var bright by remember(stick) { mutableIntStateOf(if (hasBrightness) base.brightness else 100) }
    // Cleared when the stick notifies back what it applied.
    var status by remember(stick) { mutableStateOf<String?>(null) }

    val edited = StickSettings(tol, target, buzzer, steady, tenths,
        brightness = if (hasBrightness) bright else 0)
    val dirty = stick == null || edited.copy(version = stick!!.version) != stick

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Stick settings", style = MaterialTheme.typography.headlineSmall)
        if (!ready) Text("Not connected — changes can be written once the stick is back.", color = MaterialTheme.colorScheme.error)

        Text("Target angle", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { target = (target - 5).coerceAtLeast(50) }) { Text("− 0.5") }
            Text("${AngleFormat.tenthsToString(target)}°", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { target = (target + 5).coerceAtMost(450) }) { Text("+ 0.5") }
        }
        Slider(
            value = target / 10f,
            onValueChange = { target = ((it * 2).roundToInt() * 5).coerceIn(50, 450) },
            valueRange = 5f..45f,
            steps = 79,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { p ->
                FilterChip(selected = target == p * 10, onClick = { target = p * 10 }, label = { Text("$p°") })
            }
        }

        Text("Tolerance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tolerance.entries.forEach { t ->
                FilterChip(
                    selected = tol == t, onClick = { tol = t },
                    label = { Text("${t.label} ${t.name.lowercase().replaceFirstChar { it.uppercase() }}") },
                )
            }
        }

        if (hasBrightness) {
            Text("Stick screen brightness", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Slider(
                    modifier = Modifier.weight(1f),
                    value = bright.toFloat(),
                    onValueChange = { bright = ((it / 10f).roundToInt() * 10).coerceIn(10, 100) },
                    // Applies as soon as you let go, so you can judge it on the stick.
                    onValueChangeFinished = {
                        if (ready) scope.launch { ble.writeSettings(edited.copy(brightness = bright)) }
                    },
                    valueRange = 10f..100f,
                    steps = 8,
                )
                Text("$bright %", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Lower saves battery: the backlight is the stick's biggest drain. Applies when you let go.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        SwitchRow("Buzzer", "Beep when out of tolerance", buzzer) { buzzer = it }
        SwitchRow("Steady mode", "Extra smoothing against stroke sway", steady) { steady = it }
        SwitchRow("Tenths", "Show one decimal on the stick", tenths) { tenths = it }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = ready, onClick = {
                status = "Writing…"
                scope.launch {
                    status = if (ble.writeSettings(edited)) "Sent — waiting for the stick…" else "Write failed"
                }
            }) { Text("Write to stick") }
            if (dirty && stick != null) Text("Unsaved changes", color = Color(0xFFFFC107))
        }
        stick?.let { s ->
            Text(
                "Stick has: ${AngleFormat.tenthsToString(s.targetTenths)}°, ${s.tolerance.label}, " +
                    "buzzer ${onOff(s.buzzer)}, steady ${onOff(s.steady)}, tenths ${onOff(s.tenths)}" +
                    (if (s.brightness != 0) ", brightness ${s.brightness} %" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        status?.let { Text(it) }
        Spacer(Modifier.size(24.dp))
    }
}

private fun onOff(b: Boolean) = if (b) "on" else "off"

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

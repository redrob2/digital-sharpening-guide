package io.github.redrob2.sharpguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.redrob2.sharpguide.ble.BleClient

@Composable
fun DeviceScreen(
    ble: BleClient,
    needPermission: Boolean,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) {
    val found by ble.found.collectAsState()
    val scanning by ble.scanning.collectAsState()
    val error by ble.lastError.collectAsState()
    val current by ble.device.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose your stick", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text(
                "Turn the stick on. It advertises as SharpGuide-XXXX.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.size(12.dp))
            if (needPermission) {
                Text("The app needs the Nearby devices (Bluetooth) permission to find the stick.")
                Spacer(Modifier.size(8.dp))
                Button(onClick = onRequestPermission) { Text("Grant permission") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { if (scanning) ble.stopScan() else ble.startScan() }) {
                        Text(if (scanning) "Stop scan" else "Scan again")
                    }
                    if (scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    if (current != null) OutlinedButton(onClick = { ble.disconnect(forget = true) }) {
                        Text("Forget ${current?.name}")
                    }
                }
            }
            error?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.size(12.dp))
            if (found.isEmpty() && scanning) Text("Searching…")
            if (found.isEmpty() && !scanning && !needPermission) Text("No sticks found yet.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found, key = { it.address }) { d ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            ble.connect(d.address, d.name)
                            onClose()
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                            Text("${d.address} · ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

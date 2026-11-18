package io.github.redrob2.sharpguide.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.redrob2.sharpguide.SharpGuideApp
import io.github.redrob2.sharpguide.ble.LinkState
import io.github.redrob2.sharpguide.ble.OtaUi
import io.github.redrob2.sharpguide.protocol.OtaPrep
import io.github.redrob2.sharpguide.protocol.otaChunkSize

private val Good = Color(0xFF3DDC6A)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirmwareScreen(app: SharpGuideApp) {
    val ble = app.ble
    val fw = app.firmware
    val info by ble.info.collectAsState()
    val link by ble.state.collectAsState()
    val mtu by ble.mtu.collectAsState()
    val ota by app.ota.ui.collectAsState()
    val ready = link == LinkState.READY
    val live by ble.live.collectAsState()
    val lowBattery = ready && live?.tooLowForUpdate == true
    val board = info?.board ?: ""

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fw.pickedFile(uri, board)
    }

    // After a successful update the stick reboots; refresh INFO once it is back.
    LaunchedEffect(link, ota) {
        if (ready && ota is OtaUi.Done) ble.refreshInfo()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Firmware", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (info == null) Text(if (ready) "Reading stick info…" else "Not connected")
                info?.let {
                    Text("Installed: ${it.fw}", style = MaterialTheme.typography.titleMedium)
                    Text("Board: ${it.board.ifEmpty { "?" }} · protocol ${it.proto ?: "?"}")
                    if (ready) Text("MTU $mtu · ${otaChunkSize(mtu)}-byte chunks", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("Get an update", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = app.prefs.repo,
            onValueChange = { app.prefs.repo = it.trim() },
            label = { Text("GitHub repo (owner/name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = fw.busy == null && !ota.busy, onClick = { fw.checkGitHub(board) }) { Text("Check GitHub") }
            OutlinedButton(enabled = fw.busy == null && !ota.busy, onClick = {
                picker.launch(arrayOf("application/octet-stream", "application/macbinary", "*/*"))
            }) { Text("Pick .bin file…") }
        }
        fw.busy?.let { Text(it) }
        fw.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        fw.release?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Latest release: ${r.tag.ifEmpty { r.name }}", style = MaterialTheme.typography.titleMedium)
                    val installed = info?.fw
                    if (installed != null && r.tag.trimStart('v', 'V') == installed.trimStart('v', 'V')) {
                        Text("That is the version already on the stick.", color = Good)
                    }
                    fw.asset?.let { a ->
                        Text("${a.name} (${a.size / 1024} KB)")
                        Button(enabled = fw.busy == null && !ota.busy, onClick = { fw.downloadAsset(board) }) {
                            Text("Download")
                        }
                    }
                }
            }
        }

        when (val p = fw.prep) {
            null -> Unit
            is OtaPrep.Error -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(fw.sourceName ?: "File", style = MaterialTheme.typography.titleMedium)
                    Text("Refused: ${p.message}", color = MaterialTheme.colorScheme.error)
                }
            }
            is OtaPrep.Ok -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(fw.sourceName ?: "Image", style = MaterialTheme.typography.titleMedium)
                    Text("Version ${p.version ?: "unknown"} · board ${p.board} · ${p.image.size / 1024} KB")
                    if (p.strippedMerged) Text("Merged flasher image: sending the app part only (from 0x10000).",
                        style = MaterialTheme.typography.bodySmall)
                    Text("CRC-32 %08X".format(p.crc32), style = MaterialTheme.typography.bodySmall)
                    if (!ota.busy) Button(enabled = ready && !lowBattery, onClick = { app.ota.start(p) }) {
                        Text("Upload to stick")
                    }
                    if (lowBattery && !ota.busy) Text(
                        "The stick's battery is too low for an update. Plug it into USB first.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!ready && !ota.busy) Text("Connect to the stick to upload.", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        OtaProgress(ota, onCancel = { app.ota.cancel() }, onDismiss = { app.ota.reset() })
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun OtaProgress(ota: OtaUi, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (ota) {
        OtaUi.Idle -> Unit
        OtaUi.Erasing -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Erasing the update slot on the stick…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        is OtaUi.Sending -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { ota.sent.toFloat() / ota.total }, modifier = Modifier.fillMaxWidth())
            val eta = "%d:%02d".format(ota.etaSec / 60, ota.etaSec % 60)
            Text("${ota.sent / 1024} / ${ota.total / 1024} KB · ${"%.1f".format(ota.bytesPerSec / 1024)} KB/s · ETA $eta")
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        OtaUi.Verifying -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Verifying on the stick…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        OtaUi.Done -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Updated, stick is rebooting. The app reconnects by itself.", color = Good)
            OutlinedButton(onClick = onDismiss) { Text("OK") }
        }
        is OtaUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Update failed: ${ota.message}", color = MaterialTheme.colorScheme.error)
            Text("The stick keeps running its current firmware.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onDismiss) { Text("OK") }
        }
        OtaUi.Cancelled -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Update cancelled.")
            OutlinedButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

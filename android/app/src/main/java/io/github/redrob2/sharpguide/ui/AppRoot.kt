package io.github.redrob2.sharpguide.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.redrob2.sharpguide.SharpGuideApp
import io.github.redrob2.sharpguide.ble.LinkState

private val scheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF3DDC6A),
    onPrimary = Color(0xFF00210B),
    background = Color(0xFF121212),
    surface = Color(0xFF1A1A1A),
)

@Composable
fun SharpTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Filled.Home),
    SETTINGS("Settings", Icons.Filled.Settings),
    FIRMWARE("Firmware", Icons.Filled.Build),
}

@Composable
fun AppRoot(app: SharpGuideApp, keepScreenOn: (Boolean) -> Unit, fullscreen: (Boolean) -> Unit) {
    val ble = app.ble
    val link by ble.state.collectAsState()
    val device by ble.device.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    var permsAsked by rememberSaveable { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) {
            if (ble.device.value == null) { showDevices = true; ble.startScan() } else ble.start()
        }
    }

    LaunchedEffect(Unit) {
        if (!ble.hasPermissions()) {
            if (!permsAsked) { permsAsked = true; permLauncher.launch(ble.requiredPermissions()) }
        } else if (device == null) {
            showDevices = true
            ble.startScan()
        }
    }

    val connected = link == LinkState.READY || link == LinkState.SETTING_UP
    LaunchedEffect(connected) { keepScreenOn(connected) }

    val immersive = app.prefs.immersive && Tab.entries[tab] == Tab.LIVE && !showDevices
    LaunchedEffect(immersive) { fullscreen(immersive) }

    if (showDevices) {
        BackHandler { ble.stopScan(); showDevices = false }
        DeviceScreen(
            ble = ble,
            needPermission = !ble.hasPermissions(),
            onRequestPermission = { permLauncher.launch(ble.requiredPermissions()) },
            onClose = { ble.stopScan(); showDevices = false },
        )
        return
    }

    Scaffold(
        bottomBar = {
            if (!immersive) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = Tab.entries[tab] == t,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        // Live paints its colour edge to edge (behind the status bar); other tabs get insets.
        val mod = when {
            Tab.entries[tab] == Tab.LIVE -> Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())
            else -> Modifier.fillMaxSize().padding(pad)
        }
        Box(mod) {
            when (Tab.entries[tab]) {
                Tab.LIVE -> LiveScreen(
                    ble = ble,
                    prefs = app.prefs,
                    immersive = immersive,
                    onDevices = { showDevices = true; ble.startScan() },
                )
                Tab.SETTINGS -> SettingsScreen(ble)
                Tab.FIRMWARE -> FirmwareScreen(app)
            }
        }
    }
}

package io.github.redrob2.sharpguide

import android.app.Application
import io.github.redrob2.sharpguide.ble.BleClient
import io.github.redrob2.sharpguide.ble.OtaController
import io.github.redrob2.sharpguide.firmware.FirmwareModel

/** Holds the app-wide singletons so the BLE link survives rotation and tab changes. */
class SharpGuideApp : Application() {
    lateinit var ble: BleClient
        private set
    lateinit var ota: OtaController
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var firmware: FirmwareModel
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        ble = BleClient(this)
        ota = OtaController(ble)
        firmware = FirmwareModel(this, prefs)
    }
}

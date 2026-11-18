package io.github.redrob2.sharpguide.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import io.github.redrob2.sharpguide.protocol.DEVICE_NAME_PREFIX
import io.github.redrob2.sharpguide.protocol.DeviceInfo
import io.github.redrob2.sharpguide.protocol.LiveFrame
import io.github.redrob2.sharpguide.protocol.OtaReply
import io.github.redrob2.sharpguide.protocol.StickSettings
import io.github.redrob2.sharpguide.protocol.Uuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class LinkState {
    /** No device chosen (or the user disconnected). */
    IDLE,
    CONNECTING,
    /** Connected, running MTU/discovery/notify setup. */
    SETTING_UP,
    READY,
    /** Link dropped; retrying quietly. */
    RECONNECTING,
}

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/**
 * Owns the single GATT connection. Android allows one outstanding GATT operation at a time,
 * so every read/write/descriptor/MTU/discovery request goes through [op], which holds a mutex
 * until the matching callback arrives (or a timeout).
 *
 * Notifications are parsed on the binder thread straight into StateFlows.
 */
@SuppressLint("MissingPermission")
class BleClient(private val ctx: Context) {
    private val tag = "BleClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = ctx.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    private val _state = MutableStateFlow(LinkState.IDLE)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _live = MutableStateFlow<LiveFrame?>(null)
    val live: StateFlow<LiveFrame?> = _live.asStateFlow()

    private val _settings = MutableStateFlow<StickSettings?>(null)
    val settings: StateFlow<StickSettings?> = _settings.asStateFlow()

    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    private val _mtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _device = MutableStateFlow(prefs.getString(KEY_ADDR, null)?.let {
        FoundDevice(it, prefs.getString(KEY_NAME, null) ?: it, 0)
    })
    /** The remembered device (target of auto-reconnect). */
    val device: StateFlow<FoundDevice?> = _device.asStateFlow()

    /** OTA_CTRL notifications. Consumers drain it before sending BEGIN. */
    val otaReplies = Channel<OtaReply>(Channel.UNLIMITED)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var wantConnected = false
    private var reconnectJob: Job? = null
    private var scanStopJob: Job? = null
    private var attempts = 0

    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<OpResult>? = null

    private class OpResult(val status: Int, val value: ByteArray? = null)

    // ---------------------------------------------------------------- permissions

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Called once permissions are granted: reconnect to the remembered stick. */
    fun start() {
        if (!hasPermissions()) return
        val dev = _device.value ?: return
        if (!wantConnected) connect(dev.address, dev.name)
    }

    // ---------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _lastError.value = "Scan failed ($errorCode)"
        }

        private fun handle(r: ScanResult) {
            val rec = r.scanRecord
            val name = rec?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: ""
            val hasService = rec?.serviceUuids?.contains(SERVICE_PARCEL) == true
            if (!hasService && !name.startsWith(DEVICE_NAME_PREFIX)) return
            val d = FoundDevice(r.device.address, name.ifEmpty { r.device.address }, r.rssi)
            val list = _found.value
            val i = list.indexOfFirst { it.address == d.address }
            _found.value = if (i < 0) list + d else list.toMutableList().also { it[i] = d }
        }
    }

    fun startScan() {
        if (!hasPermissions()) { _lastError.value = "Bluetooth permission not granted"; return }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !bluetoothOn) { _lastError.value = "Bluetooth is off"; return }
        stopScan()
        _found.value = emptyList()
        _lastError.value = null
        // No ScanFilter: the 128-bit UUID and the name may be split between the advertisement
        // and the scan response, so match either one in the callback.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure { _lastError.value = "Scan failed: ${it.message}"; return }
        _scanning.value = true
        scanStopJob = scope.launch { delay(15_000); stopScan() }
    }

    fun stopScan() {
        scanStopJob?.cancel()
        if (_scanning.value) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            _scanning.value = false
        }
    }

    // ---------------------------------------------------------------- connection

    fun connect(address: String, name: String) {
        stopScan()
        prefs.edit().putString(KEY_ADDR, address).putString(KEY_NAME, name).apply()
        _device.value = FoundDevice(address, name, 0)
        wantConnected = true
        attempts = 0
        reconnectJob?.cancel()
        closeGatt()
        openGatt()
    }

    /** User-initiated disconnect: stop auto-reconnect until a device is chosen again. */
    fun disconnect(forget: Boolean = false) {
        wantConnected = false
        reconnectJob?.cancel()
        closeGatt()
        _state.value = LinkState.IDLE
        _live.value = null
        if (forget) {
            prefs.edit().remove(KEY_ADDR).remove(KEY_NAME).apply()
            _device.value = null
        }
    }

    private fun openGatt() {
        val addr = _device.value?.address ?: return
        val a = adapter
        if (a == null || !a.isEnabled || !hasPermissions()) { scheduleReconnect(); return }
        _state.value = if (attempts == 0) LinkState.CONNECTING else LinkState.RECONNECTING
        val dev: BluetoothDevice = runCatching { a.getRemoteDevice(addr) }.getOrNull()
            ?: run { _lastError.value = "Bad address $addr"; return }
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) scheduleReconnect()
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        pending?.complete(OpResult(-1))
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        _state.value = LinkState.RECONNECTING
        reconnectJob?.cancel()
        val wait = (500L * (1 shl attempts.coerceAtMost(3))).coerceAtMost(4_000)
        attempts++
        reconnectJob = scope.launch {
            delay(wait)
            if (wantConnected && gatt == null) openGatt()
        }
    }

    private fun onLinkDown(g: BluetoothGatt) {
        if (g !== gatt) { runCatching { g.close() }; return }
        gatt = null
        pending?.complete(OpResult(-1))
        runCatching { g.close() }
        if (wantConnected) scheduleReconnect() else _state.value = LinkState.IDLE
    }

    private suspend fun setup(g: BluetoothGatt) {
        _state.value = LinkState.SETTING_UP
        val mtuRes = op(3_000) { it.requestMtu(517) }
        if (mtuRes == null) _mtu.value = 23
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

        val disc = op(10_000) { it.discoverServices() }
        if (disc == null || disc.status != BluetoothGatt.GATT_SUCCESS || g !== gatt) {
            Log.w(tag, "discovery failed"); g.disconnect(); return
        }
        val svc = g.getService(Uuids.SERVICE)
        if (svc == null) {
            _lastError.value = "That device is not a Sharpening Guide (service missing)."
            g.disconnect(); return
        }
        for (u in listOf(Uuids.LIVE, Uuids.SETTINGS, Uuids.OTA_CTRL)) {
            val ch = svc.getCharacteristic(u) ?: continue
            if (!enableNotify(g, ch)) Log.w(tag, "notify enable failed for $u")
        }
        read(Uuids.INFO)?.let { _info.value = DeviceInfo.parse(String(it, Charsets.UTF_8)) }
        read(Uuids.SETTINGS)?.let { v -> StickSettings.parse(v)?.let { _settings.value = it } }
        if (g === gatt) {
            attempts = 0
            _lastError.value = null
            _state.value = LinkState.READY
        }
    }

    private suspend fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val d = ch.getDescriptor(Uuids.CCCD) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val r = op(3_000) {
            if (Build.VERSION.SDK_INT >= 33) it.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { d.value = v; it.writeDescriptor(d) }
        }
        return r?.status == BluetoothGatt.GATT_SUCCESS
    }

    // ---------------------------------------------------------------- GATT ops

    private suspend fun op(timeoutMs: Long, start: (BluetoothGatt) -> Boolean): OpResult? =
        opMutex.withLock {
            val g = gatt ?: return null
            val d = CompletableDeferred<OpResult>()
            pending = d
            var started = false
            for (i in 0 until 5) {               // the stack can briefly report "busy"
                if (start(g)) { started = true; break }
                delay(15)
            }
            if (!started) { pending = null; return null }
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            pending = null
            if (r == null || r.status == -1) null else r
        }

    private fun characteristic(u: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(Uuids.SERVICE)?.getCharacteristic(u)

    suspend fun read(u: UUID): ByteArray? {
        val ch = characteristic(u) ?: return null
        val r = op(3_000) { it.readCharacteristic(ch) } ?: return null
        return if (r.status == BluetoothGatt.GATT_SUCCESS) r.value else null
    }

    suspend fun write(u: UUID, value: ByteArray, timeoutMs: Long = 5_000): Boolean {
        val ch = characteristic(u) ?: return false
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val r = op(timeoutMs) {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
            } else @Suppress("DEPRECATION") run {
                ch.writeType = type
                ch.value = value
                it.writeCharacteristic(ch)
            }
        }
        return r?.status == BluetoothGatt.GATT_SUCCESS
    }

    suspend fun writeSettings(s: StickSettings): Boolean = write(Uuids.SETTINGS, s.encode())

    suspend fun refreshInfo() {
        read(Uuids.INFO)?.let { _info.value = DeviceInfo.parse(String(it, Charsets.UTF_8)) }
    }

    // ---------------------------------------------------------------- callbacks

    private fun onValue(u: UUID, v: ByteArray) {
        when (u) {
            Uuids.LIVE -> LiveFrame.parse(v)?.let { _live.value = it }
            Uuids.SETTINGS -> StickSettings.parse(v)?.let { _settings.value = it }
            Uuids.OTA_CTRL -> OtaReply.parse(v)?.let { otaReplies.trySend(it) }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // connectGatt() can deliver this before its return value has been stored.
                if (gatt == null && wantConnected && g.device.address == _device.value?.address) gatt = g
                if (g !== gatt) return
                scope.launch { setup(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED ||
                status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(tag, "link down status=$status state=$newState")
                onLinkDown(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _mtu.value = mtu
            pending?.complete(OpResult(status))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(OpResult(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(OpResult(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(OpResult(status))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            pending?.complete(OpResult(status, value))
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(OpResult(status, ch.value?.copyOf()))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) = onValue(ch.uuid, value)

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ch.value?.let { onValue(ch.uuid, it) }
        }
    }

    companion object {
        private const val KEY_ADDR = "last_address"
        private const val KEY_NAME = "last_name"
        private val SERVICE_PARCEL = ParcelUuid(Uuids.SERVICE)
    }
}

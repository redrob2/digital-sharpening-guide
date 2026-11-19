package io.github.redrob2.sharpguide.ble

import android.os.SystemClock
import io.github.redrob2.sharpguide.protocol.Ota
import io.github.redrob2.sharpguide.protocol.OtaPrep
import io.github.redrob2.sharpguide.protocol.OtaReply
import io.github.redrob2.sharpguide.protocol.Uuids
import io.github.redrob2.sharpguide.protocol.otaChunkSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed class OtaUi {
    data object Idle : OtaUi()
    data object Erasing : OtaUi()
    data class Sending(val sent: Int, val total: Int, val bytesPerSec: Double, val etaSec: Int) : OtaUi()
    data object Verifying : OtaUi()
    data object Done : OtaUi()
    data class Failed(val message: String) : OtaUi()
    data object Cancelled : OtaUi()

    val busy: Boolean get() = this is Erasing || this is Sending || this is Verifying
}

/** Runs the BEGIN → DATA… → END upload from docs/companion-protocol.md. */
class OtaController(private val ble: BleClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _ui = MutableStateFlow<OtaUi>(OtaUi.Idle)
    val ui: StateFlow<OtaUi> = _ui.asStateFlow()
    private var job: Job? = null

    fun reset() { if (!_ui.value.busy) _ui.value = OtaUi.Idle }

    fun start(img: OtaPrep.Ok) {
        if (_ui.value.busy) return
        job = scope.launch {
            try {
                _ui.value = run(img.image, img.crc32)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { ble.write(Uuids.OTA_CTRL, Ota.abort(), 2_000) }
                _ui.value = OtaUi.Cancelled
            }
        }
    }

    fun cancel() { job?.cancel() }

    private fun drain() { while (ble.otaReplies.tryReceive().isSuccess) Unit }

    private suspend fun awaitReply(cmd: Int, timeoutMs: Long): OtaReply? = withTimeoutOrNull(timeoutMs) {
        var r: OtaReply
        do { r = ble.otaReplies.receive() } while (r.cmd != cmd && r.ok)
        r
    }

    private suspend fun run(img: ByteArray, crc: Long): OtaUi {
        if (ble.state.value != LinkState.READY) return OtaUi.Failed("Not connected to the stick.")
        drain()
        _ui.value = OtaUi.Erasing
        if (!ble.write(Uuids.OTA_CTRL, Ota.begin(img.size))) return OtaUi.Failed("Could not send BEGIN.")
        // The stick waits up to 20 s for button A, then erases (a few seconds).
        val begin = awaitReply(Ota.CMD_BEGIN, 35_000)
            ?: return OtaUi.Failed("The stick did not answer. Press A on the stick when it asks, then try again.")
        if (!begin.ok) return OtaUi.Failed("BEGIN refused: ${Ota.statusText(begin.status)}")

        val chunk = otaChunkSize(ble.mtu.value)
        val t0 = SystemClock.elapsedRealtime()
        var lastUi = 0L
        var off = 0
        while (off < img.size) {
            val n = minOf(chunk, img.size - off)
            if (!ble.write(Uuids.OTA_DATA, img.copyOfRange(off, off + n))) {
                return OtaUi.Failed("Write failed at ${off / 1024} KB (link lost?).")
            }
            off += n
            ble.otaReplies.tryReceive().getOrNull()?.let {
                if (!it.ok) return OtaUi.Failed("Stick aborted the update: ${Ota.statusText(it.status)}")
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastUi >= 150 || off == img.size) {
                lastUi = now
                val secs = ((now - t0).coerceAtLeast(1)) / 1000.0
                val rate = off / secs
                val eta = if (rate > 0) ((img.size - off) / rate).toInt() else 0
                _ui.value = OtaUi.Sending(off, img.size, rate, eta)
            }
        }

        _ui.value = OtaUi.Verifying
        if (!ble.write(Uuids.OTA_CTRL, Ota.end(crc))) return OtaUi.Failed("Could not send END.")
        val end = awaitReply(Ota.CMD_END, 20_000)
            ?: return OtaUi.Failed("The stick did not confirm END.")
        if (!end.ok) return OtaUi.Failed("Update rejected: ${Ota.statusText(end.status)}")
        return OtaUi.Done
    }
}

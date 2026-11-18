package io.github.redrob2.sharpguide.firmware

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.redrob2.sharpguide.AppPrefs
import io.github.redrob2.sharpguide.protocol.AssetPicker
import io.github.redrob2.sharpguide.protocol.FirmwareImage
import io.github.redrob2.sharpguide.protocol.OtaPrep
import io.github.redrob2.sharpguide.protocol.Release
import io.github.redrob2.sharpguide.protocol.ReleaseAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** UI state of the Firmware screen; lives in the Application so it survives tab changes. */
class FirmwareModel(private val ctx: Context, private val prefs: AppPrefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var busy by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var release by mutableStateOf<Release?>(null); private set
    var asset by mutableStateOf<ReleaseAsset?>(null); private set
    var sourceName by mutableStateOf<String?>(null); private set
    var prep by mutableStateOf<OtaPrep?>(null); private set

    fun checkGitHub(board: String) {
        if (busy != null) return
        busy = "Checking GitHub…"; error = null; release = null; asset = null
        scope.launch {
            try {
                val r = GitHub.latestRelease(prefs.repo)
                release = r
                asset = AssetPicker.pick(r.assets, board)
                if (asset == null) {
                    error = if (board.isEmpty()) "Connect to the stick first so the app knows its board."
                    else "Release ${r.tag} has no .bin for board $board."
                }
            } catch (e: Exception) {
                error = "GitHub check failed: ${e.message}"
            } finally { busy = null }
        }
    }

    fun downloadAsset(board: String) {
        val a = asset ?: return
        if (busy != null) return
        busy = "Downloading ${a.name}…"; error = null; prep = null
        scope.launch {
            try {
                val bytes = GitHub.download(a.downloadUrl) { got, total ->
                    if (total > 0) busy = "Downloading ${a.name}… ${got * 100 / total}%"
                }
                load(a.name, bytes, board)
            } catch (e: Exception) {
                error = "Download failed: ${e.message}"
            } finally { busy = null }
        }
    }

    fun pickedFile(uri: Uri, board: String) {
        if (busy != null) return
        busy = "Reading file…"; error = null; prep = null
        scope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val cr = ctx.contentResolver
                    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment ?: "firmware.bin"
                    val bytes = cr.openInputStream(uri)?.use { s ->
                        val b = s.readBytes()
                        if (b.size > 8 * 1024 * 1024) throw IOException("File is too large to be firmware")
                        b
                    } ?: throw IOException("Could not open the file")
                    name to bytes
                }
                load(name, bytes, board)
            } catch (e: Exception) {
                error = "Could not read file: ${e.message}"
            } finally { busy = null }
        }
    }

    private suspend fun load(name: String, bytes: ByteArray, board: String) {
        sourceName = name
        prep = withContext(Dispatchers.Default) { FirmwareImage.prepare(bytes, board) }
    }

    fun clearImage() { prep = null; sourceName = null }
}

package io.github.redrob2.sharpguide.protocol

import java.util.zip.CRC32

/** Result of turning a downloaded/picked .bin into an image the stick will accept. */
sealed class OtaPrep {
    data class Ok(
        val image: ByteArray,
        val board: String,
        val strippedMerged: Boolean,
        /** Version string from the ESP-IDF app descriptor, if present. */
        val version: String?,
        val crc32: Long,
    ) : OtaPrep()

    data class Error(val message: String) : OtaPrep()
}

object FirmwareImage {
    const val MERGED_APP_OFFSET = 0x10000
    const val PARTITION_TABLE_OFFSET = 0x8000
    const val IMAGE_MAGIC = 0xE9.toByte()
    private val TAG = "SG-BOARD:".toByteArray(Charsets.US_ASCII)

    fun isMerged(b: ByteArray): Boolean =
        b.size > PARTITION_TABLE_OFFSET + 1 &&
            b[PARTITION_TABLE_OFFSET] == 0xAA.toByte() &&
            b[PARTITION_TABLE_OFFSET + 1] == 0x50.toByte()

    /** All `SG-BOARD:<x>;` tags found in the image, in order. */
    fun findBoardTags(b: ByteArray): List<String> {
        val out = ArrayList<String>(1)
        var i = 0
        val last = b.size - TAG.size
        outer@ while (i <= last) {
            for (k in TAG.indices) {
                if (b[i + k] != TAG[k]) { i++; continue@outer }
            }
            val start = i + TAG.size
            var j = start
            while (j < b.size && j - start < 16 && b[j] != ';'.code.toByte()) {
                val c = b[j].toInt()
                if (c !in 0x21..0x7E) break
                j++
            }
            if (j < b.size && b[j] == ';'.code.toByte() && j > start) {
                out.add(String(b, start, j - start, Charsets.US_ASCII))
            }
            i = j.coerceAtLeast(i + 1)
        }
        return out
    }

    /**
     * ESP-IDF app images carry an esp_app_desc_t right after the 24-byte image header and
     * the first 8-byte segment header: magic 0xABCD5432 at offset 32, version[32] at 48.
     */
    fun appVersion(img: ByteArray): String? {
        // Sharpening Guide builds carry their own "SG-FW:<version>;" tag. Prefer
        // it: Arduino builds put the IDF version in the descriptor field below.
        fwTag(img)?.let { return it }
        if (img.size < 80) return null
        val magic = u16(img, 32).toLong() or (u16(img, 34).toLong() shl 16)
        if (magic != 0xABCD5432L) return null
        var end = 48
        while (end < 80 && img[end] != 0.toByte()) end++
        return String(img, 48, end - 48, Charsets.US_ASCII).ifBlank { null }
    }

    /** The version from the first "SG-FW:<version>;" tag, or null if there is none. */
    fun fwTag(b: ByteArray): String? {
        val key = "SG-FW:".toByteArray(Charsets.US_ASCII)
        var i = 0
        outer@ while (i <= b.size - key.size) {
            for (k in key.indices) {
                if (b[i + k] != key[k]) { i++; continue@outer }
            }
            val start = i + key.size
            var j = start
            while (j < b.size && j - start < 24 && b[j] != ';'.code.toByte() && b[j].toInt() in 0x21..0x7E) j++
            if (j < b.size && b[j] == ';'.code.toByte() && j > start) {
                return String(b, start, j - start, Charsets.US_ASCII)
            }
            i = start
        }
        return null
    }

    fun prepare(raw: ByteArray, stickBoard: String): OtaPrep {
        val stripped = isMerged(raw)
        if (stripped && raw.size <= MERGED_APP_OFFSET) {
            return OtaPrep.Error("This looks like a merged flash image but has no app after 0x10000.")
        }
        val img = if (stripped) raw.copyOfRange(MERGED_APP_OFFSET, raw.size) else raw
        if (img.isEmpty() || img[0] != IMAGE_MAGIC) {
            return OtaPrep.Error("Not an ESP32 firmware image (first byte must be 0xE9).")
        }
        val tags = findBoardTags(img)
        val want = stickBoard.uppercase()
        if (tags.isEmpty()) {
            return OtaPrep.Error("No SG-BOARD tag found: this file is not a Sharpening Guide build (or is too old to update over Bluetooth).")
        }
        if (want.isEmpty()) {
            return OtaPrep.Error("The stick has not reported its board yet; reconnect and try again.")
        }
        val mismatched = tags.filter { it.uppercase() != want }
        if (mismatched.isNotEmpty()) {
            return OtaPrep.Error("This firmware is for ${mismatched.first()}, but your stick is $want. Pick the $want build.")
        }
        val crc = CRC32().apply { update(img) }.value
        return OtaPrep.Ok(img, want, stripped, appVersion(img), crc)
    }
}

data class ReleaseAsset(val name: String, val downloadUrl: String, val size: Long)
data class Release(val tag: String, val name: String, val assets: List<ReleaseAsset>)

object AssetPicker {
    /**
     * Picks the .bin asset for [board] (PLUS, PLUS2, S3). The board name must appear as a
     * whole token, so `plus` never matches `...-plus2-...`.
     */
    fun pick(assets: List<ReleaseAsset>, board: String): ReleaseAsset? {
        val b = board.lowercase()
        if (b.isEmpty()) return null
        val token = Regex("(?<![a-z0-9])" + Regex.escape(b) + "(?![a-z0-9])")
        val candidates = assets.filter {
            val n = it.name.lowercase()
            n.endsWith(".bin") && token.containsMatchIn(n.removeSuffix(".bin"))
        }
        // Prefer the project's own naming, then the shortest name (fewest qualifiers).
        return candidates.minWithOrNull(
            compareBy<ReleaseAsset>({ if (it.name.lowercase().contains("sharpening-guide")) 0 else 1 }, { it.name.length })
        )
    }
}

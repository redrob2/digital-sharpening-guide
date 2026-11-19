package io.github.redrob2.sharpguide.protocol

import java.util.UUID

/**
 * BLE GATT protocol v1, see docs/companion-protocol.md. Everything in this file is
 * pure Kotlin (no Android types) so it can be unit tested on the JVM.
 * All multi-byte integers are little-endian.
 */
object Uuids {
    val SERVICE: UUID = UUID.fromString("5347a000-0b1e-4a6c-9d2e-6b6e69666521")
    val LIVE: UUID = UUID.fromString("5347a001-0b1e-4a6c-9d2e-6b6e69666521")
    val SETTINGS: UUID = UUID.fromString("5347a002-0b1e-4a6c-9d2e-6b6e69666521")
    val INFO: UUID = UUID.fromString("5347a003-0b1e-4a6c-9d2e-6b6e69666521")
    val OTA_CTRL: UUID = UUID.fromString("5347a010-0b1e-4a6c-9d2e-6b6e69666521")
    val OTA_DATA: UUID = UUID.fromString("5347a011-0b1e-4a6c-9d2e-6b6e69666521")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

const val DEVICE_NAME_PREFIX = "SharpGuide-"
const val PROTOCOL_VERSION = 1

/** ATT attribute values are capped at 512 bytes, regardless of MTU. */
const val MAX_ATTR_LEN = 512

/** OTA_DATA chunk size for a negotiated ATT MTU: min(mtu - 3, 512). */
fun otaChunkSize(mtu: Int): Int = (mtu - 3).coerceIn(20, MAX_ATTR_LEN)

internal fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
internal fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)
internal fun i16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()

enum class Tolerance(val code: Int, val degrees: Int) {
    TIGHT(0, 2), NORMAL(1, 3), EASY(2, 5);

    val label: String get() = "±$degrees°"

    companion object {
        fun fromCode(c: Int): Tolerance = entries.firstOrNull { it.code == c } ?: NORMAL
    }
}

enum class StickColour { GREEN, BLUE, RED, NONE;
    companion object {
        fun fromCode(c: Int): StickColour = when (c) {
            0 -> GREEN; 1 -> BLUE; 2 -> RED; else -> NONE
        }
    }
}

enum class StickState(val code: Int, val label: String) {
    BOOT(0, "Starting"),
    SET_TARGET(1, "Set target"),
    SET_TOLERANCE(2, "Set tolerance"),
    ZERO_CAL(3, "Zero calibration"),
    ACTIVE(4, "Active"),
    REZERO(5, "Re-zero"),
    VERIFY(6, "Accuracy check"),
    SUMMARY(7, "Summary"),
    HISTORY(8, "History"),
    FAULT(9, "Fault"),
    RESUME_PROMPT(10, "Resume?"),
    SLEEP(11, "Sleeping"),
    UNKNOWN(-1, "Unknown");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(c: Int): StickState = byCode[c] ?: UNKNOWN
    }
}

/** One 16-byte LIVE notification. */
data class LiveFrame(
    val version: Int,
    val state: StickState,
    /** Centi-degrees, or [NO_ANGLE]. */
    val angleCenti: Int,
    /** Tenths of a degree. */
    val targetTenths: Int,
    val tolerance: Tolerance,
    val colour: StickColour,
    val sideB: Boolean,
    val flags: Int,
    val strokesA: Int,
    val strokesB: Int,
    val onAnglePct: Int,
    /** 0..4, or -1 unknown. */
    val batteryBars: Int,
) {
    val hasAngle: Boolean get() = angleCenti != NO_ANGLE
    val buzzer: Boolean get() = flags and 0x01 != 0
    val steady: Boolean get() = flags and 0x02 != 0
    val tenths: Boolean get() = flags and 0x04 != 0
    val charging: Boolean get() = flags and 0x08 != 0

    /** Too little charge to survive a firmware update: empty bar and not on USB. */
    val tooLowForUpdate: Boolean get() = batteryBars == 0 && !charging
    val strokesCurrentSide: Int get() = if (sideB) strokesB else strokesA

    companion object {
        const val SIZE = 16
        const val NO_ANGLE = -32768

        /** Returns null if the packet is too short. Extra trailing bytes are ignored. */
        fun parse(b: ByteArray): LiveFrame? {
            if (b.size < SIZE) return null
            return LiveFrame(
                version = u8(b, 0),
                state = StickState.fromCode(u8(b, 1)),
                angleCenti = i16(b, 2),
                targetTenths = u16(b, 4),
                tolerance = Tolerance.fromCode(u8(b, 6)),
                colour = StickColour.fromCode(u8(b, 7)),
                sideB = u8(b, 8) == 1,
                flags = u8(b, 9),
                strokesA = u16(b, 10),
                strokesB = u16(b, 12),
                onAnglePct = u8(b, 14),
                batteryBars = b[15].toInt(),
            )
        }
    }
}

/** The 8-byte SETTINGS struct. */
data class StickSettings(
    val tolerance: Tolerance = Tolerance.NORMAL,
    /** Tenths of a degree, 50..450. */
    val targetTenths: Int = 170,
    val buzzer: Boolean = false,
    val steady: Boolean = true,
    val tenths: Boolean = false,
    val version: Int = PROTOCOL_VERSION,
    /** Screen brightness, 10..100 % of normal. 0 = not reported (older firmware) / leave unchanged. */
    val brightness: Int = 0,
) {
    fun encode(): ByteArray {
        val t = targetTenths.coerceIn(TARGET_MIN, TARGET_MAX)
        val flags = (if (buzzer) 1 else 0) or (if (steady) 2 else 0) or (if (tenths) 4 else 0)
        return byteArrayOf(
            PROTOCOL_VERSION.toByte(),
            tolerance.code.toByte(),
            (t and 0xFF).toByte(), (t shr 8).toByte(),
            flags.toByte(),
            (if (brightness == 0) 0 else brightness.coerceIn(BRIGHTNESS_MIN, 100)).toByte(),
            0, 0,
        )
    }

    companion object {
        const val SIZE = 8
        const val TARGET_MIN = 50
        const val TARGET_MAX = 450
        const val BRIGHTNESS_MIN = 10

        fun parse(b: ByteArray): StickSettings? {
            if (b.size < SIZE) return null
            val flags = u8(b, 4)
            return StickSettings(
                version = u8(b, 0),
                tolerance = Tolerance.fromCode(u8(b, 1)),
                targetTenths = u16(b, 2),
                buzzer = flags and 1 != 0,
                steady = flags and 2 != 0,
                tenths = flags and 4 != 0,
                brightness = u8(b, 5).let { if (it in BRIGHTNESS_MIN..100) it else 0 },
            )
        }
    }
}

/** INFO text, e.g. `fw=1.2.0;board=S3;proto=1`. */
data class DeviceInfo(val fw: String, val board: String, val proto: Int?, val raw: String) {
    companion object {
        fun parse(text: String): DeviceInfo {
            val map = text.trim().split(';').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            return DeviceInfo(
                fw = map["fw"] ?: "?",
                board = (map["board"] ?: "").uppercase(),
                proto = map["proto"]?.toIntOrNull(),
                raw = text,
            )
        }
    }
}

object Ota {
    const val CMD_BEGIN = 0x01
    const val CMD_END = 0x03
    const val CMD_ABORT = 0x05

    fun begin(size: Int): ByteArray = byteArrayOf(CMD_BEGIN.toByte()) + le32(size.toLong())
    fun end(crc32: Long): ByteArray = byteArrayOf(CMD_END.toByte()) + le32(crc32)
    fun abort(): ByteArray = byteArrayOf(CMD_ABORT.toByte())

    fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    fun statusText(status: Int): String = when (status) {
        0 -> "OK"
        1 -> "bad state/command"
        2 -> "flash error"
        3 -> "size/CRC mismatch"
        4 -> "wrong chip"
        5 -> "battery too low, plug the stick in"
        6 -> "not confirmed on the stick (press A there when it asks)"
        else -> "status $status"
    }
}

/** `[cmd, status]` notification on OTA_CTRL. */
data class OtaReply(val cmd: Int, val status: Int) {
    val ok: Boolean get() = status == 0

    companion object {
        fun parse(b: ByteArray): OtaReply? =
            if (b.size < 2) null else OtaReply(u8(b, 0), u8(b, 1))
    }
}

/** Formats an angle without String.format (called for every frame). */
object AngleFormat {
    /** Rounds half away from zero. */
    fun format(angleCenti: Int, tenths: Boolean): String {
        val neg = angleCenti < 0
        val a = if (neg) -angleCenti else angleCenti
        return if (tenths) {
            val t = (a + 5) / 10
            val s = "${t / 10}.${t % 10}"
            if (neg && t != 0) "-$s" else s
        } else {
            val d = (a + 50) / 100
            if (neg && d != 0) "-$d" else d.toString()
        }
    }

    fun tenthsToString(tenths: Int): String = "${tenths / 10}.${tenths % 10}"
}

package io.github.redrob2.sharpguide.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class LiveFrameTest {
    @Test fun parsesAllFields() {
        // ver 1, ACTIVE, 17.25 deg (1725 = 0x06BD), target 17.0 (170 = 0xAA), NORMAL, BLUE,
        // side B, flags buzzer|tenths|charging, strokes A 300 (0x012C), B 7, 84 %, 3 bars
        val f = LiveFrame.parse(bytes(1, 4, 0xBD, 0x06, 0xAA, 0x00, 1, 1, 1, 0x0D, 0x2C, 0x01, 7, 0, 84, 3))!!
        assertEquals(1, f.version)
        assertEquals(StickState.ACTIVE, f.state)
        assertEquals(1725, f.angleCenti)
        assertEquals(170, f.targetTenths)
        assertEquals(Tolerance.NORMAL, f.tolerance)
        assertEquals(StickColour.BLUE, f.colour)
        assertTrue(f.sideB)
        assertTrue(f.buzzer); assertFalse(f.steady); assertTrue(f.tenths); assertTrue(f.charging)
        assertEquals(300, f.strokesA)
        assertEquals(7, f.strokesB)
        assertEquals(7, f.strokesCurrentSide)
        assertEquals(84, f.onAnglePct)
        assertEquals(3, f.batteryBars)
        assertTrue(f.hasAngle)
    }

    @Test fun negativeAngleAndUnknownBattery() {
        // -250 centi-degrees = 0xFF06, colour none, battery -1
        val f = LiveFrame.parse(bytes(1, 6, 0x06, 0xFF, 0x2C, 0x01, 2, 255, 0, 0, 0, 0, 0, 0, 0, 0xFF))!!
        assertEquals(-250, f.angleCenti)
        assertEquals(StickState.VERIFY, f.state)
        assertEquals(StickColour.NONE, f.colour)
        assertEquals(Tolerance.EASY, f.tolerance)
        assertEquals(-1, f.batteryBars)
        assertFalse(f.sideB)
    }

    @Test fun noAngleSentinel() {
        val f = LiveFrame.parse(bytes(1, 7, 0x00, 0x80, 0xAA, 0, 0, 255, 0, 0, 0, 0, 0, 0, 50, 4))!!
        assertEquals(LiveFrame.NO_ANGLE, f.angleCenti)
        assertFalse(f.hasAngle)
        assertEquals(StickState.SUMMARY, f.state)
        assertEquals("Summary", f.state.label)
    }

    @Test fun shortPacketRejected() {
        assertNull(LiveFrame.parse(ByteArray(15)))
    }

    @Test fun unknownStateDoesNotCrash() {
        val f = LiveFrame.parse(bytes(1, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))!!
        assertEquals(StickState.UNKNOWN, f.state)
    }
}

class SettingsTest {
    @Test fun roundTrip() {
        val s = StickSettings(Tolerance.TIGHT, 225, buzzer = true, steady = false, tenths = true)
        val b = s.encode()
        assertArrayEquals(bytes(1, 0, 0xE1, 0x00, 0x05, 0, 0, 0), b)
        assertEquals(s, StickSettings.parse(b))
    }

    @Test fun parsesNotify() {
        val s = StickSettings.parse(bytes(1, 2, 0xC2, 0x01, 0x02, 0, 0, 0))!!
        assertEquals(Tolerance.EASY, s.tolerance)
        assertEquals(450, s.targetTenths)
        assertFalse(s.buzzer); assertTrue(s.steady); assertFalse(s.tenths)
    }

    @Test fun encodeClampsTarget() {
        assertArrayEquals(bytes(1, 1, 50, 0, 0, 0, 0, 0), StickSettings(Tolerance.NORMAL, 10, false, false, false).encode())
        assertArrayEquals(bytes(1, 1, 0xC2, 0x01, 0, 0, 0, 0), StickSettings(Tolerance.NORMAL, 999, false, false, false).encode())
    }

    @Test fun shortRejected() { assertNull(StickSettings.parse(ByteArray(7))) }
}

class MiscProtocolTest {
    @Test fun info() {
        val i = DeviceInfo.parse("fw=1.2.0;board=S3;proto=1")
        assertEquals("1.2.0", i.fw); assertEquals("S3", i.board); assertEquals(1, i.proto)
        assertEquals("PLUS2", DeviceInfo.parse("board=plus2;fw=2.0").board)
    }

    @Test fun otaCommands() {
        assertArrayEquals(bytes(1, 0x00, 0x10, 0x12, 0x00), Ota.begin(0x121000))
        assertArrayEquals(bytes(3, 0x78, 0x56, 0x34, 0xF2), Ota.end(0xF2345678L))
        assertArrayEquals(bytes(5), Ota.abort())
        assertEquals(OtaReply(1, 0), OtaReply.parse(bytes(1, 0)))
        assertNull(OtaReply.parse(bytes(1)))
    }

    @Test fun chunkSizeIsCappedAt512() {
        assertEquals(512, otaChunkSize(517))
        assertEquals(244, otaChunkSize(247))
        assertEquals(20, otaChunkSize(23))
    }

    @Test fun angleFormatting() {
        assertEquals("17", AngleFormat.format(1725, false))
        assertEquals("18", AngleFormat.format(1750, false))
        assertEquals("17.3", AngleFormat.format(1725, true))
        assertEquals("17.2", AngleFormat.format(1724, true))
        assertEquals("0.0", AngleFormat.format(-4, true))
        assertEquals("-2.5", AngleFormat.format(-250, true))
        assertEquals("-3", AngleFormat.format(-250, false))
        assertEquals("0", AngleFormat.format(-49, false))
        assertEquals("5.0", AngleFormat.format(500, true))
    }
}

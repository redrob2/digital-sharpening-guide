package io.github.redrob2.sharpguide.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class FirmwareImageTest {
    /** A fake app image: 0xE9 header, an app descriptor with [version], and a board tag. */
    private fun appImage(board: String?, version: String = "1.2.0", size: Int = 4096): ByteArray {
        val b = ByteArray(size)
        b[0] = 0xE9.toByte()
        // esp_app_desc_t magic 0xABCD5432 at 32, version at 48
        b[32] = 0x32; b[33] = 0x54; b[34] = 0xCD.toByte(); b[35] = 0xAB.toByte()
        version.toByteArray().copyInto(b, 48)
        board?.let { "xxSG-BOARD:$it;yy".toByteArray().copyInto(b, 1000) }
        return b
    }

    private fun merged(app: ByteArray): ByteArray {
        val b = ByteArray(0x10000 + app.size) { 0xFF.toByte() }
        b[0x1000] = 0xE9.toByte()            // bootloader, irrelevant
        b[0x8000] = 0xAA.toByte(); b[0x8001] = 0x50
        app.copyInto(b, 0x10000)
        return b
    }

    @Test fun plainAppImageAccepted() {
        val img = appImage("S3")
        val r = FirmwareImage.prepare(img, "S3") as OtaPrep.Ok
        assertFalse(r.strippedMerged)
        assertEquals(img.size, r.image.size)
        assertEquals("S3", r.board)
        assertEquals("1.2.0", r.version)
        assertEquals(CRC32().apply { update(img) }.value, r.crc32)
    }

    @Test fun mergedImageIsStripped() {
        val app = appImage("PLUS2")
        val r = FirmwareImage.prepare(merged(app), "plus2") as OtaPrep.Ok
        assertTrue(r.strippedMerged)
        assertTrue(app.contentEquals(r.image))
        assertEquals(0xE9.toByte(), r.image[0])
    }

    @Test fun wrongBoardRefused() {
        val r = FirmwareImage.prepare(appImage("PLUS2"), "PLUS")
        assertTrue(r is OtaPrep.Error)
        assertTrue((r as OtaPrep.Error).message.contains("PLUS2"))
    }

    @Test fun plusTagDoesNotSatisfyPlus2() {
        assertTrue(FirmwareImage.prepare(appImage("PLUS"), "PLUS2") is OtaPrep.Error)
        assertTrue(FirmwareImage.prepare(appImage("PLUS"), "PLUS") is OtaPrep.Ok)
    }

    @Test fun missingTagRefused() {
        assertTrue(FirmwareImage.prepare(appImage(null), "S3") is OtaPrep.Error)
    }

    @Test fun badMagicRefused() {
        val img = appImage("S3"); img[0] = 0
        assertTrue(FirmwareImage.prepare(img, "S3") is OtaPrep.Error)
    }

    @Test fun mergedWithoutAppRefused() {
        val b = ByteArray(0x9000); b[0x8000] = 0xAA.toByte(); b[0x8001] = 0x50
        assertTrue(FirmwareImage.prepare(b, "S3") is OtaPrep.Error)
    }

    @Test fun findsTags() {
        val b = "....SG-BOARD:S3;....SG-BOARD:;..SG-BOARD:PLUS2;".toByteArray()
        assertEquals(listOf("S3", "PLUS2"), FirmwareImage.findBoardTags(b))
        assertEquals(emptyList<String>(), FirmwareImage.findBoardTags("SG-BOARD:S3".toByteArray()))
    }

    @Test fun noDescriptorMeansNoVersion() {
        val b = ByteArray(100); b[0] = 0xE9.toByte()
        assertNull(FirmwareImage.appVersion(b))
    }
}

class AssetPickerTest {
    private fun a(n: String) = ReleaseAsset(n, "https://example.invalid/$n", 1000)
    private val assets = listOf(
        a("sharpening-guide-plus-v1.2.0.bin"),
        a("sharpening-guide-plus2-v1.2.0.bin"),
        a("sharpening-guide-s3-v1.2.0.bin"),
        a("digital-sharpening-guide-demo.mp4"),
        a("checksums.txt"),
    )

    @Test fun picksEachBoard() {
        assertEquals("sharpening-guide-plus-v1.2.0.bin", AssetPicker.pick(assets, "PLUS")?.name)
        assertEquals("sharpening-guide-plus2-v1.2.0.bin", AssetPicker.pick(assets, "PLUS2")?.name)
        assertEquals("sharpening-guide-s3-v1.2.0.bin", AssetPicker.pick(assets, "S3")?.name)
    }

    @Test fun plusDoesNotMatchPlus2Only() {
        val only2 = listOf(a("sharpening-guide-plus2-v1.2.0.bin"))
        assertNull(AssetPicker.pick(only2, "PLUS"))
    }

    @Test fun caseInsensitive() {
        assertEquals("Sharpening-Guide-S3-v2.bin", AssetPicker.pick(listOf(a("Sharpening-Guide-S3-v2.bin")), "s3")?.name)
    }

    @Test fun onlyBinFiles() {
        assertNull(AssetPicker.pick(listOf(a("sharpening-guide-s3-v1.zip")), "S3"))
    }

    @Test fun emptyBoard() { assertNull(AssetPicker.pick(assets, "")) }
}

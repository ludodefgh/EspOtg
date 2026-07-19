package com.espotg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EspBinaryInfoTest {

    /** Builds an ESP image header + optional description body from field values. */
    private fun image(
        segmentCount: Int = 1,
        spiMode: Int = 2,
        speedNibble: Int = 0xF,
        sizeNibble: Int = 0x1,
        chipId: Int = 0x0005,
        descBody: ByteArray = ByteArray(0),
    ): ByteArray {
        val buf = ByteArray(0x20 + descBody.size)
        buf[0] = 0xE9.toByte()
        buf[1] = segmentCount.toByte()
        buf[2] = spiMode.toByte()
        buf[3] = (((sizeNibble and 0x0F) shl 4) or (speedNibble and 0x0F)).toByte()
        buf[12] = (chipId and 0xFF).toByte()
        buf[13] = ((chipId shr 8) and 0xFF).toByte()
        descBody.copyInto(buf, 0x20)
        return buf
    }

    private fun appDesc(version: String, project: String, time: String, date: String, idf: String): ByteArray {
        val body = ByteArray(0xB0)
        // magic 0xABCD5432 little-endian
        body[0] = 0x32; body[1] = 0x54; body[2] = 0xCD.toByte(); body[3] = 0xAB.toByte()
        fun put(s: String, off: Int) = s.toByteArray(Charsets.UTF_8).copyInto(body, off)
        put(version, 0x10)
        put(project, 0x30)
        put(time, 0x50)
        put(date, 0x60)
        put(idf, 0x70)
        return body
    }

    private fun bootloaderDesc(version: Int, idf: String, dateTime: String): ByteArray {
        val body = ByteArray(0x60)
        body[0] = 0x50 // magic byte
        body[4] = (version and 0xFF).toByte()
        idf.toByteArray(Charsets.UTF_8).copyInto(body, 8)
        dateTime.toByteArray(Charsets.UTF_8).copyInto(body, 8 + 32)
        return body
    }

    @Test
    fun `parses an app image (values from real hello_c3 build)`() {
        val bin = image(descBody = appDesc("v0.1.15", "hello_c3", "11:09:14", "Jul 19 2026", "v5.5-dev-3511-g106d55ddbe"))
        val info = EspBinaryInfo.parse(bin)
        assertEquals(EspImageType.APP, info.type)
        assertEquals("ESP32-C3", info.chipName)
        assertEquals("hello_c3", info.projectName)
        assertEquals("v0.1.15", info.appVersion)
        assertEquals("Jul 19 2026", info.compileDate)
        assertEquals("11:09:14", info.compileTime)
        assertEquals("v5.5-dev-3511-g106d55ddbe", info.idfVersion)
        assertEquals("DIO", info.flashMode)
        assertEquals("80 MHz", info.flashFreq)
        assertEquals("2MB", info.flashSize)
    }

    @Test
    fun `parses a bootloader image`() {
        val bin = image(segmentCount = 3, descBody = bootloaderDesc(1, "v5.5-dev-3511-g106d55ddbe", "Jul 19 2026 11:09:22"))
        val info = EspBinaryInfo.parse(bin)
        assertEquals(EspImageType.BOOTLOADER, info.type)
        assertEquals("ESP32-C3", info.chipName)
        assertEquals("v5.5-dev-3511-g106d55ddbe", info.idfVersion)
        assertEquals("Jul 19 2026 11:09:22", info.compileDate)
        assertNull(info.projectName)
    }

    @Test
    fun `valid image header without a known desc is ESP_IMAGE with chip and flash info`() {
        val info = EspBinaryInfo.parse(image(chipId = 0x0009))
        assertEquals(EspImageType.ESP_IMAGE, info.type)
        assertEquals("ESP32-S3", info.chipName)
        assertEquals("2MB", info.flashSize)
        assertNull(info.projectName)
    }

    @Test
    fun `non-esp data returns DATA`() {
        val partitionTableLike = byteArrayOf(0xAA.toByte(), 0x50, 0x01, 0x02) + ByteArray(60)
        val info = EspBinaryInfo.parse(partitionTableLike)
        assertEquals(EspImageType.DATA, info.type)
        assertNull(info.chipName)
    }

    @Test
    fun `too-short input returns DATA`() {
        assertEquals(EspImageType.DATA, EspBinaryInfo.parse(byteArrayOf(0xE9.toByte(), 0x01)).type)
    }
}

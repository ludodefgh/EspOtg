package com.espotg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexOffsetTest {

    @Test
    fun `parses with 0x prefix`() {
        assertEquals(0x1000L, HexOffset.parse("0x1000").getOrThrow().value)
    }

    @Test
    fun `parses with uppercase 0X prefix`() {
        assertEquals(0x8000L, HexOffset.parse("0X8000").getOrThrow().value)
    }

    @Test
    fun `parses bare hex digits without prefix`() {
        assertEquals(0x10000L, HexOffset.parse("10000").getOrThrow().value)
    }

    @Test
    fun `parses mixed case hex letters`() {
        assertEquals(0xABCDEFL, HexOffset.parse("aBcDeF").getOrThrow().value)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(0x1000L, HexOffset.parse("  0x1000  ").getOrThrow().value)
    }

    @Test
    fun `rejects empty input`() {
        assertTrue(HexOffset.parse("").isFailure)
        assertTrue(HexOffset.parse("   ").isFailure)
    }

    @Test
    fun `rejects non-hex characters`() {
        assertTrue(HexOffset.parse("0xZZZZ").isFailure)
        assertTrue(HexOffset.parse("not-hex").isFailure)
    }

    @Test
    fun `rejects bare 0x with no digits`() {
        assertTrue(HexOffset.parse("0x").isFailure)
    }

    @Test
    fun `toHexString round-trips through parse`() {
        val original = HexOffset.parse("0x10000").getOrThrow()
        val roundTripped = HexOffset.parse(original.toHexString()).getOrThrow()
        assertEquals(original, roundTripped)
    }

    @Test
    fun `of rejects negative values`() {
        assertTrue(runCatching { HexOffset.of(-1L) }.isFailure)
    }
}

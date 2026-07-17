package com.espotg.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FlashProgressTest {

    @Test
    fun `entry fraction is zero when total bytes is zero`() {
        val progress = FlashEntryProgress(entryId = "a", state = FlashStepState.PENDING, totalBytes = 0)
        assertEquals(0f, progress.fraction)
    }

    @Test
    fun `entry fraction reflects bytes written over total`() {
        val progress = FlashEntryProgress(entryId = "a", state = FlashStepState.WRITING, bytesWritten = 50, totalBytes = 200)
        assertEquals(0.25f, progress.fraction)
    }

    @Test
    fun `entry fraction is clamped to one`() {
        val progress = FlashEntryProgress(entryId = "a", state = FlashStepState.WRITING, bytesWritten = 500, totalBytes = 200)
        assertEquals(1f, progress.fraction)
    }

    @Test
    fun `overall fraction is zero for empty plan`() {
        val progress = FlashProgress(entries = emptyList(), isRunning = false)
        assertEquals(0f, progress.overallFraction)
    }

    @Test
    fun `overall fraction averages entry fractions`() {
        val progress = FlashProgress(
            entries = listOf(
                FlashEntryProgress("a", FlashStepState.DONE, bytesWritten = 100, totalBytes = 100),
                FlashEntryProgress("b", FlashStepState.WRITING, bytesWritten = 0, totalBytes = 100),
            ),
            isRunning = true,
        )
        assertEquals(0.5f, progress.overallFraction)
    }
}

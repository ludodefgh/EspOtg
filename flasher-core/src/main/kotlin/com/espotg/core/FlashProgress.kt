package com.espotg.core

enum class FlashStepState { PENDING, WRITING, VERIFYING, DONE, ERROR }

data class FlashEntryProgress(
    val entryId: String,
    val state: FlashStepState,
    val bytesWritten: Long = 0,
    val totalBytes: Long = 0,
    val errorMessage: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesWritten.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class FlashProgress(
    val entries: List<FlashEntryProgress>,
    val isRunning: Boolean,
) {
    val overallFraction: Float
        get() = if (entries.isEmpty()) 0f else entries.map { it.fraction }.average().toFloat()
}

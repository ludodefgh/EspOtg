package com.espotg.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espotg.core.LogLevel
import com.espotg.core.LogLine

/** Monospace, auto-scrolling console for live flash/monitor logs, color-coded by level. */
@Composable
fun LogConsole(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.background(Color(0xFF0D1117)),
    ) {
        items(lines) { line ->
            Text(
                text = line.message,
                color = colorFor(line.level),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

private fun colorFor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Color(0xFFFF6B6B)
    LogLevel.WARN -> Color(0xFFFFD166)
    LogLevel.INFO -> Color(0xFFB8E1FF)
    LogLevel.DEBUG -> Color(0xFF8B949E)
    LogLevel.NONE -> Color(0xFFC9D1D9)
}

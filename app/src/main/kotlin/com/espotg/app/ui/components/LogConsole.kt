package com.espotg.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espotg.core.LogLevel
import com.espotg.core.LogLine

/**
 * Monospace, auto-scrolling console for live flash/monitor logs, color-coded by
 * level. Long-press anywhere in the console to copy every visible line to the
 * clipboard - handy for pasting the whole log elsewhere rather than screenshotting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogConsole(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .background(Color(0xFF0D1117))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
                onLongClick = {
                    // Android's clipboard rides a Binder transaction with a hard
                    // ~1MB limit - exceeding it kills the app with
                    // TransactionTooLargeException (this crashed the app when
                    // copying a busy flash session's log). Cap the payload to the
                    // most recent content and guard the call anyway.
                    var text = lines.joinToString("\n") { it.message }
                    if (text.length > MAX_CLIPBOARD_CHARS) {
                        text = "[... truncated, showing most recent ...]\n" + text.takeLast(MAX_CLIPBOARD_CHARS)
                    }
                    runCatching { clipboard.setText(AnnotatedString(text)) }
                        .onSuccess { Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "Copy failed: log too large", Toast.LENGTH_SHORT).show() }
                },
            ),
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

private const val MAX_CLIPBOARD_CHARS = 400_000

private fun colorFor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Color(0xFFFF6B6B)
    LogLevel.WARN -> Color(0xFFFFD166)
    LogLevel.INFO -> Color(0xFFB8E1FF)
    LogLevel.DEBUG -> Color(0xFF8B949E)
    LogLevel.NONE -> Color(0xFFC9D1D9)
}

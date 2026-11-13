package dev.kindletts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kindletts.reader.FIXTURE_LONG_URL
import dev.kindletts.reader.FIXTURE_URL
import dev.kindletts.reader.FrameInfo
import dev.kindletts.reader.PageResult
import dev.kindletts.reader.ReaderSession
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val frames by ReaderSession.frames.collectAsState()
    val contentFrameId by ReaderSession.contentFrameId.collectAsState()
    val lastText by ReaderSession.lastPageText.collectAsState()
    val lastUrl by ReaderSession.lastPageUrl.collectAsState()
    val lastTurn by ReaderSession.lastTurn.collectAsState()
    val status by ReaderSession.status.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Reader diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    text = "content=${contentFrameId ?: "none"}  frames=${frames.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (status.isNotBlank()) {
                    Text("status=$status", style = MaterialTheme.typography.bodySmall)
                }
                if (lastTurn.isNotBlank()) {
                    Text("lastTurn=$lastTurn", style = MaterialTheme.typography.bodySmall)
                }
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                message = "Probing frames…"
                                ReaderSession.probe()
                                message = "Probe sent"
                                busy = false
                            }
                        },
                    ) { Text("Probe") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                message = when (val result = ReaderSession.extractPage()) {
                                    is PageResult.Text ->
                                        "Extracted ${result.text.length} chars via ${result.strategy}"
                                    PageResult.NoContent -> "No readable page in the selected frame"
                                    PageResult.Boundary -> "No new text at the selected frame"
                                    is PageResult.SignIn -> result.message
                                    is PageResult.Error -> result.message
                                }
                                busy = false
                            }
                        },
                    ) { Text("Extract") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val result = ReaderSession.turnPage()
                                message = "Turn: ${result.strategy}, changed=${result.changed}, ${result.text.length} chars"
                                busy = false
                            }
                        },
                    ) { Text("Turn") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { ReaderSession.load(FIXTURE_URL) }) { Text("Load fixture") }
                    TextButton(onClick = { ReaderSession.load(FIXTURE_LONG_URL) }) { Text("Long fixture") }
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            fun current(info: dev.kindletts.reader.PageInfo?) =
                                info?.images?.minByOrNull { kotlin.math.abs(it.y) }?.src?.takeLast(8) ?: "?"
                            val before = current(ReaderSession.pageInfo())
                            val moved = ReaderSession.scrollNextPage()
                            val info = ReaderSession.pageInfo()
                            message = "scroll=$moved before=$before after=${current(info)} label=${info?.label ?: "none"} images=${info?.images?.size ?: 0}"
                            busy = false
                        }
                    }) { Text("Scroll") }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(ReaderSession.diagnosticsReport())) }) {
                        Text("Copy report")
                    }
                }

                Divider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(frames, key = { it.frameId }) { frame ->
                        FrameRow(
                            frame = frame,
                            selected = frame.frameId == contentFrameId,
                            onUse = { ReaderSession.selectFrame(frame.frameId) },
                        )
                    }
                    item {
                        if (lastText.isNotBlank()) {
                            Text(
                                text = "last page ($lastUrl):\n$lastText",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameRow(frame: FrameInfo, selected: Boolean, onUse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${frame.textLength} chars · depth ${frame.depth}${if (selected) " · selected" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(frame.url, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        TextButton(onClick = onUse) { Text("Use") }
    }
    Divider()
}

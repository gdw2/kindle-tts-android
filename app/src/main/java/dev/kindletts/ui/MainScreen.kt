package dev.kindletts.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kindletts.BuildConfig
import dev.kindletts.playback.PlaybackService
import dev.kindletts.reader.KindleWebView
import dev.kindletts.tts.PlaybackController
import dev.kindletts.tts.PlaybackStatus
import dev.kindletts.tts.PlaybackUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<PlaybackController?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                controller = (binder as? PlaybackService.LocalBinder)?.service?.controller
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                controller = null
            }
        }
        context.bindService(
            Intent(context, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose {
            context.unbindService(connection)
            controller = null
        }
    }

    val fallback = remember { MutableStateFlow(PlaybackUiState()) }
    val state by (controller?.state ?: fallback).collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        KindleWebView(modifier = Modifier.weight(1f))
        PlaybackBar(
            state = state,
            onPlay = {
                if (state.status == PlaybackStatus.PLAYING) {
                    PlaybackService.pause(context)
                } else {
                    PlaybackService.play(context)
                }
            },
            onStop = { PlaybackService.stop(context) },
            onRateChange = { controller?.setRate(it) },
            onDiagnostics = if (BuildConfig.DEBUG) ({ showDiagnostics = true }) else null,
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }
}

@Composable
private fun PlaybackBar(
    state: PlaybackUiState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRateChange: (Float) -> Unit,
    onDiagnostics: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = statusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onDiagnostics != null) {
                    TextButton(onClick = onDiagnostics) { Text("Dev") }
                }
                TextButton(onClick = { onRateChange(nextRate(state.rate)) }) {
                    Text("${(state.rate * 10).roundToInt() / 10f}x")
                }
                if (state.status == PlaybackStatus.PLAYING) {
                    Button(onClick = onPlay) { Text("Pause") }
                } else {
                    Button(onClick = onPlay) { Text("Play") }
                }
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

private fun statusText(state: PlaybackUiState): String = when {
    state.error != null -> state.error
    state.status == PlaybackStatus.PLAYING -> "Reading — page ${state.pageNumber.coerceAtLeast(1)}"
    state.status == PlaybackStatus.PAUSED -> state.message ?: "Paused"
    else -> "Open a book and press Play"
}

private fun nextRate(rate: Float): Float {
    val rates = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val index = rates.indexOfFirst { it >= rate }.coerceAtLeast(0)
    return rates[(index + 1) % rates.size]
}

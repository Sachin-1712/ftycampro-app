package dev.ftycam.ui.live

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.ftycam.R
import dev.ftycam.ViewModelFactories
import dev.ftycam.stream.PlayerController
import dev.ftycam.transport.HandshakeState
import dev.ftycam.transport.SessionDiagnostics

@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
@Suppress("LongMethod")
fun LiveScreen(
    cameraId: String,
    onBack: () -> Unit,
    viewModel: LiveViewModel = viewModel(factory = ViewModelFactories.live),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val controller = remember { PlayerController(context) }

    DisposableEffect(cameraId) {
        viewModel.attachPlayer(controller)
        viewModel.connect(cameraId)
        onDispose {
            viewModel.disconnect()
            controller.release()
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissToast()
        }
    }

    if (state.fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(controller, Modifier.fillMaxSize())
            IconButton(
                onClick = viewModel::toggleFullscreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    stringResource(R.string.live_exit_fullscreen),
                    tint = Color.White,
                )
            }
        }
        return
    }

    Scaffold(
        topBar = { LiveTopBar(title = state.camera?.name.orEmpty(), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                VideoSurface(controller, Modifier.fillMaxSize())

                when {
                    state.error != null -> ErrorOverlay(
                        message = state.error.orEmpty(),
                        hint = state.errorHint.orEmpty(),
                        onRetry = viewModel::retry,
                    )

                    state.connecting -> StatusOverlay(stringResource(R.string.live_connecting))

                    state.reconnecting -> StatusOverlay(
                        "${stringResource(R.string.live_reconnecting)} (${state.reconnectAttempt})"
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            ControlBar(
                muted = state.muted,
                recording = state.recording,
                enabled = state.connected,
                onMuteToggle = viewModel::toggleMute,
                onSnapshot = {
                    // PlayerView owns the rendered frame, so the snapshot is taken
                    // from the surface rather than from the transport. Cameras that
                    // expose a native snapshot command can override this via
                    // CameraTransport.requestSnapshot().
                    viewModel.onSnapshotResult(
                        Result.failure(
                            NotImplementedError(
                                "Surface capture needs a TextureView-backed PlayerView"
                            )
                        )
                    )
                },
                onRecordToggle = viewModel::toggleRecording,
                onFullscreen = viewModel::toggleFullscreen,
            )

            Spacer(Modifier.height(24.dp))

            DiagnosticsPanel(
                diagnostics = state.diagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * What the last connection attempt actually saw.
 *
 * Discovery and handshake are reported as separate lines because they fail for
 * unrelated reasons. Right now the expected reading is "Discovery: success" with
 * "Session handshake: failed" — that combination is the current known state of the
 * protocol work, and showing it plainly is more useful than a single red error.
 */
@Composable
private fun DiagnosticsPanel(
    diagnostics: SessionDiagnostics,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = "DIAGNOSTICS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))

        DiagnosticRow(
            "Discovery",
            if (diagnostics.discoverySucceeded) "success" else "no reply",
            if (diagnostics.discoverySucceeded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        DiagnosticRow("UID", diagnostics.uid ?: "—")
        DiagnosticRow("Current IP", diagnostics.host ?: "—")
        DiagnosticRow(
            "UDP source port",
            diagnostics.sourcePort?.let { "$it (ephemeral)" } ?: "—",
        )
        DiagnosticRow(
            "Session handshake",
            diagnostics.handshake.name.lowercase(),
            when (diagnostics.handshake) {
                HandshakeState.SUCCEEDED -> MaterialTheme.colorScheme.primary
                HandshakeState.FAILED -> MaterialTheme.colorScheme.error
                HandshakeState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (diagnostics.attemptedEndpoints.isNotEmpty()) {
            DiagnosticRow("Endpoints tried", diagnostics.attemptedEndpoints.joinToString(", "))
        }

        if (diagnostics.trace.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "PACKET TRACE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            diagnostics.trace.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(controller: PlayerController, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                player = controller.player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
            }
        },
    )
}

@Composable
private fun StatusOverlay(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorOverlay(message: String, hint: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
    }
}

@Composable
private fun ControlBar(
    muted: Boolean,
    recording: Boolean,
    enabled: Boolean,
    onMuteToggle: () -> Unit,
    onSnapshot: () -> Unit,
    onRecordToggle: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onMuteToggle, enabled = enabled) {
            Icon(
                imageVector = if (muted) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = stringResource(
                    if (muted) R.string.live_unmute else R.string.live_mute
                ),
            )
        }

        FilledTonalIconButton(onClick = onSnapshot, enabled = enabled) {
            Icon(Icons.Default.CameraAlt, stringResource(R.string.live_snapshot))
        }

        FilledTonalIconButton(
            onClick = onRecordToggle,
            enabled = enabled,
            colors = if (recording) {
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                IconButtonDefaults.filledTonalIconButtonColors()
            },
        ) {
            Icon(
                imageVector = if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = stringResource(
                    if (recording) R.string.live_record_stop else R.string.live_record_start
                ),
            )
        }

        FilledTonalIconButton(onClick = onFullscreen, enabled = enabled) {
            Icon(Icons.Default.Fullscreen, stringResource(R.string.live_fullscreen))
        }
    }
}

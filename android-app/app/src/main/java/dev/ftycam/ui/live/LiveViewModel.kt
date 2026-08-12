package dev.ftycam.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ftycam.data.CameraRepository
import dev.ftycam.data.SettingsRepository
import dev.ftycam.data.model.Camera
import dev.ftycam.data.model.StreamQuality
import dev.ftycam.stream.MediaWriter
import dev.ftycam.stream.PlayerController
import dev.ftycam.transport.CameraTransport
import dev.ftycam.transport.ConnectionState
import dev.ftycam.transport.SessionDiagnostics
import dev.ftycam.transport.TransportException
import dev.ftycam.transport.TransportFactory
import dev.ftycam.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LiveViewModel(
    private val cameraRepository: CameraRepository,
    private val transportFactory: TransportFactory,
    private val mediaWriter: MediaWriter,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveUiState())
    val state = _state.asStateFlow()

    private var transport: CameraTransport? = null
    private var playerController: PlayerController? = null
    private var collectJobs = mutableListOf<Job>()

    /** Set by the screen once it has a Context to build the player with. */
    fun attachPlayer(controller: PlayerController) {
        playerController = controller
    }

    fun connect(cameraId: String) {
        if (_state.value.connecting) return

        viewModelScope.launch {
            if (cameraRepository.cameras.value.isEmpty()) cameraRepository.load()

            val camera = cameraRepository.find(cameraId)
            if (camera == null) {
                _state.update { it.copy(error = "Camera not found", errorHint = "") }
                return@launch
            }

            val settings = settingsRepository.settings.first()
            _state.update {
                it.copy(
                    camera = camera,
                    connecting = true,
                    error = null,
                    muted = !settings.audioOnByDefault,
                )
            }

            val newTransport = transportFactory.create(camera)
            transport = newTransport
            observeTransport(newTransport)

            try {
                newTransport.connect(camera.streamQuality)
                playerController?.let { controller ->
                    controller.start(newTransport)
                    controller.setMuted(_state.value.muted)
                }
                _state.update { it.copy(connecting = false, connected = true) }
            } catch (e: TransportException) {
                Log.w(TAG, "connect failed: ${e.message}")
                _state.update {
                    it.copy(connecting = false, connected = false, error = e.message, errorHint = e.hint)
                }
            } catch (e: Exception) {
                Log.e(TAG, "unexpected connect failure", e)
                _state.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        error = "Could not connect",
                        errorHint = e.message.orEmpty(),
                    )
                }
            }
        }
    }

    private fun observeTransport(transport: CameraTransport) {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()

        collectJobs += viewModelScope.launch {
            transport.state.collect { connectionState ->
                _state.update {
                    when (connectionState) {
                        is ConnectionState.Reconnecting ->
                            it.copy(reconnecting = true, reconnectAttempt = connectionState.attempt)
                        is ConnectionState.Connected ->
                            it.copy(reconnecting = false, sessionDetail = connectionState.detail.toString())
                        is ConnectionState.Failed ->
                            it.copy(
                                connected = false,
                                error = connectionState.error.message,
                                errorHint = connectionState.error.hint,
                            )
                        else -> it
                    }
                }
            }
        }

        collectJobs += viewModelScope.launch {
            transport.diagnostics.collect { diagnostics ->
                _state.update { it.copy(diagnostics = diagnostics) }
                // Record where it was seen, as metadata only. The endpoint used to
                // connect always comes from fresh discovery, never from storage.
                val camera = _state.value.camera
                val host = diagnostics.host
                if (camera != null && host != null && diagnostics.discoverySucceeded) {
                    cameraRepository.noteLastSeen(camera.id, host)
                }
            }
        }

        collectJobs += viewModelScope.launch {
            transport.video.collect { chunk ->
                playerController?.feed(chunk)
                if (_state.value.recording) mediaWriter.writeFrame(chunk)
            }
        }
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted) }
        playerController?.setMuted(muted)
    }

    fun toggleFullscreen() = _state.update { it.copy(fullscreen = !it.fullscreen) }

    fun toggleRecording() {
        val camera = _state.value.camera ?: return
        viewModelScope.launch {
            if (_state.value.recording) {
                val file = mediaWriter.stopRecording()
                _state.update {
                    it.copy(recording = false, toast = file?.let { f -> "Saved ${f.name}" })
                }
            } else {
                mediaWriter.startRecording(camera.name).fold(
                    onSuccess = { file ->
                        _state.update { it.copy(recording = true, toast = "Recording to ${file.name}") }
                    },
                    onFailure = { error ->
                        _state.update { it.copy(toast = "Could not record: ${error.message}") }
                    },
                )
            }
        }
    }

    fun onSnapshotResult(result: Result<String>) {
        _state.update {
            it.copy(
                toast = result.fold(
                    onSuccess = { name -> "Snapshot saved: $name" },
                    onFailure = { error -> "Snapshot failed: ${error.message}" },
                )
            )
        }
    }

    fun dismissToast() = _state.update { it.copy(toast = null) }

    fun retry() {
        val cameraId = _state.value.camera?.id ?: return
        _state.update { it.copy(error = null, errorHint = null) }
        connect(cameraId)
    }

    fun disconnect() {
        viewModelScope.launch {
            if (_state.value.recording) mediaWriter.stopRecording()
            collectJobs.forEach { it.cancel() }
            collectJobs.clear()
            transport?.disconnect()
            transport = null
            playerController?.stop()
            _state.update { it.copy(connected = false, recording = false) }
        }
    }

    override fun onCleared() {
        // The view model outlives the composable across configuration changes, so
        // this is the only place that reliably runs when the screen is truly gone.
        transport?.let { t -> viewModelScope.launch { t.disconnect() } }
        super.onCleared()
    }

    private companion object {
        const val TAG = "LiveViewModel"
    }
}

data class LiveUiState(
    val camera: Camera? = null,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    val muted: Boolean = false,
    val recording: Boolean = false,
    val fullscreen: Boolean = false,
    val quality: StreamQuality = StreamQuality.HIGH,
    val error: String? = null,
    val errorHint: String? = null,
    val sessionDetail: String? = null,
    val toast: String? = null,
    val diagnostics: SessionDiagnostics = SessionDiagnostics(),
)

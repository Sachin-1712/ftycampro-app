package dev.ftycam.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ftycam.data.CameraRepository
import dev.ftycam.transport.pppp.PpppDiscovery
import dev.ftycam.data.model.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CameraListViewModel(
    private val repository: CameraRepository,
) : ViewModel() {

    private val _discovery = MutableStateFlow(DiscoveryState())

    val uiState: StateFlow<CameraListUiState> =
        combine(repository.cameras, _discovery) { cameras, discovery ->
            CameraListUiState(
                cameras = cameras,
                isScanning = discovery.scanning,
                discovered = discovery.results,
                message = discovery.message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CameraListUiState(isLoading = true),
        )

    init {
        viewModelScope.launch { repository.load() }
    }

    fun scan() {
        if (_discovery.value.scanning) return
        viewModelScope.launch {
            _discovery.value = DiscoveryState(scanning = true)
            val results = repository.discover()
            _discovery.value = DiscoveryState(
                scanning = false,
                results = results,
                message = if (results.isEmpty()) {
                    // Being specific here matters: a bare "nothing found" invites the
                    // conclusion that the camera is broken, when the likeliest causes
                    // are a router setting and a protocol guess.
                    "No cameras answered the discovery broadcast. The camera may not " +
                        "use this protocol, or your router may be blocking " +
                        "device-to-device traffic. You can still add it by IP."
                } else {
                    "Found ${results.size} device(s)"
                },
            )
        }
    }

    /** Save a discovered camera. Stored by UID — the reply port is never kept. */
    fun addDiscovered(endpoint: PpppDiscovery.Endpoint) {
        viewModelScope.launch {
            val name = endpoint.uid?.substringBefore('-')?.takeIf { it.isNotBlank() }
                ?: endpoint.host
            repository.addDiscovered(endpoint, name)
            _discovery.value = _discovery.value.copy(
                results = _discovery.value.results.filterNot { it.host == endpoint.host },
                message = "Added $name",
            )
        }
    }

    fun delete(cameraId: String) {
        viewModelScope.launch { repository.delete(cameraId) }
    }

    fun dismissMessage() {
        _discovery.value = _discovery.value.copy(message = null)
    }

    private data class DiscoveryState(
        val scanning: Boolean = false,
        val results: List<PpppDiscovery.Endpoint> = emptyList(),
        val message: String? = null,
    )
}

data class CameraListUiState(
    val cameras: List<Camera> = emptyList(),
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val discovered: List<PpppDiscovery.Endpoint> = emptyList(),
    val message: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && cameras.isEmpty()
}

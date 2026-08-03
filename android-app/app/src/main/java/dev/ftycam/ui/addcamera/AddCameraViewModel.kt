package dev.ftycam.ui.addcamera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ftycam.data.CameraRepository
import dev.ftycam.data.model.Address
import dev.ftycam.data.model.AddressValidator
import dev.ftycam.data.model.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddCameraViewModel(private val repository: CameraRepository) : ViewModel() {

    private val _state = MutableStateFlow(AddCameraUiState())
    val state = _state.asStateFlow()

    fun setMode(mode: AddMode) = _state.update { it.copy(mode = mode, error = null) }

    fun setName(value: String) = _state.update { it.copy(name = value, error = null) }

    fun setUid(value: String) = _state.update { it.copy(uid = value, error = null) }

    fun setHost(value: String) = _state.update { it.copy(host = value, error = null) }

    fun setPort(value: String) = _state.update { it.copy(port = value, error = null) }

    fun setUsername(value: String) = _state.update { it.copy(username = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun save() {
        val current = _state.value

        val name = current.name.trim().ifEmpty {
            // Falling back to the address beats rejecting the form over a field
            // the user can reasonably consider optional.
            when (current.mode) {
                AddMode.UID -> current.uid.trim()
                AddMode.IP -> current.host.trim()
            }
        }
        if (name.isEmpty()) {
            _state.update { it.copy(error = "Enter a name or an address") }
            return
        }

        val address: Result<Address> = when (current.mode) {
            AddMode.UID -> AddressValidator.validateUid(current.uid)
            AddMode.IP -> AddressValidator.validateNetwork(current.host, current.port)
        }

        address.fold(
            onSuccess = { resolved ->
                viewModelScope.launch {
                    repository.add(
                        camera = Camera(name = name, address = resolved),
                        username = current.username,
                        password = current.password,
                    )
                    _state.update { it.copy(saved = true) }
                }
            },
            onFailure = { error ->
                _state.update { it.copy(error = error.message ?: "Invalid address") }
            },
        )
    }
}

enum class AddMode { UID, IP }

data class AddCameraUiState(
    val mode: AddMode = AddMode.UID,
    val name: String = "",
    val uid: String = "",
    val host: String = "",
    val port: String = Address.DEFAULT_PORT.toString(),
    val username: String = "",
    val password: String = "",
    val error: String? = null,
    val saved: Boolean = false,
)

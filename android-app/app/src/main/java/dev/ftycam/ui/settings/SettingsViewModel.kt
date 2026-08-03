package dev.ftycam.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ftycam.data.SettingsRepository
import dev.ftycam.data.model.StreamQuality
import dev.ftycam.util.Log
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings = repository.settings
        // Keep the logger in step with the preference without the settings screen
        // having to remember to do it.
        .onEach { Log.fileLoggingEnabled = it.verboseLogging; Log.verbose = it.verboseLogging }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsRepository.Settings(),
        )

    fun setQuality(quality: StreamQuality) {
        viewModelScope.launch { repository.setDefaultQuality(quality) }
    }

    fun setHardwareDecoding(enabled: Boolean) {
        viewModelScope.launch { repository.setHardwareDecoding(enabled) }
    }

    fun setAudioOnByDefault(enabled: Boolean) {
        viewModelScope.launch { repository.setAudioOnByDefault(enabled) }
    }

    fun setVerboseLogging(enabled: Boolean) {
        viewModelScope.launch {
            repository.setVerboseLogging(enabled)
            if (!enabled) Log.clear()
        }
    }
}

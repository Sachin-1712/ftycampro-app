package dev.ftycam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ftycam.data.model.StreamQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App preferences.
 *
 * Plain DataStore rather than the encrypted store: none of this is sensitive, and
 * DataStore gives a Flow, which is what the settings UI wants.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            defaultQuality = runCatching {
                StreamQuality.valueOf(prefs[KEY_QUALITY] ?: StreamQuality.HIGH.name)
            }.getOrDefault(StreamQuality.HIGH),
            hardwareDecoding = prefs[KEY_HW_DECODE] ?: true,
            audioOnByDefault = prefs[KEY_AUDIO_DEFAULT] ?: true,
            verboseLogging = prefs[KEY_VERBOSE_LOG] ?: false,
        )
    }

    suspend fun setDefaultQuality(quality: StreamQuality) {
        context.dataStore.edit { it[KEY_QUALITY] = quality.name }
    }

    suspend fun setHardwareDecoding(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HW_DECODE] = enabled }
    }

    suspend fun setAudioOnByDefault(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUDIO_DEFAULT] = enabled }
    }

    suspend fun setVerboseLogging(enabled: Boolean) {
        context.dataStore.edit { it[KEY_VERBOSE_LOG] = enabled }
    }

    data class Settings(
        val defaultQuality: StreamQuality = StreamQuality.HIGH,
        val hardwareDecoding: Boolean = true,
        val audioOnByDefault: Boolean = true,
        val verboseLogging: Boolean = false,
    )

    private companion object {
        val KEY_QUALITY = stringPreferencesKey("default_quality")
        val KEY_HW_DECODE = booleanPreferencesKey("hardware_decoding")
        val KEY_AUDIO_DEFAULT = booleanPreferencesKey("audio_default")
        val KEY_VERBOSE_LOG = booleanPreferencesKey("verbose_logging")
    }
}

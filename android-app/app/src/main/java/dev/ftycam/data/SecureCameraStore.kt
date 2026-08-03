package dev.ftycam.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.ftycam.data.model.Camera
import dev.ftycam.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for cameras and their credentials.
 *
 * Everything goes into [EncryptedSharedPreferences], backed by a key in the
 * hardware keystore. The camera list is not secret in the way a password is, but
 * a device UID is a durable identifier for a camera in the user's home, and
 * splitting storage by sensitivity would mean deciding which of two files a given
 * field belongs in every time one is added. One encrypted file removes that
 * decision.
 *
 * Credentials are keyed separately from the camera record so that
 * [dev.ftycam.data.model.Camera] can stay free of secrets — see the note on that
 * class.
 */
class SecureCameraStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        // Keystore corruption after an OS update or a restore-to-new-device is a
        // real and reasonably common failure. Crashing on launch would leave the
        // user with no way back; starting empty at least lets them re-add cameras.
        Log.e(TAG, "encrypted store unavailable, resetting", error)
        context.deleteSharedPreferences(FILE_NAME)
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun loadCameras(): List<Camera> {
        val raw = prefs.getString(KEY_CAMERAS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { Camera.fromJson(array.getJSONObject(it)) }
        }.getOrElse {
            Log.e(TAG, "camera list unreadable, discarding", it)
            emptyList()
        }
    }

    fun saveCameras(cameras: List<Camera>) {
        val array = JSONArray().apply { cameras.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_CAMERAS, array.toString()).apply()
    }

    fun saveCredentials(cameraId: String, username: String, password: String) {
        val json = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        prefs.edit().putString(credentialKey(cameraId), json.toString()).apply()
    }

    fun loadCredentials(cameraId: String): Credentials? {
        val raw = prefs.getString(credentialKey(cameraId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Credentials(json.optString("username"), json.optString("password"))
        }.getOrNull()
    }

    fun deleteCredentials(cameraId: String) {
        prefs.edit().remove(credentialKey(cameraId)).apply()
    }

    private fun credentialKey(cameraId: String) = "$KEY_CREDENTIALS_PREFIX$cameraId"

    /**
     * Credentials, deliberately without a useful `toString()`.
     *
     * A data class here would print the password into any log line or crash trace
     * that touched it, which is exactly the kind of leak that survives review
     * because nothing looks wrong at the call site.
     */
    class Credentials(val username: String, val password: String) {
        override fun toString(): String = "Credentials(username=$username, password=***)"
    }

    private companion object {
        const val TAG = "SecureCameraStore"
        const val FILE_NAME = "ftycam_secure"
        const val KEY_CAMERAS = "cameras"
        const val KEY_CREDENTIALS_PREFIX = "cred_"
    }
}

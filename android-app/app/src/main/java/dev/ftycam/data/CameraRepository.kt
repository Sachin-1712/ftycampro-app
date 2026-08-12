package dev.ftycam.data

import dev.ftycam.data.model.Address
import dev.ftycam.data.model.Camera
import dev.ftycam.transport.pppp.PpppDiscovery
import dev.ftycam.transport.pppp.PpppProtocol
import dev.ftycam.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the camera list.
 *
 * Reads are served from an in-memory [StateFlow] so the UI never touches disk on
 * the main thread; writes go through to the encrypted store immediately, because
 * losing a camera the user just added to a process death is a worse trade than
 * the write cost.
 */
class CameraRepository(
    private val store: SecureCameraStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras: StateFlow<List<Camera>> = _cameras.asStateFlow()

    suspend fun load() = withContext(ioDispatcher) {
        _cameras.value = store.loadCameras()
        Log.i(TAG, "loaded ${_cameras.value.size} camera(s)")
    }

    suspend fun add(
        camera: Camera,
        username: String = "",
        password: String = "",
    ) = withContext(ioDispatcher) {
        _cameras.value = _cameras.value + camera
        store.saveCameras(_cameras.value)
        if (username.isNotEmpty() || password.isNotEmpty()) {
            store.saveCredentials(camera.id, username, password)
        }
    }

    suspend fun update(camera: Camera) = withContext(ioDispatcher) {
        _cameras.value = _cameras.value.map { if (it.id == camera.id) camera else it }
        store.saveCameras(_cameras.value)
    }

    suspend fun delete(cameraId: String) = withContext(ioDispatcher) {
        _cameras.value = _cameras.value.filterNot { it.id == cameraId }
        store.saveCameras(_cameras.value)
        store.deleteCredentials(cameraId)
    }

    fun find(cameraId: String): Camera? = _cameras.value.firstOrNull { it.id == cameraId }

    suspend fun credentialsFor(cameraId: String): SecureCameraStore.Credentials? =
        withContext(ioDispatcher) { store.loadCredentials(cameraId) }

    /**
     * Broadcast a PPPP LAN_SEARCH and report what answers.
     *
     * Delegates to [PpppDiscovery] so the app and `tools/p2p_probe.py` send the
     * identical probe — if the script finds the camera from a PC and this doesn't
     * find it from the phone, that discrepancy is itself informative.
     */
    suspend fun discover(
        timeoutMs: Int = PpppDiscovery.DEFAULT_TIMEOUT_MS,
    ): List<PpppDiscovery.Endpoint> = withContext(ioDispatcher) {
        PpppDiscovery.search(timeoutMs).also {
            Log.i(TAG, "discovery returned ${it.size} endpoint(s)")
        }
    }

    /**
     * Save a camera found by discovery.
     *
     * Stored by **UID**, never by the endpoint it happened to answer from: the
     * reply port is ephemeral and the IP moves with DHCP. The host is kept only as
     * display metadata.
     */
    suspend fun addDiscovered(
        endpoint: PpppDiscovery.Endpoint,
        name: String,
    ) = withContext(ioDispatcher) {
        val uid = endpoint.uid
        val camera = Camera(
            name = name,
            address = if (uid != null) {
                Address.Uid(uid)
            } else {
                // No UID in the reply — fall back to the canonical PPPP port, not
                // the ephemeral one we were answered from.
                Address.Network(endpoint.host, PpppProtocol.DEFAULT_PORT)
            },
            lastKnownHost = endpoint.host,
            lastSeenAtMillis = endpoint.discoveredAtMillis,
        )
        add(camera)
    }

    /** Record where a camera was last seen. Metadata only — never an endpoint. */
    suspend fun noteLastSeen(cameraId: String, host: String) = withContext(ioDispatcher) {
        val existing = find(cameraId) ?: return@withContext
        update(
            existing.copy(
                lastKnownHost = host,
                lastSeenAtMillis = System.currentTimeMillis(),
            )
        )
    }

    private companion object {
        const val TAG = "CameraRepository"
    }
}

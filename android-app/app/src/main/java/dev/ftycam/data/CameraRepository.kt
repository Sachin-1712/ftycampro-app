package dev.ftycam.data

import dev.ftycam.data.model.Camera
import dev.ftycam.transport.pppp.PpppProtocol
import dev.ftycam.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

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
     * This is the same probe as `tools/p2p_probe.py`, which is the point: if the
     * script finds the camera from a PC, this finds it from the phone, and if it
     * doesn't the discrepancy is itself informative — it would mean the phone's
     * network path differs from the PC's, which usually means client isolation
     * applies to one and not the other.
     */
    suspend fun discover(timeoutMs: Int = DISCOVERY_TIMEOUT_MS): List<DiscoveredCamera> =
        withContext(ioDispatcher) {
            val found = mutableMapOf<String, DiscoveredCamera>()
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = SOCKET_POLL_MS

                    val probe = PpppProtocol.lanSearch()
                    socket.send(
                        DatagramPacket(
                            probe,
                            probe.size,
                            InetAddress.getByName(BROADCAST_ADDRESS),
                            PpppProtocol.DEFAULT_PORT,
                        )
                    )

                    val buffer = ByteArray(PpppProtocol.MAX_PACKET_SIZE)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val datagram = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(datagram)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val packet = PpppProtocol.decode(datagram.data, datagram.length) ?: continue
                        val host = datagram.address.hostAddress ?: continue
                        found[host] = DiscoveredCamera(
                            host = host,
                            port = datagram.port,
                            uid = PpppProtocol.decodeUid(packet.payload),
                            replyType = packet.typeName,
                        )
                        Log.i(TAG, "discovered $host -> ${packet.typeName}")
                    }
                }
            }.onFailure { Log.w(TAG, "discovery failed: ${it.message}") }
            found.values.toList()
        }

    data class DiscoveredCamera(
        val host: String,
        val port: Int,
        val uid: String?,
        val replyType: String,
    )

    private companion object {
        const val TAG = "CameraRepository"
        const val BROADCAST_ADDRESS = "255.255.255.255"
        const val DISCOVERY_TIMEOUT_MS = 4_000
        const val SOCKET_POLL_MS = 500
    }
}

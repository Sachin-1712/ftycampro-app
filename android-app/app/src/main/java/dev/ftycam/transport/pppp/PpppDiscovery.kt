package dev.ftycam.transport.pppp

import dev.ftycam.transport.ProtocolTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * PPPP LAN discovery — the one implementation, used by both the camera list and
 * the transport.
 *
 * ## Why the reply port must never be persisted
 *
 * Discovery is a broadcast of `LAN_SEARCH` to the well-known port
 * [PpppProtocol.DEFAULT_PORT] (32108). The camera replies from an **ephemeral
 * source port that changes on every single search** — ports 10473, 11791, 14206,
 * 22120 and 25288 have all been observed from the same device. Storing that port
 * as if it were the camera's address produces a saved endpoint that is dead the
 * moment it is written, and a "camera did not respond" error that looks like a
 * network fault but is really a stale port.
 *
 * So: the well-known port is the only thing ever *sent to* for discovery, and the
 * reply port is treated as a per-attempt fact with no lifetime beyond the
 * connection attempt that observed it.
 */
object PpppDiscovery {

    const val BROADCAST_ADDRESS = "255.255.255.255"
    const val DEFAULT_TIMEOUT_MS = 4_000
    private const val SOCKET_POLL_MS = 500

    /**
     * One camera's answer to a single discovery round.
     *
     * @param sourcePort the port the reply *came from*. Ephemeral — valid only for
     *   the attempt that observed it. Never persist this.
     */
    data class Endpoint(
        val uid: String?,
        val host: String,
        val sourcePort: Int,
        val replyType: String,
        val discoveredAtMillis: Long = System.currentTimeMillis(),
    ) {
        val display: String get() = "$host:$sourcePort"
    }

    /**
     * Broadcast a `LAN_SEARCH` and collect every reply until [timeoutMs] elapses.
     *
     * @param uidFilter if non-null, only endpoints reporting this UID are returned.
     */
    suspend fun search(
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        uidFilter: String? = null,
        trace: ProtocolTrace? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<Endpoint> = withContext(dispatcher) {
        val found = linkedMapOf<String, Endpoint>()

        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = SOCKET_POLL_MS

                val probe = PpppProtocol.lanSearch()
                val destination = "$BROADCAST_ADDRESS:${PpppProtocol.DEFAULT_PORT}"
                socket.send(
                    DatagramPacket(
                        probe,
                        probe.size,
                        InetAddress.getByName(BROADCAST_ADDRESS),
                        PpppProtocol.DEFAULT_PORT,
                    )
                )
                trace?.sent("LAN_SEARCH", destination)

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
                    val host = datagram.address?.hostAddress ?: continue
                    val endpoint = Endpoint(
                        uid = PpppProtocol.decodeUid(packet.payload),
                        host = host,
                        sourcePort = datagram.port,
                        replyType = packet.typeName,
                    )
                    trace?.received("${packet.typeName} uid=${endpoint.uid}", endpoint.display)

                    if (uidFilter != null && !uidFilter.equals(endpoint.uid, ignoreCase = true)) {
                        trace?.note("ignoring ${endpoint.uid} — looking for $uidFilter")
                        continue
                    }
                    // Keyed by UID where known so a device answering twice in one
                    // round collapses to its latest endpoint rather than appearing
                    // as two cameras.
                    found[endpoint.uid ?: host] = endpoint
                }

                if (found.isEmpty()) {
                    trace?.silence("LAN_SEARCH", destination, timeoutMs.toLong())
                }
            }
        }.onFailure { trace?.note("discovery failed: ${it.message}") }

        found.values.toList()
    }

    /** Locate one camera by UID. Returns null if it did not answer this round. */
    suspend fun findByUid(
        uid: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        trace: ProtocolTrace? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Endpoint? = search(timeoutMs, uid, trace, dispatcher).firstOrNull()
}

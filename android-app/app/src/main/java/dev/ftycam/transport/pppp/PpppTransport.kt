package dev.ftycam.transport.pppp

import dev.ftycam.data.model.Address
import dev.ftycam.data.model.Camera
import dev.ftycam.data.model.StreamQuality
import dev.ftycam.transport.CameraTransport
import dev.ftycam.transport.Codec
import dev.ftycam.transport.ConnectionState
import dev.ftycam.transport.MediaChunk
import dev.ftycam.transport.SessionDetail
import dev.ftycam.transport.TransportException
import dev.ftycam.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * PPPP/PPCS transport.
 *
 * **Partially implemented, on purpose.** The framing, discovery, session
 * handshake and keepalives below are real and runnable. The *command* layer that
 * rides inside DRW — the login blob, the start-stream command, the per-frame media
 * header — is vendor-specific and cannot be written until a capture of the vendor
 * app reveals it. Those points are marked and throw
 * [TransportException.NotImplemented] rather than silently doing nothing.
 *
 * Filling them in is the work described in `docs/INVESTIGATION-CHECKLIST.md`
 * track E. Prototype in `tools/poc_client.py` first — iterating there is much
 * faster than rebuilding an APK — then port the working bytes here.
 */
class PpppTransport(
    private val camera: Camera,
    private val ioDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : CameraTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state = _state.asStateFlow()

    // Video must not block the receive loop: dropping the oldest frame under back
    // pressure is the correct behaviour for a live stream, where a late frame is
    // worth less than a current one.
    private val _video = MutableSharedFlow<MediaChunk>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val video = _video.asSharedFlow()

    private val _audio = MutableSharedFlow<MediaChunk>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val audio = _audio.asSharedFlow()

    override val hasAudio: Boolean = true

    private var socket: DatagramSocket? = null
    private var remote: InetSocketAddress? = null
    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null
    private var keepaliveJob: Job? = null

    private val videoAssembler = FrameAssembler(Codec.H264)
    private val audioAssembler = FrameAssembler(Codec.G711_ULAW)

    override suspend fun connect(quality: StreamQuality) = withContext(ioDispatcher) {
        _state.value = ConnectionState.Connecting

        val target = resolveTarget()
        Log.i(TAG, "connecting to $target")

        val sock = DatagramSocket().apply { soTimeout = SOCKET_TIMEOUT_MS }
        socket = sock
        remote = target

        val sessionScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        scope = sessionScope

        val ready = performHandshake(sock, target)
        if (!ready) {
            cleanup()
            throw TransportException.Unreachable(target.toString())
        }

        receiveJob = sessionScope.launch { receiveLoop(sock) }
        keepaliveJob = sessionScope.launch { keepaliveLoop(sock, target) }

        _state.value = ConnectionState.Connected(
            SessionDetail(
                transportName = "PPPP/PPCS",
                remote = target.toString(),
                videoCodec = Codec.H264,
                audioCodec = Codec.G711_ULAW,
                notes = "Session established. Media requires the DRW command layer.",
            )
        )

        // Everything above works. This does not, and cannot until phase 3 delivers
        // the bytes. Failing loudly here beats a connected-but-black screen that
        // looks like a bug in the player.
        startStream(quality)
    }

    /**
     * Resolve the camera to a socket address.
     *
     * A direct host/port is used as given. A UID needs either LAN discovery or a
     * cloud rendezvous; only the first is attempted, because the second would mean
     * involving the vendor's servers, which this project is trying to avoid.
     */
    private suspend fun resolveTarget(): InetSocketAddress = when (val address = camera.address) {
        is Address.Network -> InetSocketAddress(address.host, address.port)
        is Address.Uid -> discoverByUid(address.uid)
            ?: throw TransportException.Unreachable(
                address.uid,
            ).also { Log.w(TAG, "no LAN device answered for UID ${address.uid}") }
    }

    /** Broadcast a LAN_SEARCH and keep whichever device reports the wanted UID. */
    private suspend fun discoverByUid(uid: String): InetSocketAddress? = withContext(ioDispatcher) {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = DISCOVERY_TIMEOUT_MS

            val probe = PpppProtocol.lanSearch()
            sock.send(
                DatagramPacket(
                    probe,
                    probe.size,
                    InetAddress.getByName(BROADCAST_ADDRESS),
                    PpppProtocol.DEFAULT_PORT,
                )
            )
            Log.d(TAG, "LAN_SEARCH broadcast for $uid")

            val buffer = ByteArray(PpppProtocol.MAX_PACKET_SIZE)
            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    sock.receive(datagram)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val packet = PpppProtocol.decode(datagram.data, datagram.length) ?: continue
                val reported = PpppProtocol.decodeUid(packet.payload)
                Log.d(TAG, "discovery reply from ${datagram.address}: $packet uid=$reported")
                if (reported != null && reported.equals(uid, ignoreCase = true)) {
                    return@withContext InetSocketAddress(datagram.address, datagram.port)
                }
            }
            null
        }
    }

    private suspend fun performHandshake(sock: DatagramSocket, target: InetSocketAddress): Boolean {
        send(sock, target, PpppProtocol.lanSearch())

        val reply = awaitPacket(
            sock,
            setOf(
                PpppProtocol.MessageType.PUNCH_PKT,
                PpppProtocol.MessageType.LAN_NOTIFY,
                PpppProtocol.MessageType.P2P_RDY,
            ),
            HANDSHAKE_TIMEOUT_MS,
        )
        if (reply == null) {
            Log.w(TAG, "no answer to LAN_SEARCH from $target")
            return false
        }
        Log.i(TAG, "device answered: $reply")

        if (camera.address is Address.Uid) {
            val did = runCatching { PpppProtocol.encodeUid(camera.address.uid) }.getOrNull()
            if (did != null) {
                send(sock, target, PpppProtocol.Packet(PpppProtocol.MessageType.P2P_REQ, did).encode())
                awaitPacket(sock, setOf(PpppProtocol.MessageType.P2P_RDY), HANDSHAKE_TIMEOUT_MS)
            }
        }
        return true
    }

    private suspend fun awaitPacket(
        sock: DatagramSocket,
        wanted: Set<Int>,
        timeoutMs: Long,
    ): PpppProtocol.Packet? = withTimeoutOrNull(timeoutMs) {
        val buffer = ByteArray(PpppProtocol.MAX_PACKET_SIZE)
        while (isActive) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(datagram)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val packet = PpppProtocol.decode(datagram.data, datagram.length) ?: continue
            if (packet.type in wanted) return@withTimeoutOrNull packet
        }
        null
    }

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(PpppProtocol.MAX_PACKET_SIZE)
        while (scope?.isActive == true) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(datagram)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (scope?.isActive == true) {
                    Log.w(TAG, "receive loop ended: ${e.message}")
                    _state.value = ConnectionState.Failed(
                        TransportException.ProtocolError("Connection lost", e)
                    )
                }
                return
            }

            val packet = PpppProtocol.decode(datagram.data, datagram.length) ?: continue
            when (packet.type) {
                PpppProtocol.MessageType.ALIVE ->
                    remote?.let { send(sock, it, PpppProtocol.aliveAck()) }

                PpppProtocol.MessageType.DRW -> {
                    remote?.let { send(sock, it, PpppProtocol.drwAck(packet.payload)) }
                    handleDrw(packet.payload)
                }

                PpppProtocol.MessageType.CLOSE -> {
                    Log.i(TAG, "camera closed the session")
                    _state.value = ConnectionState.Disconnected
                    return
                }
            }
        }
    }

    private suspend fun handleDrw(payload: ByteArray) {
        val header = PpppProtocol.DrwHeader.parse(payload) ?: return
        val body = payload.copyOfRange(header.bodyOffset, payload.size)
        when (header.channel) {
            PpppProtocol.Channel.VIDEO ->
                videoAssembler.feed(body)?.let { _video.emit(it) }

            PpppProtocol.Channel.AUDIO ->
                audioAssembler.feed(body)?.let { _audio.emit(it) }

            PpppProtocol.Channel.CONTROL ->
                Log.d(TAG, "control: ${body.take(32).joinToString(" ") { "%02x".format(it) }}")

            else ->
                Log.d(TAG, "unknown DRW channel ${header.channel}, ${body.size} bytes")
        }
    }

    private suspend fun keepaliveLoop(sock: DatagramSocket, target: InetSocketAddress) {
        while (scope?.isActive == true) {
            kotlinx.coroutines.delay(KEEPALIVE_INTERVAL_MS)
            send(sock, target, PpppProtocol.alive())
        }
    }

    private fun send(sock: DatagramSocket, target: InetSocketAddress, data: ByteArray) {
        runCatching { sock.send(DatagramPacket(data, data.size, target)) }
            .onFailure { Log.w(TAG, "send failed: ${it.message}") }
    }

    /**
     * Tell the camera to start sending video.
     *
     * The command id and its argument structure are vendor-specific. Capture the
     * vendor app starting a stream, find the first DRW packet the *app* sends on
     * the control channel after the handshake, and put those bytes here.
     */
    private fun startStream(quality: StreamQuality): Nothing =
        throw TransportException.NotImplemented(
            "Starting the video stream (the DRW control command)"
        )

    override suspend fun setQuality(quality: StreamQuality): Boolean = false

    override suspend fun disconnect() = withContext(ioDispatcher) {
        cleanup()
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun cleanup() {
        keepaliveJob?.cancelAndJoin()
        receiveJob?.cancelAndJoin()
        socket?.let { sock ->
            remote?.let { send(sock, it, PpppProtocol.close()) }
            sock.close()
        }
        socket = null
        remote = null
        scope = null
    }

    private companion object {
        const val TAG = "PpppTransport"
        const val BROADCAST_ADDRESS = "255.255.255.255"
        const val SOCKET_TIMEOUT_MS = 1_000
        const val DISCOVERY_TIMEOUT_MS = 4_000
        const val HANDSHAKE_TIMEOUT_MS = 3_000L
        const val KEEPALIVE_INTERVAL_MS = 5_000L
    }
}

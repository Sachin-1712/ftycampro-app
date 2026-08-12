package dev.ftycam.transport.pppp

import dev.ftycam.data.model.Address
import dev.ftycam.data.model.Camera
import dev.ftycam.data.model.StreamQuality
import dev.ftycam.transport.CameraTransport
import dev.ftycam.transport.Codec
import dev.ftycam.transport.ConnectionState
import dev.ftycam.transport.HandshakeState
import dev.ftycam.transport.MediaChunk
import dev.ftycam.transport.ProtocolTrace
import dev.ftycam.transport.SessionDetail
import dev.ftycam.transport.SessionDiagnostics
import dev.ftycam.transport.TransportException
import dev.ftycam.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * PPPP/PPCS transport.
 *
 * **Partially implemented, on purpose.** Discovery, framing, keepalives and the
 * DRW reader below are real and runnable. The *session handshake* that the vendor
 * app performs after discovery is not known, and is not invented here — see
 * `research/findings/02-local-session-gap.md`.
 *
 * ## Endpoint handling
 *
 * The camera's discovery reply comes from an ephemeral source port that changes on
 * every search (10473, 11791, 14206, 22120, 25288 all observed from one device).
 * Nothing here persists that port. Every connection attempt begins with a fresh
 * [PpppDiscovery] round to obtain the current IP and reply port, and those are used
 * only for that attempt.
 */
class PpppTransport(
    private val camera: Camera,
    /**
     * Credentials for the `0x2010` login. `admin` with an empty password is the
     * factory default on this hardware and is what the vendor app sent.
     */
    private val credentials: Credentials = Credentials("admin", "admin"),
    private val ioDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : CameraTransport {

    data class Credentials(val username: String, val password: String) {
        // Never let a password reach a log line or crash trace.
        override fun toString(): String = "Credentials(username=$username, password=***)"
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(SessionDiagnostics())
    override val diagnostics = _diagnostics.asStateFlow()

    // Dropping the oldest frame under back pressure is correct for live video: a
    // late frame is worth less than a current one.
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

    private val videoParser = VideoFrameParser()
    private val audioAssembler = FrameAssembler(Codec.G711_ULAW)

    /** Session token from the login reply. Required by every later command. */
    private var sessionToken: String? = null

    /** Outgoing command sequence number for the DATA sub-header. */
    private var commandSequence = 0

    override suspend fun connect(quality: StreamQuality) = withContext(ioDispatcher) {
        _state.value = ConnectionState.Connecting

        val uid = (camera.address as? Address.Uid)?.uid
        val trace = ProtocolTrace(uid)
        _diagnostics.value = SessionDiagnostics(uid = uid, handshake = HandshakeState.PENDING)

        // Step 1 — always rediscover. Any address we hold is stale by construction.
        val endpoint = resolveEndpoint(uid, trace)

        _diagnostics.update {
            it.copy(
                discoverySucceeded = true,
                uid = endpoint.uid ?: uid,
                host = endpoint.host,
                sourcePort = endpoint.sourcePort,
                discoveredAtMillis = endpoint.discoveredAtMillis,
                trace = trace.snapshot(),
            )
        }

        val sock = DatagramSocket().apply { soTimeout = SOCKET_TIMEOUT_MS }
        socket = sock
        val sessionScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        scope = sessionScope

        // Step 2 — session request against the freshly observed endpoint.
        val established = attemptHandshake(sock, endpoint, trace)

        _diagnostics.update {
            it.copy(
                handshake = if (established) HandshakeState.SUCCEEDED else HandshakeState.FAILED,
                trace = trace.snapshot(),
            )
        }

        if (!established) {
            cleanup()
            _state.value = ConnectionState.Disconnected
            throw TransportException.SessionNotEstablished(endpoint.display)
        }

        val target = remote ?: InetSocketAddress(endpoint.host, endpoint.sourcePort)
        val token = login(sock, target, credentials.username, credentials.password, trace)
        _diagnostics.update { it.copy(trace = trace.snapshot()) }
        if (token == null) {
            cleanup()
            _state.value = ConnectionState.Disconnected
            throw TransportException.AuthenticationFailed(
                "The camera did not accept the stored credentials, or did not reply to the login."
            )
        }
        sessionToken = token

        // Start the receive loop before asking for video, so no frames are missed.
        receiveJob = sessionScope.launch { receiveLoop(sock) }
        keepaliveJob = sessionScope.launch { keepaliveLoop(sock) }

        startStream(sock, target, token, trace)
        _diagnostics.update { it.copy(trace = trace.snapshot()) }

        _state.value = ConnectionState.Connected(
            SessionDetail(
                transportName = "PPPP/PPCS",
                remote = endpoint.display,
                videoCodec = Codec.H264,
                audioCodec = Codec.G711_ULAW,
                notes = "Session open, logged in, stream requested.",
            )
        )
    }

    /**
     * Locate the camera right now.
     *
     * A UID camera is always rediscovered. A manually-entered host/port is used as
     * given, because the user chose it deliberately and it may point at something
     * discovery cannot see.
     */
    private suspend fun resolveEndpoint(
        uid: String?,
        trace: ProtocolTrace,
    ): PpppDiscovery.Endpoint = when (val address = camera.address) {
        is Address.Network -> {
            trace.note("using manually entered endpoint ${address.host}:${address.port}")
            PpppDiscovery.Endpoint(
                uid = uid,
                host = address.host,
                sourcePort = address.port,
                replyType = "(not discovered — manual address)",
            )
        }

        is Address.Uid -> {
            trace.note("rediscovering ${address.uid} (stored ports are never reused)")
            PpppDiscovery.findByUid(address.uid, trace = trace)
                ?: run {
                    _diagnostics.update {
                        it.copy(discoverySucceeded = false, trace = trace.snapshot())
                    }
                    throw TransportException.NotDiscovered(address.uid)
                }
        }
    }

    /**
     * Send the session request to the freshly discovered endpoint.
     *
     * Only message types defined by the PPPP protocol are sent — nothing is
     * invented. If the camera stays silent, that is reported as a failed handshake
     * rather than dressed up as success.
     *
     * The canonical port is tried as a second endpoint because discovery proves the
     * camera listens there, so a silent ephemeral port and a silent 32108 are
     * different facts worth telling apart.
     */
    /**
     * Open the session the way the vendor app does: `PUNCH_READY` carrying the DID,
     * to the endpoint discovery just reported. The camera accepts by echoing
     * `PUNCH_READY` back (finding 05).
     */
    private suspend fun attemptHandshake(
        sock: DatagramSocket,
        endpoint: PpppDiscovery.Endpoint,
        trace: ProtocolTrace,
    ): Boolean {
        val uid = endpoint.uid
        if (uid == null) {
            trace.note("no UID from discovery — cannot build the DID for PUNCH_READY")
            return false
        }

        val candidates = buildList {
            add(InetSocketAddress(endpoint.host, endpoint.sourcePort))
            if (endpoint.sourcePort != PpppProtocol.DEFAULT_PORT) {
                add(InetSocketAddress(endpoint.host, PpppProtocol.DEFAULT_PORT))
            }
        }
        _diagnostics.update { it.copy(attemptedEndpoints = candidates.map { c -> c.toString() }) }

        val punch = runCatching { PpppProtocol.punchReady(uid) }.getOrElse {
            trace.note("could not encode DID for $uid: ${it.message}")
            return false
        }

        for (candidate in candidates) {
            remote = candidate
            val label = "${candidate.address?.hostAddress}:${candidate.port}"

            runCatching { sock.send(DatagramPacket(punch, punch.size, candidate)) }
            trace.sent("PUNCH_READY (+DID)", label)

            val reply = awaitPacket(
                sock,
                setOf(PpppProtocol.MessageType.PUNCH_READY, PpppProtocol.MessageType.ALIVE),
                HANDSHAKE_TIMEOUT_MS,
                trace,
            )
            if (reply != null) {
                // The camera pings as soon as it accepts; answer immediately so the
                // session isn't torn down while we continue.
                if (reply.type == PpppProtocol.MessageType.ALIVE) {
                    send(sock, candidate, PpppProtocol.Packet(PpppProtocol.MessageType.ALIVE_ACK))
                }
                trace.note("session accepted at $label")
                return true
            }
            trace.silence("PUNCH_READY", label, HANDSHAKE_TIMEOUT_MS)
        }
        return false
    }

    /**
     * Log in and capture the session token.
     *
     * Sends command `0x2010` on channel 0 with the credentials XOR-0x01, and reads
     * the token out of the `0x2011` reply. Every later command carries it.
     */
    private suspend fun login(
        sock: DatagramSocket,
        target: InetSocketAddress,
        username: String,
        password: String,
        trace: ProtocolTrace,
    ): String? {
        val body = PpppCommands.login(username, password)
        val packet = PpppProtocol.data(PpppProtocol.Channel.COMMAND, commandSequence++, body)
        runCatching { sock.send(DatagramPacket(packet, packet.size, target)) }
        // Credentials are never traced — only the fact that a login went out.
        trace.sent("CMD 0x2010 LOGIN (credentials redacted)", "${target.address?.hostAddress}:${target.port}")

        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        val buffer = ByteArray(PpppProtocol.MAX_PACKET_SIZE)
        while (System.currentTimeMillis() < deadline) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(datagram)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val packetIn = PpppProtocol.decode(datagram.data, datagram.length) ?: continue
            when (packetIn.type) {
                PpppProtocol.MessageType.ALIVE ->
                    send(sock, target, PpppProtocol.Packet(PpppProtocol.MessageType.ALIVE_ACK))

                PpppProtocol.MessageType.DATA -> {
                    val header = PpppProtocol.DrwHeader.parse(packetIn.payload) ?: continue
                    val cmdBody = packetIn.payload.copyOfRange(header.bodyOffset, packetIn.payload.size)
                    ackData(sock, target, header)
                    val reply = PpppCommands.parse(cmdBody) ?: continue
                    trace.received("CMD 0x%04X".format(reply.cmd), "${datagram.address?.hostAddress}:${datagram.port}")
                    if (reply.cmd == PpppCommands.Cmd.LOGIN_REPLY) {
                        val token = PpppCommands.sessionToken(reply)
                        if (token == null) trace.note("login rejected by camera")
                        else trace.note("login accepted, session token acquired")
                        return token
                    }
                }
            }
        }
        trace.silence("CMD 0x2010 LOGIN", "${target.address?.hostAddress}:${target.port}", LOGIN_TIMEOUT_MS)
        return null
    }

    private fun ackData(
        sock: DatagramSocket,
        target: InetSocketAddress,
        header: PpppProtocol.DrwHeader,
    ) {
        val ack = PpppProtocol.dataAck(header.channel, listOf(header.sequence))
        runCatching { sock.send(DatagramPacket(ack, ack.size, target)) }
    }

    private suspend fun awaitPacket(
        sock: DatagramSocket,
        wanted: Set<Int>,
        timeoutMs: Long,
        trace: ProtocolTrace,
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
            trace.received(packet.typeName, "${datagram.address?.hostAddress}:${datagram.port}")
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
                    remote?.let { send(sock, it, PpppProtocol.Packet(PpppProtocol.MessageType.ALIVE_ACK)) }

                PpppProtocol.MessageType.DATA -> {
                    val header = PpppProtocol.DrwHeader.parse(packet.payload)
                    if (header != null) {
                        remote?.let { ackData(sock, it, header) }
                        handleData(header, packet.payload)
                    }
                }

                PpppProtocol.MessageType.CLOSE -> {
                    Log.i(TAG, "camera closed the session")
                    _state.value = ConnectionState.Disconnected
                    return
                }
            }
        }
    }

    private suspend fun handleData(header: PpppProtocol.DrwHeader, payload: ByteArray) {
        val body = payload.copyOfRange(header.bodyOffset, payload.size)
        when (header.channel) {
            PpppProtocol.Channel.VIDEO ->
                videoParser.feed(body).forEach { _video.emit(it) }

            PpppProtocol.Channel.COMMAND -> {
                val reply = PpppCommands.parse(body)
                if (reply != null) Log.d(TAG, "cmd reply 0x%04X (%d bytes)".format(reply.cmd, reply.payload.size))
            }

            PpppProtocol.Channel.AUDIO ->
                audioAssembler.feed(body)?.let { _audio.emit(it) }

            else -> Log.d(TAG, "data channel ${header.channel}, ${body.size} bytes")
        }
    }

    private suspend fun keepaliveLoop(sock: DatagramSocket) {
        while (scope?.isActive == true) {
            delay(KEEPALIVE_INTERVAL_MS)
            remote?.let { send(sock, it, PpppProtocol.Packet(PpppProtocol.MessageType.ALIVE)) }
        }
    }

    private fun send(sock: DatagramSocket, target: InetSocketAddress, packet: PpppProtocol.Packet) {
        val data = packet.encode()
        runCatching { sock.send(DatagramPacket(data, data.size, target)) }
            .onFailure { Log.w(TAG, "send failed: ${it.message}") }
    }

    /**
     * Ask the camera to start sending video.
     *
     * Replays the command burst the vendor app sends immediately before frames
     * begin. The capture does not isolate which single command means "start", so
     * the observed sequence is reproduced rather than guessed at — see
     * [PpppCommands.startStreamSequence].
     */
    private fun startStream(
        sock: DatagramSocket,
        target: InetSocketAddress,
        token: String,
        trace: ProtocolTrace,
    ) {
        val label = "${target.address?.hostAddress}:${target.port}"
        PpppCommands.startStreamSequence(token).forEach { body ->
            val packet = PpppProtocol.data(PpppProtocol.Channel.COMMAND, commandSequence++, body)
            runCatching { sock.send(DatagramPacket(packet, packet.size, target)) }
            val cmd = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
            trace.sent("CMD 0x%04X (stream setup)".format(cmd), label)
        }
    }

    override suspend fun setQuality(quality: StreamQuality): Boolean = false

    override suspend fun disconnect() = withContext(ioDispatcher) {
        cleanup()
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun cleanup() {
        keepaliveJob?.cancelAndJoin()
        receiveJob?.cancelAndJoin()
        socket?.let { sock ->
            remote?.let { send(sock, it, PpppProtocol.Packet(PpppProtocol.MessageType.CLOSE)) }
            sock.close()
        }
        socket = null
        remote = null
        scope = null
    }

    private companion object {
        const val TAG = "PpppTransport"
        const val SOCKET_TIMEOUT_MS = 1_000
        const val HANDSHAKE_TIMEOUT_MS = 3_000L

        /** The observed login reply took ~310ms; this is generous headroom. */
        const val LOGIN_TIMEOUT_MS = 5_000L

        // The camera pings roughly every 300ms, so keepalives must be brisk.
        const val KEEPALIVE_INTERVAL_MS = 2_000L
    }
}

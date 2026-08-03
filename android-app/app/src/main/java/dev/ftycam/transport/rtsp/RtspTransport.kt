package dev.ftycam.transport.rtsp

import dev.ftycam.data.model.Address
import dev.ftycam.data.model.Camera
import dev.ftycam.data.model.StreamQuality
import dev.ftycam.transport.CameraTransport
import dev.ftycam.transport.Codec
import dev.ftycam.transport.ConnectionState
import dev.ftycam.transport.MediaChunk
import dev.ftycam.transport.SessionDetail
import dev.ftycam.transport.TransportException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * RTSP, delegated to Media3's own client.
 *
 * The port scan says the camera doesn't speak RTSP, and that is probably right —
 * but the measurement it rests on is one the project hasn't validated yet (see
 * hypothesis H0), and a single working RTSP URL would make almost all of the rest
 * of this work unnecessary. Keeping the path here costs nothing and means the
 * check is one setting away rather than a rebuild.
 *
 * Unlike [dev.ftycam.transport.pppp.PpppTransport], this transport does not carry
 * media itself: it hands a URL to ExoPlayer, which owns the socket. So [video]
 * and [audio] stay empty and [rtspUrl] is what the player layer consumes.
 */
class RtspTransport(private val camera: Camera) : CameraTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state = _state.asStateFlow()

    override val video = emptyFlow<MediaChunk>()
    override val audio = emptyFlow<MediaChunk>()
    override val hasAudio: Boolean = true

    /** The URL for [dev.ftycam.stream.PlayerController] to hand to ExoPlayer. */
    var rtspUrl: String? = null
        private set

    override suspend fun connect(quality: StreamQuality) {
        val address = camera.address as? Address.Network
            ?: throw TransportException.ProtocolError("RTSP needs a host and port, not a UID")

        _state.value = ConnectionState.Connecting

        // Path varies by vendor; these are the common ones for this hardware class.
        // Which one works is a finding — record it and pin it in settings.
        val path = when (quality) {
            StreamQuality.HIGH -> "/live/ch0"
            StreamQuality.LOW -> "/live/ch1"
        }
        rtspUrl = "rtsp://${address.host}:${address.port}$path"

        _state.value = ConnectionState.Connected(
            SessionDetail(
                transportName = "RTSP",
                remote = rtspUrl.orEmpty(),
                videoCodec = Codec.H264,
                audioCodec = Codec.G711_ULAW,
                notes = "Playback is handled by ExoPlayer's RTSP client.",
            )
        )
    }

    override suspend fun disconnect() {
        rtspUrl = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun setQuality(quality: StreamQuality): Boolean = false
}

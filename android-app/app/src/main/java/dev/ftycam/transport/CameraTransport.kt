package dev.ftycam.transport

import dev.ftycam.data.model.Camera
import dev.ftycam.data.model.StreamQuality
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the app and whatever protocol the camera actually speaks.
 *
 * This interface exists because the protocol is not yet known. Everything above
 * it — view models, the player, recording, the UI — is written against these
 * methods, so identifying the protocol later means writing one new implementation
 * rather than reworking the app. See `research/03-protocol-hypotheses.md` for the
 * candidates and `tools/poc_client.py` for the reference implementation of the
 * leading one.
 *
 * Implementations must be safe to call from any thread and must not block; the
 * suspending functions do their work on an IO dispatcher.
 */
interface CameraTransport {

    /** Live connection state. Cold until [connect] is called. */
    val state: Flow<ConnectionState>

    /**
     * What the last attempt observed, updated as it proceeds.
     *
     * Separate from [state] because it stays useful after a failure — the whole
     * point is to be able to read back what happened.
     */
    val diagnostics: Flow<SessionDiagnostics>

    /**
     * Decoded-ready video units, in the order they arrive.
     *
     * For H.264 this emits Annex-B access units with start codes intact, which is
     * what Media3's extractor expects to be fed.
     */
    val video: Flow<MediaChunk>

    /** Audio units, or an empty flow if the camera has no audio channel. */
    val audio: Flow<MediaChunk>

    /**
     * Connect and authenticate.
     *
     * @throws TransportException on any failure; the message is user-facing.
     */
    suspend fun connect(quality: StreamQuality)

    suspend fun disconnect()

    /**
     * Ask the camera to switch stream quality mid-session, if it supports it.
     * Returns false if the transport must reconnect to change quality instead.
     */
    suspend fun setQuality(quality: StreamQuality): Boolean

    /** Whether this transport carries an audio channel at all. */
    val hasAudio: Boolean

    /**
     * A still image straight from the camera, if the protocol offers one.
     *
     * Returns null when it doesn't — the caller then falls back to grabbing the
     * current frame out of the decoder, which is the common case.
     */
    suspend fun requestSnapshot(): ByteArray? = null
}

/**
 * One unit of media.
 *
 * [data] is owned by the receiver and is safe to retain; transports must not
 * reuse the array. That costs an allocation per frame and is worth it — the
 * alternative is a buffer-pool contract that every future transport author has to
 * get right.
 */
data class MediaChunk(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean = false,
    val codec: Codec = Codec.UNKNOWN,
) {
    // Data classes generate identity-based equals for arrays, which is wrong here
    // and produces confusing test failures.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaChunk) return false
        return presentationTimeUs == other.presentationTimeUs &&
            isKeyFrame == other.isKeyFrame &&
            codec == other.codec &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + codec.hashCode()
        return result
    }
}

enum class Codec {
    MJPEG,
    H264,
    H265,
    G711_ULAW,
    G711_ALAW,
    ADPCM_IMA,
    AAC,
    PCM_16LE,
    UNKNOWN,
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val detail: SessionDetail) : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data class Failed(val error: TransportException) : ConnectionState
    data object Disconnected : ConnectionState
}

/** Outcome of the PPPP session handshake, kept separate from discovery. */
enum class HandshakeState { PENDING, FAILED, SUCCEEDED }

/**
 * What the last connection attempt actually observed.
 *
 * Discovery and handshake are reported independently on purpose: they fail for
 * completely different reasons, and collapsing them into one "connection failed"
 * is what produced the misleading client-isolation message. Discovery succeeding
 * while the handshake stalls is the current known state of this project, and the
 * UI should be able to say exactly that.
 */
data class SessionDiagnostics(
    val discoverySucceeded: Boolean = false,
    val uid: String? = null,
    val host: String? = null,
    /** Ephemeral reply port from the most recent discovery. Never persisted. */
    val sourcePort: Int? = null,
    val handshake: HandshakeState = HandshakeState.PENDING,
    val attemptedEndpoints: List<String> = emptyList(),
    val trace: List<String> = emptyList(),
    val discoveredAtMillis: Long? = null,
)

/** Whatever the transport learned about the session. Surfaced in diagnostics. */
data class SessionDetail(
    val transportName: String,
    val remote: String,
    val videoCodec: Codec = Codec.UNKNOWN,
    val audioCodec: Codec = Codec.UNKNOWN,
    val notes: String = "",
)

/**
 * Failures a user might see. Each carries a [hint] because "connection failed" on
 * its own is useless when the most likely cause is a router setting three menus
 * deep.
 */
sealed class TransportException(
    message: String,
    val hint: String = "",
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Discovery itself found nothing — the camera is absent, asleep, or unreachable. */
    class NotDiscovered(uid: String, cause: Throwable? = null) : TransportException(
        "Camera not found on this network",
        "No device answered the discovery broadcast for $uid. It may be powered " +
            "off, asleep, or on a different Wi-Fi network than this phone.",
        cause,
    )

    /**
     * Discovery worked but the session never opened.
     *
     * This is the honest description of the current state of the project: the
     * camera is present and answering, and the vendor-specific handshake that
     * follows discovery has not been reverse-engineered yet. Saying anything about
     * client isolation here would be actively wrong — isolation would have stopped
     * discovery too.
     */
    class SessionNotEstablished(
        endpoint: String,
        cause: Throwable? = null,
    ) : TransportException(
        "Camera discovered, but PPPP session establishment did not complete",
        "The camera answered discovery at $endpoint, so the network path works. " +
            "It did not respond to the session request. The handshake the vendor " +
            "app performs after discovery is not yet implemented — see the " +
            "diagnostics below and research/findings/02-local-session-gap.md.",
        cause,
    )

    class AuthenticationFailed(detail: String = "") : TransportException(
        "Authentication rejected",
        detail.ifEmpty { "The camera refused the stored credentials." },
    )

    class ProtocolError(detail: String, cause: Throwable? = null) : TransportException(
        "Protocol error",
        detail,
        cause,
    )

    class NotImplemented(what: String) : TransportException(
        "Not implemented yet",
        "$what is not implemented. The protocol is still being reverse-engineered " +
            "— see the project README for where that has got to.",
    )
}

package dev.ftycam.transport

import dev.ftycam.data.model.Address
import dev.ftycam.data.model.Camera
import dev.ftycam.data.model.TransportKind
import dev.ftycam.transport.pppp.PpppTransport
import dev.ftycam.transport.rtsp.RtspTransport
import dev.ftycam.util.Log

/**
 * Chooses a transport for a camera.
 *
 * The indirection earns its keep because the protocol is not yet settled. When
 * the investigation lands on an answer, that answer becomes one new class and one
 * new branch here; nothing above this layer changes.
 */
class TransportFactory {

    fun create(camera: Camera): CameraTransport = when (camera.transport) {
        TransportKind.PPPP -> PpppTransport(camera)
        TransportKind.RTSP -> RtspTransport(camera)
        TransportKind.AUTO -> auto(camera)
    }

    /**
     * Pick the most likely transport from what the address looks like.
     *
     * A UID can only be PPPP — that address form doesn't mean anything to RTSP. A
     * host and port is ambiguous, and the port is the best available signal.
     *
     * This is a guess, and it is allowed to be, because a wrong guess surfaces as
     * a connection failure the user can correct by setting the transport
     * explicitly. It is not allowed to be a *silent* guess, hence the log line.
     */
    private fun auto(camera: Camera): CameraTransport {
        val kind = when (val address = camera.address) {
            is Address.Uid -> TransportKind.PPPP
            is Address.Network -> when (address.port) {
                554, 8554 -> TransportKind.RTSP
                else -> TransportKind.PPPP
            }
        }
        Log.i(TAG, "AUTO resolved to $kind for ${camera.displayAddress}")
        return create(camera.copy(transport = kind))
    }

    private companion object {
        const val TAG = "TransportFactory"
    }
}

package dev.ftycam.stream

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.H264Extractor
import dev.ftycam.transport.CameraTransport
import dev.ftycam.transport.MediaChunk
import dev.ftycam.transport.rtsp.RtspTransport
import dev.ftycam.util.Log

/**
 * Owns the ExoPlayer instance and connects it to whichever transport is in use.
 *
 * Two paths, because the two transports differ in who owns the socket:
 *
 *  - **RTSP** — ExoPlayer's own client does the networking; it just needs a URL.
 *  - **Everything else** — the transport owns the socket and pushes frames, which
 *    are fed through [TransportDataSource] into a raw-H.264 extractor.
 */
@OptIn(UnstableApi::class)
class PlayerController(context: Context) {

    private val dataSource = TransportDataSource()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(liveLoadControl())
        .build()
        .apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }

    /**
     * Buffering tuned for live video.
     *
     * ExoPlayer's defaults target on-demand playback and will happily buffer tens
     * of seconds, which for a live camera shows up as the picture running that far
     * behind reality. These values keep it near real time and accept the
     * occasional stall as the better trade — a camera view that lags by ten
     * seconds is useless in a way that one that stutters is not.
     */
    private fun liveLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 500,
            /* maxBufferMs = */ 2_000,
            /* bufferForPlaybackMs = */ 200,
            /* bufferForPlaybackAfterRebufferMs = */ 400,
        )
        .build()

    fun start(transport: CameraTransport) {
        val source = buildMediaSource(transport)
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    private fun buildMediaSource(transport: CameraTransport): MediaSource =
        if (transport is RtspTransport) {
            val url = transport.rtspUrl
                ?: error("RtspTransport.connect() must run before playback starts")
            Log.i(TAG, "playing RTSP: $url")
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true) // UDP RTP is frequently dropped on home Wi-Fi
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            Log.i(TAG, "playing pushed elementary stream")
            dataSource.reset()
            // The stream is raw Annex-B H.264 with no container, so sniffing would
            // fail; the extractor is named explicitly.
            val extractors = ExtractorsFactory { arrayOf(H264Extractor()) }
            ProgressiveMediaSource.Factory(TransportDataSource.Factory(dataSource), extractors)
                .createMediaSource(MediaItem.fromUri(TransportDataSource.URI))
        }

    /** Called from the transport's collector for every video chunk. */
    fun feed(chunk: MediaChunk) = dataSource.offer(chunk)

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        dataSource.reset()
    }

    fun release() {
        dataSource.signalEndOfStream()
        player.release()
    }

    private companion object {
        const val TAG = "PlayerController"
    }
}

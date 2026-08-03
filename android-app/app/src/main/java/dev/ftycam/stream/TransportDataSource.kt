package dev.ftycam.stream

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.ftycam.transport.MediaChunk
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Adapts a push-based transport into the pull-based [DataSource] ExoPlayer wants.
 *
 * The camera sends frames whenever it likes; ExoPlayer's loader calls [read] when
 * it wants bytes. A bounded queue sits between them.
 *
 * The queue is deliberately small. For a live stream, a deep buffer converts
 * network jitter into latency that never comes back — the player would run
 * further and further behind real time. Dropping the oldest frame when the
 * consumer falls behind keeps latency bounded, which is the right trade for live
 * video and the wrong one for recorded playback.
 */
class TransportDataSource : BaseDataSource(/* isNetwork = */ true) {

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    @Volatile
    private var pending: ByteArray? = null

    @Volatile
    private var pendingOffset: Int = 0

    @Volatile
    private var endOfStream: Boolean = false

    private var opened: Boolean = false

    /** Called from the transport's coroutine. Never blocks. */
    fun offer(chunk: MediaChunk) {
        if (!queue.offer(chunk.data)) {
            queue.poll()
            queue.offer(chunk.data)
        }
    }

    fun signalEndOfStream() {
        endOfStream = true
    }

    fun reset() {
        queue.clear()
        pending = null
        pendingOffset = 0
        endOfStream = false
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        var current = pending
        if (current == null) {
            // A live source has no natural end, so a starved queue means "wait",
            // not "finished". Poll with a timeout so the loader thread can still
            // be interrupted on release.
            current = queue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (current == null) {
                return if (endOfStream) C.RESULT_END_OF_INPUT else 0
            }
            pending = current
            pendingOffset = 0
        }

        val remaining = current.size - pendingOffset
        val toCopy = minOf(remaining, length)
        current.copyInto(buffer, offset, pendingOffset, pendingOffset + toCopy)
        pendingOffset += toCopy

        if (pendingOffset >= current.size) {
            pending = null
            pendingOffset = 0
        }

        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri() = URI

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        reset()
    }

    /** Hands the same instance to every request; the stream is a singleton. */
    class Factory(private val source: TransportDataSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = source
    }

    companion object {
        val URI: android.net.Uri = android.net.Uri.parse("ftycam://stream")

        // ~1 second at 15fps. Enough to absorb jitter, short enough that latency
        // stays under control.
        private const val QUEUE_CAPACITY = 16
        private const val READ_TIMEOUT_MS = 250L
    }
}

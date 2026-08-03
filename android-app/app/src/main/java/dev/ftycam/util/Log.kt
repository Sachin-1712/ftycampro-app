package dev.ftycam.util

import android.util.Log as AndroidLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Logging with an optional on-device ring buffer.
 *
 * There is no crash reporting service and no remote logging in this app — that
 * is the whole point of it. But reverse-engineering a protocol means needing the
 * handshake trace from a session that failed on a phone across the room, so
 * verbose logs can be captured to a local file and exported by the user
 * deliberately, from Settings.
 *
 * Nothing is written to disk unless [fileLoggingEnabled] is turned on.
 */
object Log {

    private const val MAX_BUFFERED_LINES = 2_000
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var verbose: Boolean = false

    @Volatile
    var fileLoggingEnabled: Boolean = false

    private val buffer = ConcurrentLinkedQueue<String>()

    fun d(tag: String, message: String) {
        if (!verbose) return
        AndroidLog.d(tag, message)
        record("D", tag, message)
    }

    fun i(tag: String, message: String) {
        AndroidLog.i(tag, message)
        record("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        AndroidLog.w(tag, message, throwable)
        record("W", tag, message + (throwable?.let { " (${it.message})" } ?: ""))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        AndroidLog.e(tag, message, throwable)
        record("E", tag, message + (throwable?.let { " (${it.message})" } ?: ""))
    }

    private fun record(level: String, tag: String, message: String) {
        if (!fileLoggingEnabled) return
        buffer.add("${timestampFormat.format(Date())} $level/$tag: $message")
        while (buffer.size > MAX_BUFFERED_LINES) buffer.poll()
    }

    /** Dump the buffer to a file for the user to share. Returns null if empty. */
    fun export(directory: File): File? {
        val lines = buffer.toList()
        if (lines.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(directory, "ftycam-log-$stamp.txt").apply {
            writeText(lines.joinToString("\n"))
        }
    }

    fun clear() = buffer.clear()

    /**
     * Hex dump for protocol tracing. Truncated, because a video frame in logcat is
     * both useless and slow.
     */
    fun hex(tag: String, label: String, data: ByteArray, limit: Int = 32) {
        if (!verbose) return
        val shown = data.take(limit).joinToString(" ") { "%02x".format(it) }
        val suffix = if (data.size > limit) " ... (${data.size} bytes)" else ""
        d(tag, "$label: $shown$suffix")
    }
}

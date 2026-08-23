package dev.ftycam.stream

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.ftycam.transport.MediaChunk
import dev.ftycam.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes snapshots and recordings.
 *
 * Snapshots go to the shared Pictures collection via MediaStore, so they show up
 * in the gallery — that is what a user expects from a snapshot button and it
 * needs no storage permission on API 29+.
 *
 * Recordings stay in app-private storage. They are raw H.264 elementary streams
 * rather than MP4, which the gallery cannot play, so publishing them would just
 * produce broken entries. See [startRecording] for why they aren't muxed yet.
 */
class MediaWriter(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var recordingStream: OutputStream? = null
    private var recordingFile: File? = null

    /**
     * Save an already-encoded JPEG frame.
     *
     * The camera streams MJPEG, so a snapshot is just the current frame's bytes.
     * Writing them verbatim avoids a decode/re-encode round trip and the generation
     * loss that comes with it.
     */
    suspend fun saveJpegSnapshot(jpeg: ByteArray): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val name = "ftycam-${timestamp()}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ftycam")
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore rejected the insert")
                context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
                    ?: error("Could not open $uri for writing")
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "ftycam",
                ).apply { mkdirs() }
                FileOutputStream(File(directory, name)).use { it.write(jpeg) }
            }
            name
        }.onFailure { Log.e(TAG, "snapshot failed", it) }
    }

    suspend fun saveSnapshot(bitmap: Bitmap): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val name = "ftycam-${timestamp()}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ftycam")
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore rejected the insert")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                } ?: error("Could not open $uri for writing")
                name
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "ftycam",
                ).apply { mkdirs() }
                val file = File(directory, name)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                file.absolutePath
            }
        }.onFailure { Log.e(TAG, "snapshot failed", it) }
    }

    /**
     * Begin recording to a raw H.264 elementary stream.
     *
     * Not muxed to MP4, and that is a known limitation rather than an oversight:
     * `MediaMuxer` needs a per-frame presentation timestamp, and the transport
     * currently synthesises those at a nominal frame rate because the camera's
     * real timestamps live in a per-frame header that hasn't been decoded yet
     * (see `FrameAssembler.nextTimestampUs`). Muxing against invented timestamps
     * would produce files that play at the wrong speed and drift — worse than an
     * honest `.h264` that `ffmpeg` can remux correctly later:
     *
     *     ffmpeg -framerate 15 -i recording.h264 -c copy recording.mp4
     *
     * Switch this to `MediaMuxer` once real timestamps are available.
     */
    suspend fun startRecording(cameraName: String): Result<File> = withContext(ioDispatcher) {
        runCatching {
            stopRecordingInternal()
            val directory = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val safeName = cameraName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
            val file = File(directory, "$safeName-${timestamp()}.h264")
            recordingFile = file
            recordingStream = FileOutputStream(file).buffered()
            Log.i(TAG, "recording to ${file.absolutePath}")
            file
        }.onFailure { Log.e(TAG, "could not start recording", it) }
    }

    /** Called on the receive path — must stay cheap. */
    fun writeFrame(chunk: MediaChunk) {
        val stream = recordingStream ?: return
        runCatching { stream.write(chunk.data) }
            .onFailure {
                Log.e(TAG, "write failed, stopping recording", it)
                runCatching { stopRecordingInternal() }
            }
    }

    suspend fun stopRecording(): File? = withContext(ioDispatcher) { stopRecordingInternal() }

    val isRecording: Boolean get() = recordingStream != null

    private fun stopRecordingInternal(): File? {
        val file = recordingFile
        runCatching {
            recordingStream?.flush()
            recordingStream?.close()
        }.onFailure { Log.w(TAG, "error closing recording", it) }
        recordingStream = null
        recordingFile = null
        if (file != null) Log.i(TAG, "recording saved: ${file.length()} bytes")
        return file
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val TAG = "MediaWriter"
        const val JPEG_QUALITY = 92
    }
}

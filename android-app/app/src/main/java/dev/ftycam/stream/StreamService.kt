package dev.ftycam.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.ftycam.R
import dev.ftycam.ui.MainActivity
import dev.ftycam.util.Log

/**
 * Foreground service that keeps a recording running when the app is backgrounded.
 *
 * Only started while recording. Live viewing does not need it — if the user
 * leaves the screen there is nothing to preserve — and keeping a foreground
 * notification up for a stream nobody is watching would be both wasteful and
 * slightly alarming.
 */
class StreamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cameraName = intent?.getStringExtra(EXTRA_CAMERA_NAME) ?: "camera"
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(cameraName))
        Log.i(TAG, "foreground recording service started for $cameraName")
        // Recording is a user-initiated action tied to a live session; silently
        // resurrecting it after the process dies would leave a notification the
        // user never asked for and a file they don't know about.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "foreground recording service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a recording is in progress"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(cameraName: String): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording $cameraName")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "ftycam_recording"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_CAMERA_NAME = "camera_name"

        fun start(context: Context, cameraName: String) {
            val intent = Intent(context, StreamService::class.java)
                .putExtra(EXTRA_CAMERA_NAME, cameraName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}

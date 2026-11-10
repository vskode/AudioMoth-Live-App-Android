package info.openacousticdevices.audiomothlive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service that keeps AudioMoth Live recording while the screen
 * is off or another app is in front: it holds a partial wake lock (so the
 * USB/microphone capture threads and the autosave thread keep running) and
 * shows the required notification. The audio engine itself lives in the
 * native backstage library; this service only keeps the process alive.
 */
class RecordingService : Service() {

    companion object {
        const val TAG = "AudioMothLive"
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_QUIT = "quit"

        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, RecordingService::class.java).putExtra(EXTRA_DEVICE_NAME, deviceName)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while AudioMoth Live is listening"
            setShowBadge(false)
        })
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioMothLive:recording").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "Recording service started (wake lock held)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
        val notification = buildNotification(deviceName)

        /* Called again after the microphone permission is granted, to add the microphone type */
        foregroundStarted = startForegroundWithTypes(notification) || foregroundStarted

        /* If Android kills the process, don't restart the service on its own:
           the activity owns the engine */
        return START_NOT_STICKY
    }

    /** Microphone type for the default input, connectedDevice for the USB AudioMoth */
    private fun startForegroundWithTypes(notification: Notification): Boolean {
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val candidates = buildList {
            if (micGranted) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            if (micGranted) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        for (type in candidates) {
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                Log.i(TAG, "Foreground service type 0x${Integer.toHexString(type)}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground type 0x${Integer.toHexString(type)} failed: ${e.message}")
            }
        }
        return false
    }

    private fun buildNotification(deviceName: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val quit = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_QUIT, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AudioMoth Live is listening")
            .setContentText(deviceName.ifEmpty { "Starting…" })
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.i(TAG, "Recording service stopped (wake lock released)")
        super.onDestroy()
    }
}

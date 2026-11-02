package com.vidgrab.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.vidgrab.app.VidGrabApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive (and shows the progress notification) while
 * something is downloading. It stops itself a few seconds after the last download ends.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as VidGrabApp
        if (intent?.action == ACTION_PAUSE_ALL) app.downloads.pauseAll()

        ServiceCompat.startForeground(
            this,
            Notifications.ID_FOREGROUND,
            Notifications.progress(this, app.downloads.summary.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        acquireWakeLock()

        if (observer == null) {
            observer = scope.launch {
                app.downloads.summary.collectLatest { s ->
                    if (s.active == 0) {
                        delay(IDLE_GRACE_MS) // a new download may start right now
                        if (app.downloads.summary.value.active == 0) shutDown()
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotify >= 900) {
                            lastNotify = now
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            nm.notify(Notifications.ID_FOREGROUND, Notifications.progress(this@DownloadService, s))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun shutDown() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidgrab:download").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // already released
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.vidgrab.app.action.PAUSE_ALL"
        private const val IDLE_GRACE_MS = 2500L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}

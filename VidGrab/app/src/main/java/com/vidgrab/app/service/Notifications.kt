package com.vidgrab.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vidgrab.app.MainActivity
import com.vidgrab.app.R
import com.vidgrab.app.core.DownloadItem
import com.vidgrab.app.core.formatSpeed
import com.vidgrab.app.core.localized
import com.vidgrab.app.core.mimeForExtension
import com.vidgrab.app.engine.DownloadManager
import com.vidgrab.app.engine.FileActions

object Notifications {
    private const val CHANNEL_PROGRESS = "vidgrab_progress"
    private const val CHANNEL_DONE = "vidgrab_done"
    const val ID_FOREGROUND = 1001
    private const val ID_DONE_BASE = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val c = context.localized()
        val nm = context.getSystemService(NotificationManager::class.java)
        val progress = NotificationChannel(
            CHANNEL_PROGRESS, c.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        val done = NotificationChannel(
            CHANNEL_DONE, c.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(progress)
        nm.createNotificationChannel(done)
    }

    private fun immutableFlags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        immutableFlags(),
    )

    /** The ongoing notification shown by the foreground service. */
    fun progress(context: Context, s: DownloadManager.Summary): Notification {
        val c = context.localized()
        val pause = PendingIntent.getService(
            context, 1,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
            immutableFlags(),
        )
        val pct = (s.progress * 100).toInt().coerceIn(0, 100)
        val text = buildString {
            append("$pct%")
            if (s.speedBps > 0) append(" · ").append(formatSpeed(s.speedBps))
            if (s.queued > 0) append(" · ").append(c.getString(R.string.notif_queued, s.queued))
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(c.getString(R.string.notif_downloading, s.active.coerceAtLeast(1)))
            .setContentText(text)
            .setProgress(100, pct, s.active == 0 || pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
            .addAction(R.drawable.ic_stat_download, c.getString(R.string.pause_all), pause)
            .build()
    }

    fun completed(context: Context, item: DownloadItem) {
        if (!canNotify(context)) return
        val c = context.localized()
        val contentIntent = try {
            val stored = item.outputUri
            if (stored != null) {
                val mime = mimeForExtension(item.outputName?.substringAfterLast('.', "") ?: "mp4")
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(FileActions.resolveUri(context, stored), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                PendingIntent.getActivity(context, item.id.toInt(), view, immutableFlags())
            } else openAppIntent(context)
        } catch (e: Exception) {
            openAppIntent(context)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(c.getString(R.string.notif_done))
            .setContentText(item.title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        notifySafely(context, ID_DONE_BASE + (item.id % 10000).toInt(), n)
    }

    fun failed(context: Context, item: DownloadItem) {
        if (!canNotify(context)) return
        val c = context.localized()
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(c.getString(R.string.notif_failed))
            .setContentText(item.title)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notifySafely(context, ID_DONE_BASE + (item.id % 10000).toInt(), n)
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifySafely(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // notification permission was revoked in the meantime
        }
    }
}

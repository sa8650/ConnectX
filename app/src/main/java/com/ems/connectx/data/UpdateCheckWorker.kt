package com.ems.connectx.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ems.connectx.BuildConfig
import com.ems.connectx.ConnectXApp
import com.ems.connectx.MainActivity
import com.ems.connectx.R
import java.io.IOException

/** Periodic best-effort check when ConnectX is in the background. The UI checks
 * immediately on launch/resume too, so notification permission is not required
 * to discover a new update. WorkManager schedules within OS battery limits. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.baseUrl.isBlank()) return Result.success()
        val latest = try {
            Api(prefs).checkUpdate(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE)
        } catch (_: IOException) {
            return Result.retry()
        } catch (_: Exception) {
            // No registered/downloadable release or server misconfiguration.
            // Never notify for a release we cannot actually install.
            return Result.success()
        }
        if (latest.versionCode <= BuildConfig.VERSION_CODE ||
            latest.versionCode == prefs.lastNotifiedUpdateCode) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return Result.success()

        val open = PendingIntent.getActivity(
            applicationContext, 101, Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(applicationContext, ConnectXApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("ConnectX update available")
            .setContentText("v${latest.latestVersion} (Build ${latest.versionCode}) is ready in EMS App Store.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(101, notification)
        prefs.lastNotifiedUpdateCode = latest.versionCode
        return Result.success()
    }
}

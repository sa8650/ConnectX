package com.ems.connectx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ems.connectx.sms.QueueWorker
import com.ems.connectx.data.UpdateCheckWorker
import java.util.concurrent.TimeUnit

class ConnectXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GATEWAY,
                getString(R.string.channel_gateway),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "ConnectX updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val work = PeriodicWorkRequestBuilder<QueueWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "connectx-queue",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
        val updates = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "connectx-updates", ExistingPeriodicWorkPolicy.KEEP, updates
        )
    }

    companion object {
        const val CHANNEL_GATEWAY = "connectx_gateway"
        const val CHANNEL_UPDATES = "connectx_updates"
    }
}

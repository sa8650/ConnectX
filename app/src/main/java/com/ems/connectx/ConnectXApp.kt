package com.ems.connectx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ems.connectx.sms.QueueWorker
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
        val work = PeriodicWorkRequestBuilder<QueueWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "connectx-queue",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    companion object {
        const val CHANNEL_GATEWAY = "connectx_gateway"
    }
}

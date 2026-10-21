package com.connectx.gateway.sms

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.connectx.gateway.data.Api
import com.connectx.gateway.data.Prefs

class ControlTelemetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val connections = prefs.connections().filter { it.deviceId.isNotBlank() && it.deviceToken.startsWith("cxd_") }
        if (connections.isEmpty()) return Result.success()
        val api = Api(prefs)
        var failed = false
        for (connection in connections) {
            try { api.controlHeartbeat(connection) } catch (_: Exception) { failed = true }
        }
        for (event in prefs.pendingMonitorEvents()) {
            try {
                api.sendControlSmsEvent(event)
                prefs.removeMonitorEvent(event)
            } catch (_: Exception) {
                failed = true
                break
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ControlTelemetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "connectx-control-telemetry-sync",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

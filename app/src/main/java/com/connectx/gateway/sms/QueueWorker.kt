package com.connectx.gateway.sms

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class QueueWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = try {
        val outcome = QueueProcessor.drain(applicationContext)
        if (outcome.networkFailures > 0) Result.retry() else Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

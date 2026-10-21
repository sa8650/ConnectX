package com.connectx.gateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.connectx.gateway.data.Api
import com.connectx.gateway.data.Prefs
import com.connectx.gateway.data.SmsReport
import com.connectx.gateway.data.MonitorSmsEvent
import java.time.Instant
import kotlin.concurrent.thread

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = handleSmsCallback(this, context, intent, resultCode, delivery = false)
}

class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = handleSmsCallback(this, context, intent, resultCode, delivery = true)
}

private fun handleSmsCallback(receiver: BroadcastReceiver, context: Context, intent: Intent, resultCode: Int, delivery: Boolean) {
    val jobId = intent.getStringExtra(SmsSender.EXTRA_JOB) ?: return
    val shopId = intent.getStringExtra(SmsSender.EXTRA_SHOP) ?: return
    val claimToken = intent.getStringExtra(SmsSender.EXTRA_CLAIM).orEmpty()
    // Setup-test SMSs have no EMS claim and must never be reported as queue jobs.
    if (claimToken.isBlank()) return

    val parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1)
    val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
    val success = resultCode == Activity.RESULT_OK
    val error = if (success) null else smsResultMessage(resultCode, delivery)
    val pending = receiver.goAsync()
    thread(name = if (delivery) "cx-sms-delivery-result" else "cx-sms-send-result") {
        try {
            val prefs = Prefs(context)
            val outcomes = SmsResultLedger.record(
                context = context,
                shopId = shopId,
                jobId = jobId,
                claimToken = claimToken,
                partCount = parts,
                partIndex = part,
                delivery = delivery,
                success = success,
                error = error
            )
            outcomes.forEach { outcome ->
                val report = SmsReport(
                    shopId = shopId,
                    jobId = jobId,
                    claimToken = claimToken,
                    status = outcome.status,
                    error = outcome.error,
                    retryable = outcome.retryable
                )
                prefs.enqueueReport(report)
                try {
                    Api(prefs).report(
                        shopId = report.shopId,
                        jobId = report.jobId,
                        claimToken = report.claimToken,
                        status = report.status,
                        error = report.error,
                        retryable = report.retryable
                    )
                    prefs.removeReport(report)
                } catch (_: Exception) {
                    scheduleReportSync(context)
                }
                val resultAt = Instant.now().toString()
                val profile = prefs.getAdminProfile()
                val controlEvent = MonitorSmsEvent(
                    jobId = jobId,
                    shopId = shopId,
                    shopName = prefs.connections().firstOrNull { it.shopId == shopId }?.shopName.orEmpty(),
                    adminId = profile.id,
                    adminCode = profile.adminCode,
                    adminName = profile.name,
                    recipientPhone = intent.getStringExtra(SmsSender.EXTRA_PHONE).orEmpty(),
                    recipientName = intent.getStringExtra(SmsSender.EXTRA_NAME).orEmpty(),
                    messageType = intent.getStringExtra(SmsSender.EXTRA_TYPE) ?: "SMS",
                    status = outcome.status,
                    error = outcome.error,
                    createdAt = intent.getStringExtra(SmsSender.EXTRA_CREATED).orEmpty(),
                    sentAt = if (outcome.status == "sent" || outcome.status == "delivered") resultAt else "",
                    deliveredAt = if (outcome.status == "delivered") resultAt else "",
                    resultAt = resultAt,
                    attempts = intent.getIntExtra(SmsSender.EXTRA_ATTEMPTS, 0)
                )
                prefs.enqueueMonitorEvent(controlEvent)
                ControlTelemetryWorker.enqueue(context)
            }
        } finally {
            pending.finish()
        }
    }
}

private fun smsResultMessage(resultCode: Int, delivery: Boolean): String = when (resultCode) {
    SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service was available."
    SmsManager.RESULT_ERROR_RADIO_OFF -> "The selected SIM radio is turned off."
    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> if (delivery)
        "The carrier did not confirm recipient delivery."
    else "The phone could not submit this SMS to the carrier."
    SmsManager.RESULT_ERROR_NULL_PDU -> "The carrier returned an invalid SMS status."
    SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "The carrier blocked this SMS destination."
    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "The phone's SMS sending limit was reached."
    else -> if (delivery) "Carrier delivery confirmation failed ($resultCode)." else "SMS submission failed ($resultCode)."
}

private fun scheduleReportSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<QueueWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "connectx-report-sync",
        ExistingWorkPolicy.KEEP,
        request
    )
}

package com.connectx.gateway.sms

import android.content.Context
import com.connectx.gateway.data.Api
import com.connectx.gateway.data.Connection
import com.connectx.gateway.data.Prefs
import com.connectx.gateway.data.SmsReport
import com.connectx.gateway.data.SmsJob
import com.connectx.gateway.data.MonitorSmsEvent
import java.time.Instant

internal data class PollOutcome(
    val onlineShops: Int = 0,
    val networkFailures: Int = 0,
    val jobsStarted: Int = 0,
    val reportsFlushed: Int = 0,
    val pollIntervalSeconds: Long = 30L
)

object QueueProcessor {
    @Synchronized
    internal fun drain(context: Context): PollOutcome {
        val prefs = Prefs(context)
        ControlTelemetryWorker.enqueue(context)
        if (!prefs.gatewayEnabled) return PollOutcome()
        val connections = prefs.connections().filter { it.setupComplete && it.deviceToken.startsWith("cxd_") }
        if (connections.isEmpty()) return PollOutcome()

        var online = 0
        var failures = 0
        var startedJobs = 0
        var flushedReports = 0
        var configuredPollSeconds = 300L
        for (connection in connections) {
            val api = Api(prefs)
            try {
                val heartbeat = api.heartbeat(connection.shopId)
                configuredPollSeconds = minOf(
                    configuredPollSeconds,
                    heartbeat.optInt("pollIntervalSeconds", 30).coerceIn(10, 300).toLong()
                )
                online++
            } catch (_: Exception) {
                failures++
                continue
            }

            val reports = prefs.pendingReports().filter { it.shopId == connection.shopId }
            for (report in reports) {
                try {
                    api.report(
                        shopId = report.shopId,
                        jobId = report.jobId,
                        claimToken = report.claimToken,
                        status = report.status,
                        error = report.error,
                        retryable = report.retryable
                    )
                    prefs.removeReport(report)
                    flushedReports++
                } catch (_: Exception) {
                    failures++
                }
            }

            val jobs = try {
                api.claim(connection.shopId)
            } catch (_: Exception) {
                failures++
                continue
            }
            for (job in jobs) {
                if (job.shopId.isNotBlank() && job.shopId != connection.shopId) continue
                if (job.id.isBlank() || job.claimToken.isBlank()) continue

                // Never silently fall back to the phone's default SIM if the
                // shop-selected subscription was removed or is temporarily absent.
                val selectedSubscriptionId = try {
                    selectedActiveSubscriptionId(
                        connection.simSubscriptionId,
                        SmsSender.sims(context).map { it.subscriptionId }
                    )
                } catch (_: SecurityException) {
                    null
                }
                if (selectedSubscriptionId == null) {
                    reportFailure(
                        context, prefs, api, connection, job.id, job.claimToken,
                        "The selected SIM is unavailable. Choose or reinsert this shop's configured SIM.",
                        retryable = true,
                        job = job
                    )
                    continue
                }

                var sendPhaseStarted = false
                try {
                    api.start(connection.shopId, job.id, job.claimToken)
                    sendPhaseStarted = true
                    sendMonitorEvent(context, prefs, connection, job, "processing")
                    SmsSender.send(
                        context = context,
                        subscriptionId = selectedSubscriptionId,
                        shopId = connection.shopId,
                        jobId = job.id,
                        phone = job.phone,
                        message = job.message,
                        claimToken = job.claimToken,
                        recipientName = job.recipientName,
                        messageType = job.eventType,
                        createdAt = job.createdAt,
                        attempts = job.attempts
                    )
                    startedJobs++
                } catch (error: Exception) {
                    reportFailure(
                        context, prefs, api, connection, job.id, job.claimToken,
                        error.message ?: "Android could not start this SMS.",
                        retryable = !sendPhaseStarted,
                        status = if (sendPhaseStarted) "unknown" else "failed",
                        job = job
                    )
                }
            }
        }
        return PollOutcome(
            onlineShops = online,
            networkFailures = failures,
            jobsStarted = startedJobs,
            reportsFlushed = flushedReports,
            pollIntervalSeconds = if (online > 0) configuredPollSeconds else 30L
        )
    }

    private fun sendMonitorEvent(context: Context, prefs: Prefs, connection: Connection, job: SmsJob, status: String, error: String? = null) {
        val now = Instant.now().toString()
        val event = MonitorSmsEvent(
            jobId = job.id,
            shopId = connection.shopId,
            shopName = connection.shopName,
            adminId = connection.adminId,
            adminCode = connection.adminCode,
            adminName = connection.adminName,
            recipientPhone = job.phone,
            recipientName = job.recipientName,
            messageType = job.eventType,
            status = status,
            error = error,
            createdAt = job.createdAt,
            sentAt = if (status == "sent") now else "",
            resultAt = now,
            attempts = job.attempts
        )
        prefs.enqueueMonitorEvent(event)
        ControlTelemetryWorker.enqueue(context)
    }

    private fun reportFailure(
        context: Context,
        prefs: Prefs,
        api: Api,
        connection: Connection,
        jobId: String,
        claimToken: String,
        error: String,
        retryable: Boolean,
        status: String = "failed",
        job: SmsJob? = null
    ) {
        val report = SmsReport(
            shopId = connection.shopId,
            jobId = jobId,
            claimToken = claimToken,
            status = status,
            error = error,
            retryable = retryable
        )
        prefs.enqueueReport(report)
        try {
            api.report(
                shopId = report.shopId,
                jobId = report.jobId,
                claimToken = report.claimToken,
                status = report.status,
                error = report.error,
                retryable = report.retryable
            )
            prefs.removeReport(report)
        } catch (_: Exception) {
            // EMS retains the claimed job; the durable outbox is retried on the
            // next connected poll. The claim will not be sent a second time.
        }
        if (job != null) sendMonitorEvent(context, prefs, connection, job, status, error)
    }
}

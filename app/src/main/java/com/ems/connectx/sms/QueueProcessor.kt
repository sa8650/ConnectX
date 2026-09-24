package com.ems.connectx.sms

import android.content.Context
import com.ems.connectx.data.Api
import com.ems.connectx.data.Prefs

object QueueProcessor {
    @Synchronized
    fun drain(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.gatewayEnabled) return
        val api = Api(prefs)
        for (conn in prefs.connections().filter { it.setupComplete && it.deviceToken.isNotBlank() }) {
            runCatching { api.heartbeat(conn.shopId) }
            val jobs = runCatching { api.claim(conn.shopId) }.getOrDefault(emptyList())
            for (job in jobs) {
                if (job.shopId != conn.shopId) continue
                if (prefs.claimed(job.id)) continue
                if (prefs.isCancelled(job.id)) continue
                prefs.markClaimed(job.id)
                try {
                    SmsSender.send(
                        context,
                        conn.simSubscriptionId,
                        conn.shopId,
                        job.id,
                        job.phone,
                        job.message
                    )
                } catch (e: Exception) {
                    runCatching { api.report(conn.shopId, job.id, false, e.message) }
                }
            }
        }
    }
}

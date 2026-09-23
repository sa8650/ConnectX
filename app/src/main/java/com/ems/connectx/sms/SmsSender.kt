package com.ems.connectx.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

object SmsSender {
    const val ACTION_SENT = "com.ems.connectx.SMS_SENT"
    const val EXTRA_JOB = "jobId"
    const val EXTRA_SHOP = "shopId"
    const val EXTRA_PART = "part"

    fun sims(context: Context): List<SubscriptionInfo> {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun managerFor(context: Context, subscriptionId: Int): SmsManager {
        val def = context.getSystemService(SmsManager::class.java)
        return if (subscriptionId > 0) {
            if (Build.VERSION.SDK_INT >= 31) def.createForSubscriptionId(subscriptionId)
            else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        } else def
    }

    fun send(
        context: Context,
        subscriptionId: Int,
        shopId: String,
        jobId: String,
        phone: String,
        message: String
    ) {
        val sm = managerFor(context, subscriptionId)
        val parts = sm.divideMessage(message)
        val sent = ArrayList<PendingIntent>(parts.size)
        for (i in parts.indices) {
            val intent = Intent(ACTION_SENT).setPackage(context.packageName)
                .putExtra(EXTRA_JOB, jobId)
                .putExtra(EXTRA_SHOP, shopId)
                .putExtra(EXTRA_PART, i)
                .putExtra("parts", parts.size)
            sent.add(
                PendingIntent.getBroadcast(
                    context,
                    (jobId + i).hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )
        }
        if (parts.size == 1) {
            sm.sendTextMessage(phone, null, message, sent[0], null)
        } else {
            sm.sendMultipartTextMessage(phone, null, parts, sent, null)
        }
    }
}

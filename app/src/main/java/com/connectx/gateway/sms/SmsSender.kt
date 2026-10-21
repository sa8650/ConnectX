package com.connectx.gateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

object SmsSender {
    const val ACTION_SENT = "com.connectx.gateway.SMS_SENT"
    const val ACTION_DELIVERED = "com.connectx.gateway.SMS_DELIVERED"
    const val EXTRA_JOB = "jobId"
    const val EXTRA_SHOP = "shopId"
    const val EXTRA_CLAIM = "claimToken"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"
    const val EXTRA_PHONE = "recipientPhone"
    const val EXTRA_NAME = "recipientName"
    const val EXTRA_TYPE = "messageType"
    const val EXTRA_CREATED = "createdAt"
    const val EXTRA_ATTEMPTS = "attempts"

    fun sims(context: Context): List<SubscriptionInfo> {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            manager.activeSubscriptionInfoList ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun managerFor(context: Context, subscriptionId: Int): SmsManager {
        if (subscriptionId <= 0) throw IllegalStateException("Select an active SIM before sending SMS.")
        val defaultManager = context.getSystemService(SmsManager::class.java)
            ?: throw IllegalStateException("This device does not provide an SMS service.")
        return if (Build.VERSION.SDK_INT >= 31) {
            defaultManager.createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    fun send(
        context: Context,
        subscriptionId: Int,
        shopId: String,
        jobId: String,
        phone: String,
        message: String,
        claimToken: String = "",
        recipientName: String = "",
        messageType: String = "SMS",
        createdAt: String = "",
        attempts: Int = 0
    ) {
        require(phone.isNotBlank()) { "SMS recipient phone number is missing." }
        require(message.isNotBlank()) { "SMS message is empty." }
        val smsManager = managerFor(context, subscriptionId)
        val parts = smsManager.divideMessage(message).ifEmpty { arrayListOf(message) }
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveryIntents = if (claimToken.isNotBlank()) ArrayList<PendingIntent>(parts.size) else null

        for (index in parts.indices) {
            val sentIntent = callbackIntent(
                context = context,
                action = ACTION_SENT,
                jobId = jobId,
                shopId = shopId,
                claimToken = claimToken,
                part = index,
                partCount = parts.size,
                suffix = "sent",
                recipientPhone = phone,
                recipientName = recipientName,
                messageType = messageType,
                createdAt = createdAt,
                attempts = attempts
            )
            sentIntents.add(sentIntent)
            deliveryIntents?.add(
                callbackIntent(
                    context = context,
                    action = ACTION_DELIVERED,
                    jobId = jobId,
                    shopId = shopId,
                    claimToken = claimToken,
                    part = index,
                    partCount = parts.size,
                    suffix = "delivery",
                    recipientPhone = phone,
                    recipientName = recipientName,
                    messageType = messageType,
                    createdAt = createdAt,
                    attempts = attempts
                )
            )
        }

        if (parts.size == 1) {
            smsManager.sendTextMessage(phone, null, message, sentIntents[0], deliveryIntents?.get(0))
        } else {
            smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, deliveryIntents)
        }
    }

    private fun callbackIntent(
        context: Context,
        action: String,
        jobId: String,
        shopId: String,
        claimToken: String,
        part: Int,
        partCount: Int,
        suffix: String,
        recipientPhone: String,
        recipientName: String,
        messageType: String,
        createdAt: String,
        attempts: Int
    ): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
            .putExtra(EXTRA_JOB, jobId)
            .putExtra(EXTRA_SHOP, shopId)
            .putExtra(EXTRA_CLAIM, claimToken)
            .putExtra(EXTRA_PART, part)
            .putExtra(EXTRA_PARTS, partCount)
            .putExtra(EXTRA_PHONE, recipientPhone)
            .putExtra(EXTRA_NAME, recipientName)
            .putExtra(EXTRA_TYPE, messageType)
            .putExtra(EXTRA_CREATED, createdAt)
            .putExtra(EXTRA_ATTEMPTS, attempts)
        val requestCode = "$jobId:$claimToken:$suffix:$part".hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

package com.ems.connectx.data

data class Shop(
    val id: String,
    val name: String,
    val address: String = "",
    val phone: String = "",
    val shopCode: String = "",
    val connected: Boolean = false
)

data class Connection(
    val shopId: String,
    val shopName: String,
    val shopAddress: String,
    val adminId: String,
    val adminEmail: String,
    val adminName: String,
    val deviceId: String,
    val devicePublicId: String,
    val deviceToken: String,
    val simSubscriptionId: Int,
    val simCarrier: String,
    val phoneNumber: String,
    val setupComplete: Boolean
)

data class SmsJob(
    val id: String,
    val shopId: String,
    val phone: String,
    val message: String,
    val eventType: String,
    val recipientName: String,
    val createdAt: String
)

data class ActivityItem(
    val id: String,
    val phone: String,
    val name: String,
    val type: String,
    val status: String,
    val error: String?,
    val message: String,
    val createdAt: String,
    val sentAt: String = ""
)

data class HomeStats(
    val sent: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val lastActivity: String? = null,
    val shopName: String = "",
    val shopAddress: String = "",
    val adminName: String = "",
    val adminEmail: String = "",
    val deviceOnline: Boolean = true
)

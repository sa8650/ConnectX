package com.connectx.gateway.data

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
    val adminCode: String = "",
    val adminPhone: String = "",
    val adminAddress: String = "",
    val deviceId: String,
    val devicePublicId: String,
    val deviceToken: String,
    val simSubscriptionId: Int,
    val simCarrier: String,
    val phoneNumber: String,
    val setupComplete: Boolean
)

data class AdminProfile(
    val id: String = "",
    val adminCode: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val active: Boolean = true,
    val createdAt: String = ""
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
    val rawType: String = "",
    val status: String,
    val error: String?,
    val message: String,
    val createdAt: String,
    val sentAt: String = "",
    val invoiceId: String? = null
)

// Email is sent by the client apps (EMS, CareOS, ...); the Android device reads
// this workspace's outgoing history from ConnectX only. No mailbox credentials
// or email-sending permission live here.
data class EmailItem(
    val id: String,
    val subject: String,
    val fromEmail: String,
    val toEmails: List<String>,
    val ccEmails: List<String>,
    val bccEmails: List<String> = emptyList(),
    val recipientType: String = "",
    val status: String,
    val error: String? = null,
    val createdAt: String,
    val sentAt: String = "",
    val customBody: String = "",
    val bodyHtml: String = ""
)

data class EmailPage(
    val items: List<EmailItem>,
    val page: Int,
    val snapshot: String,
    val hasMore: Boolean
)

data class EmailStats(
    val sent: Int,
    val failed: Int,
    val pending: Int,
    val latest: EmailItem?
)

data class HomeStats(
    val sent: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val lastActivity: String? = null,
    val shopName: String = "",
    val shopAddress: String = "",
    val shopPhone: String = "",
    val adminName: String = "",
    val adminEmail: String = "",
    val adminCode: String = "",
    val adminPhone: String = "",
    val adminAddress: String = "",
    val adminCreatedAt: String = "",
    val adminActive: Boolean = true,
    val deviceOnline: Boolean = true,
    val devicePublicId: String = "",
    val simCarrier: String = "",
    val simPhone: String = ""
)

data class AppUpdateInfo(
    val title: String = "ConnectX: Central Communication Gateway powered by Dexter Studio",
    val description: String = "",
    val latestVersion: String,
    val versionCode: Int,
    val mandatory: Boolean,
    val downloadUrl: String,
    val apkFilename: String = "ConnectX.apk",
    val apkSizeBytes: Long = 0L,
    val releaseNotes: String = "",
    val updatedAt: String = ""
)

/**
 * Clean helper function to format message types for SMS card titles.
 * Replaces old "null" title bug and formats into:
 * - Sales Invoice Confirmation
 * - Due Invoice Reminder
 * - Exchange Invoice Confirmation
 * - Return Invoice Confirmation
 * - Payment Confirmation
 * - Custom Message
 */
fun formatMessageType(
    type: String?,
    eventType: String? = null,
    messageBody: String? = null
): String {
    val clean = listOfNotNull(type, eventType)
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    if (clean != null) {
        val normalized = clean.uppercase()
        when {
            normalized == "SALE" ||
            normalized.contains("SALES INVOICE") ||
            normalized.contains("SALE INVOICE") ||
            normalized == "SALE_INVOICE" ||
            normalized == "SALES" -> return "Sales Invoice Confirmation"

            normalized == "DUE_REMINDER" ||
            normalized == "DUE" ||
            normalized.contains("DUE REMINDER") ||
            normalized.contains("DUE INVOICE") -> return "Due Invoice Reminder"

            normalized == "EXCHANGE" ||
            normalized.contains("EXCHANGE INVOICE") ||
            normalized.contains("EXCHANGE CONFIRMATION") ||
            normalized == "EXCHANGE_INVOICE" -> return "Exchange Invoice Confirmation"

            normalized == "RETURN" ||
            normalized.contains("RETURN INVOICE") ||
            normalized.contains("RETURN CONFIRMATION") ||
            normalized == "RETURN_INVOICE" ||
            normalized == "REFUND" ||
            normalized.contains("REFUND CONFIRMATION") -> return "Return Invoice Confirmation"

            normalized == "PAYMENT" ||
            normalized.contains("PAYMENT") ||
            normalized == "PAYMENT_CONFIRMATION" -> return "Payment Confirmation"

            normalized == "TEST" ||
            normalized.contains("TEST MESSAGE") ||
            normalized.contains("GATEWAY TEST") -> return "Gateway Test Message"

            clean.equals("Custom Message", ignoreCase = true) ||
            clean.equals("custom", ignoreCase = true) ||
            clean.equals("manual", ignoreCase = true) -> return "Custom Message"

            // If it's a descriptive custom string, capitalize appropriately
            clean.isNotBlank() && clean.length > 3 && !clean.equals("SMS", ignoreCase = true) -> return clean
        }
    }

    // Inspect message text if type is missing or generic
    if (!messageBody.isNullOrBlank()) {
        val lower = messageBody.lowercase()
        when {
            lower.contains("thanks for your purchase") || lower.contains("purchase at") -> return "Sales Invoice Confirmation"
            lower.contains("due reminder") || lower.contains("outstanding due") -> return "Due Invoice Reminder"
            lower.contains("your exchange") || lower.contains("exchange") -> return "Exchange Invoice Confirmation"
            lower.contains("your return") || lower.contains("refund") -> return "Return Invoice Confirmation"
            lower.contains("received your payment") || lower.contains("payment of") -> return "Payment Confirmation"
            lower.contains("connectx test") || lower.contains("gateway test") -> return "Gateway Test Message"
        }
    }

    return "Custom Message"
}

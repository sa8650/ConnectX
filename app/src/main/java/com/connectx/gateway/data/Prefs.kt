package com.connectx.gateway.data

import com.connectx.gateway.BuildConfig
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val app = context.applicationContext
    private val store: SharedPreferences = try {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app,
            "connectx.secure",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        app.getSharedPreferences("connectx.fallback", Context.MODE_PRIVATE)
    }

    /** EMS origin is compiled into the signed app build; there is no in-app endpoint setting. */
    val baseUrl: String get() = BuildConfig.EMS_BASE_URL.trimEnd('/')

    var systemKey: String
        get() = store.getString("systemKey", "") ?: ""
        set(v) = store.edit().putString("systemKey", v).apply()

    var systemName: String
        get() = store.getString("systemName", "") ?: ""
        set(v) = store.edit().putString("systemName", v).apply()

    var lastNotifiedUpdateCode: Int
        get() = store.getInt("lastNotifiedUpdateCode", 0)
        set(v) = store.edit().putInt("lastNotifiedUpdateCode", v).apply()

    var adminToken: String
        get() = store.getString("adminToken", "") ?: ""
        set(v) = store.edit().putString("adminToken", v).apply()

    var adminId: String
        get() = store.getString("adminId", "") ?: ""
        set(v) = store.edit().putString("adminId", v).apply()

    var adminEmail: String
        get() = store.getString("adminEmail", "") ?: ""
        set(v) = store.edit().putString("adminEmail", v).apply()

    var adminName: String
        get() = store.getString("adminName", "") ?: ""
        set(v) = store.edit().putString("adminName", v).apply()

    var adminCode: String
        get() = store.getString("adminCode", "") ?: ""
        set(v) = store.edit().putString("adminCode", v).apply()

    var adminPhone: String
        get() = store.getString("adminPhone", "") ?: ""
        set(v) = store.edit().putString("adminPhone", v).apply()

    var adminAddress: String
        get() = store.getString("adminAddress", "") ?: ""
        set(v) = store.edit().putString("adminAddress", v).apply()

    var adminCreatedAt: String
        get() = store.getString("adminCreatedAt", "") ?: ""
        set(v) = store.edit().putString("adminCreatedAt", v).apply()

    var adminActive: Boolean
        get() = store.getBoolean("adminActive", true)
        set(v) = store.edit().putBoolean("adminActive", v).apply()

    var activeShopId: String
        get() = store.getString("activeShopId", "") ?: ""
        set(v) = store.edit().putString("activeShopId", v).apply()

    var gatewayEnabled: Boolean
        get() = store.getBoolean("gatewayEnabled", true)
        set(v) = store.edit().putBoolean("gatewayEnabled", v).apply()

    var seenGetStarted: Boolean
        get() = store.getBoolean("seenGetStarted", false)
        set(v) = store.edit().putBoolean("seenGetStarted", v).apply()

    var signedIn: Boolean
        get() = store.getBoolean("signedIn", connections().any { it.setupComplete })
        set(v) = store.edit().putBoolean("signedIn", v).apply()

    fun getAdminProfile(): AdminProfile {
        return AdminProfile(
            id = adminId,
            adminCode = adminCode,
            name = adminName,
            email = adminEmail,
            phone = adminPhone,
            address = adminAddress,
            active = adminActive,
            createdAt = adminCreatedAt
        )
    }

    fun saveAdminProfile(profile: AdminProfile) {
        if (profile.id.isNotBlank()) adminId = profile.id
        if (profile.adminCode.isNotBlank()) adminCode = profile.adminCode
        if (profile.name.isNotBlank()) adminName = profile.name
        if (profile.email.isNotBlank()) adminEmail = profile.email
        if (profile.phone.isNotBlank()) adminPhone = profile.phone
        if (profile.address.isNotBlank()) adminAddress = profile.address
        if (profile.createdAt.isNotBlank()) adminCreatedAt = profile.createdAt
        adminActive = profile.active
    }

    fun connections(): List<Connection> {
        val raw = store.getString("connections", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Connection(
                        shopId = o.getString("shopId"),
                        shopName = o.optString("shopName"),
                        shopAddress = o.optString("shopAddress"),
                        systemKey = o.optString("systemKey"),
                        systemName = o.optString("systemName"),
                        adminId = o.optString("adminId"),
                        adminEmail = o.optString("adminEmail"),
                        adminName = o.optString("adminName"),
                        adminCode = o.optString("adminCode"),
                        adminPhone = o.optString("adminPhone"),
                        adminAddress = o.optString("adminAddress"),
                        deviceId = o.optString("deviceId"),
                        devicePublicId = o.optString("devicePublicId"),
                        deviceToken = o.optString("deviceToken"),
                        simSubscriptionId = o.optInt("simSubscriptionId", -1),
                        simCarrier = o.optString("simCarrier"),
                        phoneNumber = o.optString("phoneNumber"),
                        setupComplete = o.optBoolean("setupComplete", false),
                        deviceName = o.optString("deviceName"),
                        controlDeviceToken = o.optString("controlDeviceToken")
                    )
                )
            }
        }
    }

    fun saveConnection(c: Connection) {
        val list = connections().filterNot { it.shopId == c.shopId } + c
        writeConnections(list)
        if (activeShopId.isBlank()) activeShopId = c.shopId
    }

    fun removeConnection(shopId: String) {
        writeConnections(connections().filterNot { it.shopId == shopId })
        if (activeShopId == shopId) activeShopId = connections().firstOrNull()?.shopId ?: ""
    }

    fun updateConnection(shopId: String, block: (Connection) -> Connection) {
        writeConnections(connections().map { if (it.shopId == shopId) block(it) else it })
    }

    fun active(): Connection? = connections().firstOrNull { it.shopId == activeShopId }
        ?: connections().firstOrNull()

    private fun writeConnections(list: List<Connection>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("shopId", c.shopId)
                put("shopName", c.shopName)
                put("shopAddress", c.shopAddress)
                put("systemKey", c.systemKey)
                put("systemName", c.systemName)
                put("adminId", c.adminId)
                put("adminEmail", c.adminEmail)
                put("adminName", c.adminName)
                put("adminCode", c.adminCode)
                put("adminPhone", c.adminPhone)
                put("adminAddress", c.adminAddress)
                put("deviceId", c.deviceId)
                put("devicePublicId", c.devicePublicId)
                put("deviceToken", c.deviceToken)
                put("simSubscriptionId", c.simSubscriptionId)
                put("simCarrier", c.simCarrier)
                put("phoneNumber", c.phoneNumber)
                put("setupComplete", c.setupComplete)
                put("deviceName", c.deviceName)
                put("controlDeviceToken", c.controlDeviceToken)
            })
        }
        store.edit().putString("connections", arr.toString()).apply()
    }

    private val reportLock = Companion.reportLock

    fun pendingReports(): List<SmsReport> = synchronized(reportLock) {
        val raw = store.getString("smsReportOutbox", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        buildList {
            for (i in 0 until arr.length()) {
                val item = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
                val report = runCatching {
                    SmsReport(
                        shopId = item.getString("shopId"),
                        jobId = item.getString("jobId"),
                        claimToken = item.getString("claimToken"),
                        status = item.getString("status"),
                        error = item.optString("error").takeIf { it.isNotBlank() },
                        retryable = item.optBoolean("retryable", false)
                    )
                }.getOrNull() ?: continue
                add(report)
            }
        }
    }

    fun enqueueReport(report: SmsReport) = synchronized(reportLock) {
        val existing = pendingReports()
        if (existing.any { it.key == report.key }) return@synchronized
        val arr = JSONArray()
        (existing + report).takeLast(200).forEach { item ->
            arr.put(JSONObject().apply {
                put("shopId", item.shopId)
                put("jobId", item.jobId)
                put("claimToken", item.claimToken)
                put("status", item.status)
                put("error", item.error ?: JSONObject.NULL)
                put("retryable", item.retryable)
            })
        }
        store.edit().putString("smsReportOutbox", arr.toString()).commit()
    }

    fun removeReport(report: SmsReport) = synchronized(reportLock) {
        val remaining = pendingReports().filterNot { it.key == report.key }
        val arr = JSONArray()
        remaining.forEach { item ->
            arr.put(JSONObject().apply {
                put("shopId", item.shopId)
                put("jobId", item.jobId)
                put("claimToken", item.claimToken)
                put("status", item.status)
                put("error", item.error ?: JSONObject.NULL)
                put("retryable", item.retryable)
            })
        }
        store.edit().putString("smsReportOutbox", arr.toString()).commit()
    }

    private val monitorLock = Companion.monitorLock

    fun pendingMonitorEvents(): List<MonitorSmsEvent> = synchronized(monitorLock) {
        val raw = store.getString("controlMonitorOutbox", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        buildList {
            for (i in 0 until arr.length()) {
                val item = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
                val event = runCatching {
                    MonitorSmsEvent(
                        jobId = item.getString("jobId"), shopId = item.getString("shopId"),
                        shopName = item.optString("shopName"), adminId = item.optString("adminId"),
                        adminCode = item.optString("adminCode"), adminName = item.optString("adminName"),
                        recipientPhone = item.optString("recipientPhone"), recipientName = item.optString("recipientName"),
                        messageType = item.optString("messageType"), status = item.getString("status"),
                        error = item.optString("error").takeIf { it.isNotBlank() && it != "null" },
                        createdAt = item.optString("createdAt"), sentAt = item.optString("sentAt"),
                        deliveredAt = item.optString("deliveredAt"), resultAt = item.optString("resultAt"),
                        attempts = item.optInt("attempts", 0)
                    )
                }.getOrNull() ?: continue
                add(event)
            }
        }
    }

    fun enqueueMonitorEvent(event: MonitorSmsEvent) = synchronized(monitorLock) {
        val events = pendingMonitorEvents().filterNot { it.jobId == event.jobId } + event
        val arr = JSONArray()
        events.takeLast(500).forEach { item ->
            arr.put(JSONObject().apply {
                put("jobId", item.jobId); put("shopId", item.shopId); put("shopName", item.shopName)
                put("adminId", item.adminId); put("adminCode", item.adminCode); put("adminName", item.adminName)
                put("recipientPhone", item.recipientPhone); put("recipientName", item.recipientName)
                put("messageType", item.messageType); put("status", item.status); put("error", item.error ?: JSONObject.NULL)
                put("createdAt", item.createdAt); put("sentAt", item.sentAt); put("deliveredAt", item.deliveredAt)
                put("resultAt", item.resultAt); put("attempts", item.attempts)
            })
        }
        store.edit().putString("controlMonitorOutbox", arr.toString()).commit()
    }

    fun removeMonitorEvent(event: MonitorSmsEvent) = synchronized(monitorLock) {
        val events = pendingMonitorEvents().filterNot { it.jobId == event.jobId && it.status == event.status && it.resultAt == event.resultAt }
        val arr = JSONArray()
        events.forEach { item ->
            arr.put(JSONObject().apply {
                put("jobId", item.jobId); put("shopId", item.shopId); put("shopName", item.shopName)
                put("adminId", item.adminId); put("adminCode", item.adminCode); put("adminName", item.adminName)
                put("recipientPhone", item.recipientPhone); put("recipientName", item.recipientName)
                put("messageType", item.messageType); put("status", item.status); put("error", item.error ?: JSONObject.NULL)
                put("createdAt", item.createdAt); put("sentAt", item.sentAt); put("deliveredAt", item.deliveredAt)
                put("resultAt", item.resultAt); put("attempts", item.attempts)
            })
        }
        store.edit().putString("controlMonitorOutbox", arr.toString()).commit()
    }

    fun clearSession() {
        store.edit().clear().commit()
        seenGetStarted = true
    }

    companion object {
        private val reportLock = Any()
        private val monitorLock = Any()
    }
}

package com.connectx.gateway.data

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

    /** The ConnectX Control origin. The app ships with the official address
     * built in; a custom one is stored only after the operator enters it on
     * the connect screen (shown when the built-in address is unreachable). */
    /** Always the compiled Connect App origin. Not shown in the UI. */
    var baseUrl: String
        get() = ConnectEndpoint.ORIGIN
        set(_) { /* endpoint is hidden in the app backend */ }

    var connectApplicationId: String
        get() = store.getString("cxAppId", "") ?: ""
        set(v) = store.edit().putString("cxAppId", v).apply()
    var connectDeviceName: String
        get() = store.getString("cxDeviceName", "") ?: ""
        set(v) = store.edit().putString("cxDeviceName", v).apply()
    var connectRequestToken: String
        get() = store.getString("cxReq", "") ?: ""
        set(v) = store.edit().putString("cxReq", v).apply()
    var connectPairingCode: String
        get() = store.getString("cxPair", "") ?: ""
        set(v) = store.edit().putString("cxPair", v).apply()
    var connectConnectionId: String
        get() = store.getString("cxConn", "") ?: ""
        set(v) = store.edit().putString("cxConn", v).apply()
    var connectSharedSecret: String
        get() = store.getString("cxSecret", "") ?: ""
        set(v) = store.edit().putString("cxSecret", v).apply()
    var connectRemoteAppId: String
        get() = store.getString("cxRemote", "") ?: ""
        set(v) = store.edit().putString("cxRemote", v).apply()
    var connectStatus: String
        get() = store.getString("cxStatus", "") ?: ""
        set(v) = store.edit().putString("cxStatus", v).apply()

    val connectActive: Boolean
        get() = connectStatus == "ACTIVE" && connectSharedSecret.isNotBlank() && connectConnectionId.isNotBlank()

    fun clearConnect() {
        connectStatus = ""
        connectSharedSecret = ""
        connectConnectionId = ""
        connectRequestToken = ""
        connectPairingCode = ""
        connectRemoteAppId = ""
        writeConnections(emptyList())
        signedIn = false
        activeShopId = ""
    }

    /** True when no custom URL was saved - the built-in official site is used. */
    val usingBuiltInUrl: Boolean
        get() = (store.getString("baseUrl", "") ?: "").isBlank()

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
                        setupComplete = o.optBoolean("setupComplete", false)
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
            })
        }
        store.edit().putString("connections", arr.toString()).apply()
    }

    fun claimed(jobId: String): Boolean = store.getBoolean("job:$jobId", false)
    fun markClaimed(jobId: String) = store.edit().putBoolean("job:$jobId", true).apply()

    fun isCancelled(jobId: String): Boolean = store.getBoolean("cancel:$jobId", false)
    fun markCancelled(jobId: String) = store.edit().putBoolean("cancel:$jobId", true).apply()

    fun clearSession() {
        // Old builds may have saved an incomplete address. Do not let an
        // invalid legacy URL prevent logout or reappear on the next login.
        // A valid custom URL survives logout; an invalid one falls back to
        // the built-in official ConnectX Control address.
        val url = runCatching { GatewayUrl.normalize(store.getString("baseUrl", "") ?: "") }.getOrNull().orEmpty()
        store.edit().clear().apply()
        if (url.isNotBlank()) baseUrl = url
        seenGetStarted = true
    }
}

package com.ems.connectx.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun JSONObject.optStringOrNull(name: String): String? {
    if (isNull(name)) return null
    val v = optString(name, "").trim()
    return if (v.isEmpty() || v.equals("null", ignoreCase = true)) null else v
}

class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String {
        val base = prefs.baseUrl.ifBlank { throw IllegalStateException("Enter your EMS website URL first.") }
        return "$base/api/$path"
    }

    private fun call(path: String, method: String = "GET", body: JSONObject? = null, token: String?): JSONObject {
        val builder = Request.Builder().url(url(path))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val reqBody = body?.toString()?.toRequestBody(jsonType)
        when (method) {
            "POST" -> builder.post(reqBody ?: "{}".toRequestBody(jsonType))
            "PATCH" -> builder.patch(reqBody ?: "{}".toRequestBody(jsonType))
            "DELETE" -> {
                if (reqBody != null) builder.delete(reqBody)
                else builder.delete()
            }
            else -> builder.get()
        }
        http.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val obj = try {
                if (text.startsWith("[")) JSONObject().put("items", JSONArray(text))
                else JSONObject(text.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject().put("raw", text)
            }
            if (!res.isSuccessful) {
                throw IllegalStateException(obj.optString("error", "Request failed (${res.code})"))
            }
            return obj
        }
    }

    fun adminLogin(email: String, password: String): JSONObject {
        val r = call(
            "auth/admin/login", "POST",
            JSONObject().put("email", email).put("password", password),
            token = null
        )
        prefs.adminToken = r.optString("token")
        val user = r.optJSONObject("user")
        if (user != null) {
            val adminCode = user.optStringOrNull("admin_code") ?: user.optStringOrNull("adminCode") ?: ""
            val phone = user.optStringOrNull("phone") ?: ""
            val address = user.optStringOrNull("address") ?: ""
            val name = user.optStringOrNull("name") ?: ""
            val id = user.optStringOrNull("id") ?: ""
            val createdAt = user.optStringOrNull("created_at") ?: ""
            val active = user.optBoolean("active", true)

            val p = AdminProfile(
                id = id,
                adminCode = adminCode,
                name = name,
                email = user.optStringOrNull("email") ?: email,
                phone = phone,
                address = address,
                active = active,
                createdAt = createdAt
            )
            prefs.saveAdminProfile(p)
        }
        return r
    }

    fun fetchAdminProfile(shopId: String? = null): AdminProfile {
        // Try device token gateway 'me' route first
        try {
            val token = runCatching { deviceToken(shopId) }.getOrNull()
            if (!token.isNullOrBlank()) {
                val r = call("connectx/gateway/me", token = token)
                val adminObj = r.optJSONObject("administrator")
                if (adminObj != null) {
                    val p = AdminProfile(
                        id = adminObj.optStringOrNull("id") ?: prefs.adminId,
                        adminCode = adminObj.optStringOrNull("admin_code") ?: adminObj.optStringOrNull("adminCode") ?: prefs.adminCode,
                        name = adminObj.optStringOrNull("name") ?: prefs.adminName,
                        email = adminObj.optStringOrNull("email") ?: prefs.adminEmail,
                        phone = adminObj.optStringOrNull("phone") ?: prefs.adminPhone,
                        address = adminObj.optStringOrNull("address") ?: prefs.adminAddress,
                        active = adminObj.optBoolean("active", true),
                        createdAt = adminObj.optStringOrNull("created_at") ?: prefs.adminCreatedAt
                    )
                    prefs.saveAdminProfile(p)
                    return p
                }
            }
        } catch (_: Exception) {}

        // Fallback: Admin profile endpoint with admin token
        try {
            if (prefs.adminToken.isNotBlank()) {
                val r = call("admin/profile", token = prefs.adminToken)
                val p = AdminProfile(
                    id = r.optStringOrNull("id") ?: prefs.adminId,
                    adminCode = r.optStringOrNull("admin_code") ?: r.optStringOrNull("adminCode") ?: prefs.adminCode,
                    name = r.optStringOrNull("name") ?: prefs.adminName,
                    email = r.optStringOrNull("email") ?: prefs.adminEmail,
                    phone = r.optStringOrNull("phone") ?: prefs.adminPhone,
                    address = r.optStringOrNull("address") ?: prefs.adminAddress,
                    active = r.optBoolean("active", true),
                    createdAt = r.optStringOrNull("created_at") ?: prefs.adminCreatedAt
                )
                prefs.saveAdminProfile(p)
                return p
            }
        } catch (_: Exception) {}

        return prefs.getAdminProfile()
    }

    fun shops(): List<Shop> {
        val r = call("connectx/gateway/shops", token = prefs.adminToken)
        val admin = r.optJSONObject("administrator")
        if (admin != null) {
            val adminCode = admin.optStringOrNull("admin_code") ?: admin.optStringOrNull("adminCode") ?: prefs.adminCode
            val phone = admin.optStringOrNull("phone") ?: prefs.adminPhone
            val address = admin.optStringOrNull("address") ?: prefs.adminAddress
            val name = admin.optStringOrNull("name") ?: prefs.adminName
            val id = admin.optStringOrNull("id") ?: prefs.adminId
            val email = admin.optStringOrNull("email") ?: prefs.adminEmail
            val p = AdminProfile(
                id = id,
                adminCode = adminCode,
                name = name,
                email = email,
                phone = phone,
                address = address,
                active = admin.optBoolean("active", true),
                createdAt = admin.optStringOrNull("created_at") ?: prefs.adminCreatedAt
            )
            prefs.saveAdminProfile(p)
        }
        val arr = r.optJSONArray("shops") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Shop(
                        id = o.getString("id"),
                        name = o.optStringOrNull("name") ?: "Shop",
                        address = o.optStringOrNull("address") ?: "",
                        phone = o.optStringOrNull("phone") ?: "",
                        shopCode = o.optStringOrNull("shop_code") ?: "",
                        connected = o.optBoolean("connected", false)
                    )
                )
            }
        }
    }

    fun registerDevice(
        shopId: String,
        deviceName: String,
        androidVersion: String,
        simId: Int,
        carrier: String,
        phone: String
    ): Connection {
        val r = call(
            "connectx/gateway/register", "POST",
            JSONObject()
                .put("storeId", shopId)
                .put("deviceName", deviceName)
                .put("androidVersion", androidVersion)
                .put("simSubscriptionId", simId)
                .put("simCarrier", carrier)
                .put("phoneNumber", phone),
            token = prefs.adminToken
        )
        val device = r.getJSONObject("device")
        val shop = r.getJSONObject("shop")
        val admin = r.optJSONObject("administrator")

        val adminCode = admin?.optStringOrNull("admin_code") ?: admin?.optStringOrNull("adminCode") ?: prefs.adminCode
        val adminPhone = admin?.optStringOrNull("phone") ?: prefs.adminPhone
        val adminAddress = admin?.optStringOrNull("address") ?: prefs.adminAddress
        val adminName = admin?.optStringOrNull("name") ?: prefs.adminName
        val adminEmail = admin?.optStringOrNull("email") ?: prefs.adminEmail
        val adminId = admin?.optStringOrNull("id") ?: prefs.adminId

        if (admin != null) {
            prefs.saveAdminProfile(
                AdminProfile(
                    id = adminId,
                    adminCode = adminCode,
                    name = adminName,
                    email = adminEmail,
                    phone = adminPhone,
                    address = adminAddress,
                    active = admin.optBoolean("active", true),
                    createdAt = admin.optStringOrNull("created_at") ?: prefs.adminCreatedAt
                )
            )
        }

        val conn = Connection(
            shopId = shop.getString("id"),
            shopName = shop.optStringOrNull("name") ?: "Shop",
            shopAddress = shop.optStringOrNull("address") ?: "",
            adminId = adminId,
            adminEmail = adminEmail,
            adminName = adminName,
            adminCode = adminCode,
            adminPhone = adminPhone,
            adminAddress = adminAddress,
            deviceId = device.getString("id"),
            devicePublicId = device.optStringOrNull("device_public_id") ?: "",
            deviceToken = r.getString("deviceToken"),
            simSubscriptionId = simId,
            simCarrier = carrier,
            phoneNumber = phone,
            setupComplete = false
        )
        prefs.saveConnection(conn)
        prefs.activeShopId = conn.shopId
        return conn
    }

    private fun deviceToken(shopId: String? = null): String {
        val id = shopId ?: prefs.activeShopId
        return prefs.connections().firstOrNull { it.shopId == id }?.deviceToken
            ?: throw IllegalStateException("This shop is not connected.")
    }

    fun heartbeat(shopId: String? = null): JSONObject =
        call("connectx/gateway/heartbeat", "POST", JSONObject(), deviceToken(shopId))

    fun claim(shopId: String, limit: Int = 8): List<SmsJob> {
        val r = call("connectx/gateway/claim", "POST", JSONObject().put("limit", limit), deviceToken(shopId))
        val arr = r.optJSONArray("jobs") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rawType = o.optStringOrNull("message_type") ?: o.optStringOrNull("event_type") ?: "Custom Message"
                val bodyText = o.optStringOrNull("message") ?: ""
                add(
                    SmsJob(
                        id = o.getString("id"),
                        shopId = o.optStringOrNull("shop_id") ?: shopId,
                        phone = o.optStringOrNull("phone_number") ?: "",
                        message = bodyText,
                        eventType = formatMessageType(rawType, o.optStringOrNull("event_type"), bodyText),
                        recipientName = o.optStringOrNull("recipient_name") ?: "Customer",
                        createdAt = o.optStringOrNull("created_at") ?: ""
                    )
                )
            }
        }
    }

    fun report(shopId: String, jobId: String, sent: Boolean, error: String? = null) {
        call(
            "connectx/gateway/report", "POST",
            JSONObject()
                .put("jobId", jobId)
                .put("status", if (sent) "sent" else "failed")
                .put("error", error ?: JSONObject.NULL),
            deviceToken(shopId)
        )
    }

    /**
     * Cancel a queued job SMS from device or admin token.
     */
    fun cancelJob(shopId: String, jobId: String): Boolean {
        prefs.markCancelled(jobId)
        return try {
            val res = call(
                "connectx/gateway/cancel", "POST",
                JSONObject().put("jobId", jobId),
                deviceToken(shopId)
            )
            res.optBoolean("ok", true)
        } catch (_: Exception) {
            try {
                if (prefs.adminToken.isNotBlank()) {
                    val r = call("connectx/sms/messages/$jobId", "DELETE", null, prefs.adminToken)
                    r.optBoolean("ok", true)
                } else {
                    report(shopId, jobId, false, "Cancelled by user")
                    true
                }
            } catch (_: Exception) {
                runCatching { report(shopId, jobId, false, "Cancelled by user") }
                true
            }
        }
    }

    fun markTest(shopId: String, ok: Boolean) {
        call("connectx/gateway/test", "POST", JSONObject().put("ok", ok).put("record", false), deviceToken(shopId))
        prefs.updateConnection(shopId) { it.copy(setupComplete = ok) }
    }

    fun updateSim(shopId: String, simId: Int, carrier: String, phone: String) {
        call(
            "connectx/gateway/sim", "PATCH",
            JSONObject().put("simSubscriptionId", simId).put("simCarrier", carrier).put("phoneNumber", phone),
            deviceToken(shopId)
        )
        prefs.updateConnection(shopId) { it.copy(simSubscriptionId = simId, simCarrier = carrier, phoneNumber = phone) }
    }

    fun stats(shopId: String): HomeStats {
        val r = call("connectx/gateway/stats", token = deviceToken(shopId))
        val shop = r.optJSONObject("shop")
        val admin = r.optJSONObject("administrator")
        val device = r.optJSONObject("device")
        val conn = prefs.connections().firstOrNull { it.shopId == shopId }

        val adminCode = admin?.optStringOrNull("admin_code") ?: admin?.optStringOrNull("adminCode") ?: prefs.adminCode
        val adminPhone = admin?.optStringOrNull("phone") ?: prefs.adminPhone
        val adminAddress = admin?.optStringOrNull("address") ?: prefs.adminAddress
        val adminName = admin?.optStringOrNull("name") ?: prefs.adminName
        val adminEmail = admin?.optStringOrNull("email") ?: prefs.adminEmail
        val adminId = admin?.optStringOrNull("id") ?: prefs.adminId
        val adminCreatedAt = admin?.optStringOrNull("created_at") ?: prefs.adminCreatedAt
        val adminActive = admin?.optBoolean("active", true) ?: prefs.adminActive

        if (admin != null) {
            val p = AdminProfile(
                id = adminId,
                adminCode = adminCode,
                name = adminName,
                email = adminEmail,
                phone = adminPhone,
                address = adminAddress,
                active = adminActive,
                createdAt = adminCreatedAt
            )
            prefs.saveAdminProfile(p)
        }

        return HomeStats(
            sent = r.optInt("sent", 0),
            failed = r.optInt("failed", 0),
            pending = r.optInt("pending", 0),
            lastActivity = r.optStringOrNull("lastActivity"),
            shopName = shop?.optStringOrNull("name") ?: conn?.shopName.orEmpty(),
            shopAddress = shop?.optStringOrNull("address") ?: conn?.shopAddress.orEmpty(),
            shopPhone = shop?.optStringOrNull("phone") ?: "",
            adminName = adminName,
            adminEmail = adminEmail,
            adminCode = adminCode,
            adminPhone = adminPhone,
            adminAddress = adminAddress,
            adminCreatedAt = adminCreatedAt,
            adminActive = adminActive,
            deviceOnline = true,
            devicePublicId = device?.optStringOrNull("device_public_id") ?: conn?.devicePublicId.orEmpty(),
            simCarrier = conn?.simCarrier.orEmpty(),
            simPhone = conn?.phoneNumber.orEmpty()
        )
    }

    fun activity(shopId: String, range: String): List<ActivityItem> {
        val r = call("connectx/gateway/activity?range=$range", token = deviceToken(shopId))
        val arr = r.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rawMsgType = o.optStringOrNull("message_type")
                val rawEventType = o.optStringOrNull("event_type")
                val body = o.optStringOrNull("message_body") ?: o.optStringOrNull("message") ?: ""
                val formattedType = formatMessageType(rawMsgType, rawEventType, body)

                add(
                    ActivityItem(
                        id = o.getString("id"),
                        phone = o.optStringOrNull("to_phone") ?: "",
                        name = o.optStringOrNull("recipient_name") ?: "Customer",
                        type = formattedType,
                        rawType = rawMsgType ?: rawEventType ?: "Custom Message",
                        status = o.optStringOrNull("status") ?: "queued",
                        error = o.optStringOrNull("error_message"),
                        message = body,
                        createdAt = o.optStringOrNull("created_at") ?: "",
                        sentAt = o.optStringOrNull("sent_at") ?: "",
                        invoiceId = o.optStringOrNull("invoice_id")
                    )
                )
            }
        }
    }

    fun disconnect(shopId: String) {
        runCatching { call("connectx/gateway/disconnect", "POST", JSONObject(), deviceToken(shopId)) }
        prefs.removeConnection(shopId)
    }

    /**
     * Checks official EMS App Store for newer ConnectX Gateway builds.
     */
    fun checkUpdate(packageName: String = "com.ems.connectx"): AppUpdateInfo? {
        return try {
            val base = prefs.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) return null
            val fullBase = if (!base.startsWith("http://") && !base.startsWith("https://")) "https://$base" else base
            val req = Request.Builder().url("$fullBase/api/app-store/check-update?package=$packageName").get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val text = res.body?.string().orEmpty()
                val obj = JSONObject(text)
                val vCode = obj.optInt("versionCode", obj.optInt("version_code", 0))
                val vName = obj.optString("latestVersion", obj.optString("version", "1.0.0"))
                var dl = obj.optString("downloadUrl", obj.optString("download_url", ""))
                val mandatory = obj.optBoolean("mandatory", false)
                val notes = obj.optString("releaseNotes", obj.optString("release_notes", ""))
                val title = obj.optString("title", "ConnectX SMS Gateway")
                val desc = obj.optString("description", "")
                val fn = obj.optString("apk_filename", "ConnectX-$vName.apk")
                val size = obj.optLong("apk_size_bytes", 8645200L)
                val updated = obj.optString("updated_at", "")

                if (dl.isNotBlank() && !dl.startsWith("http://") && !dl.startsWith("https://")) {
                    dl = if (dl.startsWith("/")) "$fullBase$dl" else "$fullBase/$dl"
                }

                if (vCode > 0 && dl.isNotBlank()) {
                    AppUpdateInfo(
                        title = title,
                        description = desc,
                        latestVersion = vName,
                        versionCode = vCode,
                        mandatory = mandatory,
                        downloadUrl = dl,
                        apkFilename = fn,
                        apkSizeBytes = size,
                        releaseNotes = notes,
                        updatedAt = updated
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

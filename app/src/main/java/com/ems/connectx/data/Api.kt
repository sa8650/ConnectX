package com.ems.connectx.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
            "DELETE" -> builder.delete(reqBody)
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
        prefs.adminToken = r.getString("token")
        val user = r.optJSONObject("user")
        prefs.adminEmail = user?.optString("email") ?: email
        prefs.adminName = user?.optString("name").orEmpty()
        return r
    }

    fun shops(): List<Shop> {
        val r = call("connectx/gateway/shops", token = prefs.adminToken)
        val arr = r.optJSONArray("shops") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Shop(
                        id = o.getString("id"),
                        name = o.optString("name"),
                        address = o.optString("address"),
                        phone = o.optString("phone"),
                        shopCode = o.optString("shop_code"),
                        connected = o.optBoolean("connected")
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
        val conn = Connection(
            shopId = shop.getString("id"),
            shopName = shop.optString("name"),
            shopAddress = shop.optString("address"),
            adminId = admin?.optString("id").orEmpty(),
            adminEmail = admin?.optString("email") ?: prefs.adminEmail,
            adminName = admin?.optString("name") ?: prefs.adminName,
            deviceId = device.getString("id"),
            devicePublicId = device.optString("device_public_id"),
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
                add(
                    SmsJob(
                        id = o.getString("id"),
                        shopId = o.optString("shop_id", shopId),
                        phone = o.optString("phone_number"),
                        message = o.optString("message"),
                        eventType = o.optString("event_type", o.optString("message_type")),
                        recipientName = o.optString("recipient_name"),
                        createdAt = o.optString("created_at")
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
        return HomeStats(
            sent = r.optInt("sent"),
            failed = r.optInt("failed"),
            pending = r.optInt("pending"),
            lastActivity = r.optString("lastActivity").takeIf { it.isNotBlank() && it != "null" },
            shopName = shop?.optString("name").orEmpty(),
            shopAddress = shop?.optString("address").orEmpty(),
            adminName = admin?.optString("name").orEmpty(),
            adminEmail = admin?.optString("email").orEmpty()
        )
    }

    fun activity(shopId: String, range: String): List<ActivityItem> {
        val r = call("connectx/gateway/activity?range=$range", token = deviceToken(shopId))
        val arr = r.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    ActivityItem(
                        id = o.optString("id"),
                        phone = o.optString("to_phone"),
                        name = o.optString("recipient_name"),
                        type = o.optString("event_type", o.optString("message_type")),
                        status = o.optString("status"),
                        error = o.optString("error_message").takeIf { it.isNotBlank() },
                        message = o.optString("message_body").ifBlank { o.optString("message") },
                        createdAt = o.optString("created_at"),
                        sentAt = o.optString("sent_at")
                    )
                )
            }
        }
    }

    fun disconnect(shopId: String) {
        runCatching { call("connectx/gateway/disconnect", "POST", JSONObject(), deviceToken(shopId)) }
        prefs.removeConnection(shopId)
    }
}

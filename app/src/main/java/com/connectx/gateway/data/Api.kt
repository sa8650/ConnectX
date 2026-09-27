package com.connectx.gateway.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.connectx.gateway.BuildConfig
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.TimeZone

private fun localOffsetMinutes(): Int =
    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

fun JSONObject.optStringOrNull(name: String): String? {
    if (isNull(name)) return null
    val v = optString(name, "").trim()
    return if (v.isEmpty() || v.equals("null", ignoreCase = true)) null else v
}

private fun JSONObject.emailAddresses(field: String): List<String> {
    // Both Postgres text[] and the D1 adapter normally return JSON arrays.
    val arr = optJSONArray(field) ?: runCatching {
        JSONArray(optStringOrNull(field) ?: "[]")
    }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optString(i).trim().takeIf { it.isNotBlank() && it != "null" }
    }
}

internal fun JSONObject.toEmailItem(): EmailItem = EmailItem(
    id = optStringOrNull("id") ?: throw IllegalStateException("Invalid ConnectX email record."),
    subject = optStringOrNull("subject") ?: "(No subject)",
    fromEmail = optStringOrNull("from_email") ?: "",
    toEmails = emailAddresses("to_emails"),
    ccEmails = emailAddresses("cc_emails"),
    bccEmails = emailAddresses("bcc_emails"),
    recipientType = optStringOrNull("recipient_type") ?: "",
    status = optStringOrNull("status") ?: "queued",
    error = optStringOrNull("error_message"),
    createdAt = optStringOrNull("created_at") ?: "",
    sentAt = optStringOrNull("sent_at") ?: "",
    customBody = optStringOrNull("custom_body") ?: "",
    bodyHtml = optStringOrNull("body_html") ?: ""
)

class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String = "${GatewayUrl.normalize(prefs.baseUrl)}/api/$path"

    private fun execute(request: Request): okhttp3.Response = try {
        http.newCall(request).execute()
    } catch (error: UnknownHostException) {
        throw IllegalStateException(
            "Cannot reach ConnectX. Check the internet connection and try again.", error
        )
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
        execute(builder.build()).use { res ->
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

    /** The system dropdown on the sign-in screen (EMS present; InfluenceOS,
     * CareOS, PlugX as they are connected). Served by the ConnectX website -
     * it never exposes the systems' own API URLs to the phone. */
    fun systems(): List<SystemOption> {
        val r = call("device/systems", token = null)
        val arr = r.optJSONArray("systems") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SystemOption(
                        key = o.getString("key"),
                        name = o.optStringOrNull("name") ?: o.getString("key"),
                        available = o.optBoolean("available", false)
                    )
                )
            }
        }
    }

    /** Federated administrator sign-in: ConnectX verifies the credentials
     * against the selected system's own API and returns a ConnectX session
     * plus the administrator's shops. The phone never talks to the system. */
    fun adminLogin(systemKey: String, email: String, password: String): JSONObject {
        val r = call(
            "device/auth/login", "POST",
            JSONObject().put("system", systemKey).put("email", email).put("password", password),
            token = null
        )
        prefs.adminToken = r.optString("token")
        val system = r.optJSONObject("system")
        prefs.systemKey = system?.optStringOrNull("key") ?: systemKey
        prefs.systemName = system?.optStringOrNull("name") ?: prefs.systemKey

        val user = r.optJSONObject("administrator") ?: r.optJSONObject("admin") ?: r.optJSONObject("user")
        if (user != null) {
            val p = AdminProfile(
                id = user.optStringOrNull("id") ?: "",
                adminCode = user.optStringOrNull("admin_code") ?: user.optStringOrNull("adminCode") ?: "",
                name = user.optStringOrNull("name") ?: "",
                email = user.optStringOrNull("email") ?: email,
                phone = user.optStringOrNull("phone") ?: "",
                address = user.optStringOrNull("address") ?: "",
                active = user.optBoolean("active", true),
                createdAt = user.optStringOrNull("created_at") ?: ""
            )
            prefs.saveAdminProfile(p)
        }
        return r
    }

    fun fetchAdminProfile(shopId: String? = null): AdminProfile {
        // Device-token route: 'me' returns the administrator behind this device
        // (null for code-paired phones with no administrator sign-in).
        try {
            val token = runCatching { deviceToken(shopId) }.getOrNull()
            if (!token.isNullOrBlank()) {
                val r = call("device/me", token = token)
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

        // Admin-session route: re-sync through the administrator's shop list.
        try {
            if (prefs.adminToken.isNotBlank()) {
                val r = call("device/shops", token = prefs.adminToken)
                val admin = r.optJSONObject("administrator")
                val system = r.optJSONObject("system")
                if (system != null) {
                    prefs.systemKey = system.optStringOrNull("key") ?: prefs.systemKey
                    prefs.systemName = system.optStringOrNull("name") ?: prefs.systemName
                }
                if (admin != null) {
                    val p = AdminProfile(
                        id = admin.optStringOrNull("id") ?: prefs.adminId,
                        adminCode = admin.optStringOrNull("admin_code") ?: admin.optStringOrNull("adminCode") ?: prefs.adminCode,
                        name = admin.optStringOrNull("name") ?: prefs.adminName,
                        email = admin.optStringOrNull("email") ?: prefs.adminEmail,
                        phone = admin.optStringOrNull("phone") ?: prefs.adminPhone,
                        address = admin.optStringOrNull("address") ?: prefs.adminAddress,
                        active = admin.optBoolean("active", true),
                        createdAt = admin.optStringOrNull("created_at") ?: prefs.adminCreatedAt
                    )
                    prefs.saveAdminProfile(p)
                    return p
                }
            }
        } catch (_: Exception) {}

        return prefs.getAdminProfile()
    }

    fun shops(): List<Shop> {
        val r = call("device/shops", token = prefs.adminToken)
        val system = r.optJSONObject("system")
        if (system != null) {
            prefs.systemKey = system.optStringOrNull("key") ?: prefs.systemKey
            prefs.systemName = system.optStringOrNull("name") ?: prefs.systemName
        }
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
                        category = o.optStringOrNull("category") ?: "",
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
            "device/register", "POST",
            JSONObject()
                .put("shopId", shopId)
                .put("deviceName", deviceName)
                .put("androidVersion", androidVersion)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("simSubscriptionId", simId)
                .put("simCarrier", carrier)
                .put("phoneNumber", phone),
            token = prefs.adminToken
        )
        val device = r.getJSONObject("device")
        val shop = r.getJSONObject("shop")
        val system = r.optJSONObject("system")
        val admin = r.optJSONObject("administrator")
        if (system != null) {
            prefs.systemKey = system.optStringOrNull("key") ?: prefs.systemKey
            prefs.systemName = system.optStringOrNull("name") ?: prefs.systemName
        }

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
            systemKey = prefs.systemKey,
            systemName = prefs.systemName,
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

    /** Account-free pairing: a short-lived code generated in ConnectX Control
     * (Gateways → Pair new gateway) binds this phone to a shop and
     * returns its revocable device token. No operator credentials involved. */
    fun pairDevice(
        code: String,
        deviceName: String,
        androidVersion: String,
        appVersion: String,
        simId: Int,
        carrier: String,
        phone: String
    ): Connection {
        val r = call(
            "device/pair", "POST",
            JSONObject()
                .put("code", code)
                .put("deviceName", deviceName)
                .put("androidVersion", androidVersion)
                .put("appVersion", appVersion)
                .put("simSubscriptionId", simId)
                .put("simCarrier", carrier)
                .put("phoneNumber", phone),
            token = null
        )
        val device = r.getJSONObject("device")
        val shop = r.getJSONObject("shop")
        val system = r.optJSONObject("system")
        val conn = Connection(
            shopId = shop.getString("id"),
            shopName = shop.optStringOrNull("name") ?: "Shop",
            shopAddress = shop.optStringOrNull("address") ?: "",
            systemKey = system?.optStringOrNull("key") ?: "",
            systemName = system?.optStringOrNull("name") ?: "",
            adminId = "",
            adminEmail = "",
            adminName = "",
            adminCode = "",
            adminPhone = "",
            adminAddress = "",
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

    /** Old pairing sessions only. A signed-in phone always uses the ConnectX device API. */
    private fun pairedWithoutLogin(shopId: String? = null): Boolean {
        val id = shopId ?: prefs.activeShopId
        val token = prefs.connections().firstOrNull { it.shopId == id }?.deviceToken
        return token == "connect" && prefs.connectActive
    }

    fun heartbeat(shopId: String? = null): JSONObject {
        if (pairedWithoutLogin(shopId)) {
            runCatching { ConnectClient(prefs).ping() }
            return JSONObject().put("ok", true)
        }
        return call("device/heartbeat", "POST", JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE), deviceToken(shopId))
    }

    fun claim(shopId: String, limit: Int = 8): List<SmsJob> {
        if (pairedWithoutLogin(shopId)) {
            return ConnectClient(prefs).pull(limit)
        }

        val r = call("device/jobs/claim", "POST", JSONObject().put("limit", limit), deviceToken(shopId))
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
        if (pairedWithoutLogin(shopId)) {
            ConnectClient(prefs).report(jobId, sent, error)
            return
        }

        call(
            "device/jobs/report", "POST",
            JSONObject()
                .put("jobId", jobId)
                .put("status", if (sent) "sent" else "failed")
                .put("error", error ?: JSONObject.NULL),
            deviceToken(shopId)
        )
    }

    /** Cancel only if ConnectX confirms deletion of a queued job. Never claim a
     * cancellation succeeded after a network failure: it could silently drop SMS. */
    fun cancelJob(shopId: String, jobId: String): Boolean {
        if (pairedWithoutLogin(shopId)) {
            throw IllegalStateException("Cancel this SMS from the product that sent it. This phone only delivers.")
        }
        val res = call(
            "device/jobs/cancel", "POST",
            JSONObject().put("jobId", jobId),
            deviceToken(shopId)
        )
        val confirmed = res.optBoolean("cancelled", false)
        if (confirmed) prefs.markCancelled(jobId)
        return confirmed
    }

    fun markTest(shopId: String, ok: Boolean) {
        if (pairedWithoutLogin(shopId)) {
            prefs.updateConnection(shopId) { it.copy(setupComplete = ok) }
            return
        }
        call("device/test", "POST", JSONObject().put("ok", ok).put("record", false), deviceToken(shopId))
        prefs.updateConnection(shopId) { it.copy(setupComplete = ok) }
    }

    fun updateSim(shopId: String, simId: Int, carrier: String, phone: String) {
        if (pairedWithoutLogin(shopId)) {
            prefs.updateConnection(shopId) { it.copy(simSubscriptionId = simId, simCarrier = carrier, phoneNumber = phone) }
            runCatching { ConnectClient(prefs).updateSim(simId, carrier, phone) }
            return
        }
        call(
            "device/sim", "PATCH",
            JSONObject().put("simSubscriptionId", simId).put("simCarrier", carrier).put("phoneNumber", phone),
            deviceToken(shopId)
        )
        prefs.updateConnection(shopId) { it.copy(simSubscriptionId = simId, simCarrier = carrier, phoneNumber = phone) }
    }

    fun stats(shopId: String): HomeStats {
        if (pairedWithoutLogin(shopId)) {
            val r = ConnectClient(prefs).activity()
            val conn = prefs.active()
            return HomeStats(
                sent = r.optInt("sent", 0),
                failed = r.optInt("failed", 0),
                pending = r.optInt("pending", 0),
                lastActivity = r.optStringOrNull("lastActivity"),
                shopName = "ConnectX",
                shopAddress = "",
                systemName = "Connect App",
                devicePublicId = prefs.connectApplicationId,
                simCarrier = conn?.simCarrier.orEmpty(),
                simPhone = conn?.phoneNumber.orEmpty(),
                deviceOnline = true
            )
        }
        val r = call("device/stats?utcOffsetMinutes=${localOffsetMinutes()}", token = deviceToken(shopId))
        val shop = r.optJSONObject("shop")
        val admin = r.optJSONObject("administrator")
        val device = r.optJSONObject("device")
        val system = r.optJSONObject("system")
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
            systemName = system?.optStringOrNull("name") ?: conn?.systemName.orEmpty(),
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

    fun emailStats(shopId: String): EmailStats {
        if (pairedWithoutLogin(shopId)) return EmailStats(sent = 0, failed = 0, pending = 0, latest = null)
        val r = call("device/emails/stats?utcOffsetMinutes=${localOffsetMinutes()}", token = deviceToken(shopId))
        if (!r.has("sent") || !r.has("failed") || !r.has("pending"))
            throw IllegalStateException("ConnectX Control did not return email statistics. Update the ConnectX Control deployment.")
        return EmailStats(
            sent = r.getInt("sent"),
            failed = r.getInt("failed"),
            pending = r.getInt("pending"),
            latest = r.optJSONObject("latest")?.toEmailItem()
        )
    }

    fun emailPage(shopId: String, page: Int, snapshot: String = ""): EmailPage {
        if (pairedWithoutLogin(shopId)) return EmailPage(emptyList(), page, snapshot, false)
        require(page >= 0) { "Invalid email page." }
        val suffix = if (page == 0) "" else "&snapshot=${URLEncoder.encode(snapshot, "UTF-8")}"
        val r = call("device/emails?page=$page$suffix", token = deviceToken(shopId))
        val arr = r.optJSONArray("items")
            ?: throw IllegalStateException("ConnectX Control did not return email history. Update the ConnectX Control deployment.")
        val anchor = r.optStringOrNull("snapshot")
            ?: throw IllegalStateException("ConnectX Control did not return an email history snapshot.")
        return EmailPage(
            items = (0 until arr.length()).map { arr.getJSONObject(it).toEmailItem() },
            page = r.getInt("page"),
            snapshot = anchor,
            hasMore = r.getBoolean("hasMore")
        )
    }

    fun emailDetail(shopId: String, emailId: String): EmailItem {
        if (pairedWithoutLogin(shopId)) throw IllegalStateException("Email history is not part of Connect App SMS.")
        require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(emailId)) { "Invalid email ID." }
        return call("device/emails/$emailId", token = deviceToken(shopId)).toEmailItem()
    }

    fun activityPage(shopId: String, range: String, limit: Int = 40, offset: Int = 0): ActivityPage {
        if (pairedWithoutLogin(shopId)) {
            return ActivityPage(activity(shopId, range), false)
        }
        val r = call(
            "device/activity?range=$range&limit=$limit&offset=$offset&utcOffsetMinutes=${localOffsetMinutes()}",
            token = deviceToken(shopId)
        )
        return ActivityPage(parseActivity(r), r.optBoolean("hasMore", false))
    }

    fun activity(shopId: String, range: String): List<ActivityItem> {
        if (pairedWithoutLogin(shopId)) {
            val r = ConnectClient(prefs).activity()
            val arr = r.optJSONArray("items") ?: JSONArray()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(ActivityItem(
                        id = o.optString("id"),
                        phone = o.optString("to_phone"),
                        name = "Recipient",
                        type = "SMS",
                        status = o.optString("status"),
                        error = o.optStringOrNull("error_message"),
                        message = o.optString("message_body"),
                        createdAt = o.optString("created_at"),
                        sentAt = o.optString("sent_at")
                    ))
                }
            }
        }
        val r = call("device/activity?range=$range&limit=80&utcOffsetMinutes=${localOffsetMinutes()}", token = deviceToken(shopId))
        return parseActivity(r)
    }

    private fun parseActivity(r: JSONObject): List<ActivityItem> {
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
        if (pairedWithoutLogin(shopId)) {
            ConnectClient(prefs).disconnect()
            return
        }
        runCatching { call("device/disconnect", "POST", JSONObject(), deviceToken(shopId)) }
        prefs.removeConnection(shopId)
    }

    /** Public ConnectX release endpoint; the server compares with our installed Gradle build code.
     * Never convert HTTP/network/invalid-response failures to "up to date". */
    fun checkUpdate(packageName: String, installedVersionCode: Int): AppUpdateInfo {
        val pkg = URLEncoder.encode(packageName, "UTF-8")
        val req = Request.Builder()
            .url(url("public/releases/check?package=$pkg&versionCode=$installedVersionCode"))
            .header("Cache-Control", "no-cache")
            .get().build()
        execute(req).use { res ->
            val raw = res.body?.string().orEmpty()
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (!res.isSuccessful) {
                throw IllegalStateException(obj?.optStringOrNull("error") ?: "ConnectX release server returned HTTP ${res.code}.")
            }
            if (obj == null || !obj.optBoolean("ok", false))
                throw IllegalStateException("ConnectX release server returned an invalid update response.")
            val code = obj.optInt("versionCode", obj.optInt("version_code", 0))
            val version = obj.optStringOrNull("latestVersion") ?: obj.optStringOrNull("version")
            val download = obj.optStringOrNull("downloadUrl") ?: obj.optStringOrNull("download_url")
            if (code <= 0 || version.isNullOrBlank() || download.isNullOrBlank())
                throw IllegalStateException("ConnectX release metadata is incomplete. Ask the platform owner to publish a real APK in ConnectX Control → App Releases.")
            val fullUrl = if (download.startsWith("https://") || download.startsWith("http://"))
                download else url("").removeSuffix("api/") + download.trimStart('/')
            return AppUpdateInfo(
                title = obj.optStringOrNull("title") ?: "ConnectX: Central Communication Gateway powered by Dexter Studio",
                description = obj.optStringOrNull("description") ?: "",
                latestVersion = version,
                versionCode = code,
                mandatory = obj.optBoolean("mandatory", false),
                downloadUrl = fullUrl,
                apkFilename = obj.optStringOrNull("apk_filename") ?: "ConnectX-$version.apk",
                apkSizeBytes = obj.optLong("apk_size_bytes", 0L),
                releaseNotes = obj.optStringOrNull("releaseNotes") ?: obj.optStringOrNull("release_notes") ?: "",
                updatedAt = obj.optStringOrNull("updated_at") ?: ""
            )
        }
    }
}

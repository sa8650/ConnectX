package com.connectx.gateway.data

import com.connectx.gateway.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private fun localOffsetMinutes(): Int =
    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

fun JSONObject.optStringOrNull(name: String): String? {
    if (isNull(name)) return null
    val value = optString(name, "").trim()
    return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

private fun JSONObject.emailAddresses(field: String): List<String> {
    val array = optJSONArray(field) ?: runCatching {
        JSONArray(optStringOrNull(field) ?: "[]")
    }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        array.optString(i).trim().takeIf { it.isNotBlank() && it != "null" }
    }
}

internal fun JSONObject.toEmailItem(): EmailItem = EmailItem(
    id = optStringOrNull("id") ?: throw IllegalStateException("Invalid EMS email record."),
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

/**
 * EMS is the direct SMS/account API. ConnectX Control is an independent origin
 * for app releases and optional, separately authenticated monitoring telemetry;
 * Control is never in the SMS queue, claim, send, or EMS-result path.
 */
class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val emsOrigin = BuildConfig.EMS_BASE_URL.trimEnd('/')
    private val releaseOrigin = BuildConfig.CONTROL_RELEASE_URL.trimEnd('/')

    private fun emsUrl(path: String): String = "$emsOrigin/api/${path.trimStart('/')}"
    private fun releaseUrl(path: String): String = "$releaseOrigin/api/${path.trimStart('/')}"

    private fun execute(request: Request): okhttp3.Response = try {
        http.newCall(request).execute()
    } catch (error: UnknownHostException) {
        throw IllegalStateException("Cannot reach EMS. Check the internet connection and try again.", error)
    }

    private fun controlCall(path: String, body: JSONObject, token: String? = null): JSONObject {
        val builder = Request.Builder().url(releaseUrl(path)).header("Accept", "application/json")
            .header("Content-Type", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val request = builder.post(body.toString().toRequestBody(jsonType)).build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw IllegalStateException(
                obj.optStringOrNull("error") ?: "ConnectX Control request failed (${response.code})."
            )
            return obj
        }
    }

    private fun call(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        token: String? = null
    ): JSONObject {
        val builder = Request.Builder().url(emsUrl(path)).header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val requestBody = body?.toString()?.toRequestBody(jsonType)
        when (method) {
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonType))
            "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody(jsonType))
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> builder.get()
        }
        execute(builder.build()).use { response ->
            val text = response.body?.string().orEmpty()
            val obj = try {
                if (text.startsWith("[")) JSONObject().put("items", JSONArray(text))
                else JSONObject(text.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject().put("raw", text)
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    obj.optStringOrNull("error") ?: "EMS request failed (${response.code})."
                )
            }
            return obj
        }
    }

    /** Administrator authentication is verified by EMS itself over HTTPS. */
    fun adminLogin(email: String, password: String): JSONObject {
        val response = call(
            "auth/admin/login",
            "POST",
            JSONObject().put("email", email.trim()).put("password", password)
        )
        val token = response.optStringOrNull("token")
            ?: throw IllegalStateException("EMS returned no administrator session.")
        prefs.adminToken = token
        prefs.systemKey = "ems"
        prefs.systemName = "EMS"
        response.optJSONObject("user")?.let { user ->
            prefs.saveAdminProfile(
                AdminProfile(
                    id = user.optStringOrNull("id") ?: "",
                    adminCode = user.optStringOrNull("admin_code") ?: "",
                    name = user.optStringOrNull("name") ?: "",
                    email = user.optStringOrNull("email") ?: email,
                    phone = user.optStringOrNull("phone") ?: "",
                    address = user.optStringOrNull("address") ?: "",
                    active = user.optBoolean("active", true),
                    createdAt = user.optStringOrNull("created_at") ?: ""
                )
            )
        }
        return response
    }

    fun fetchAdminProfile(shopId: String? = null): AdminProfile {
        if (!shopId.isNullOrBlank()) {
            val connection = prefs.connections().firstOrNull { it.shopId == shopId }
            if (!connection?.deviceToken.isNullOrBlank()) {
                val response = call("connectx/device/me", token = connection!!.deviceToken)
                response.optJSONObject("administrator")?.let { admin ->
                    val profile = admin.toAdminProfile(prefs)
                    prefs.saveAdminProfile(profile)
                    return profile
                }
            }
        }
        if (prefs.adminToken.isNotBlank()) {
            val response = call("connectx/device/shops", token = prefs.adminToken)
            response.optJSONObject("administrator")?.let { admin ->
                val profile = admin.toAdminProfile(prefs)
                prefs.saveAdminProfile(profile)
                return profile
            }
        }
        return prefs.getAdminProfile()
    }

    fun shops(): List<Shop> {
        val response = call("connectx/device/shops", token = prefs.adminToken)
        response.optJSONObject("administrator")?.let { admin ->
            prefs.saveAdminProfile(admin.toAdminProfile(prefs))
        }
        val array = response.optJSONArray("shops") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    Shop(
                        id = item.getString("id"),
                        name = item.optStringOrNull("name") ?: "Shop",
                        address = item.optStringOrNull("address") ?: "",
                        phone = item.optStringOrNull("phone") ?: "",
                        shopCode = item.optStringOrNull("shop_code") ?: "",
                        category = item.optStringOrNull("category") ?: "",
                        connected = item.optBoolean("connected", false)
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
        val existing = prefs.connections().firstOrNull { it.shopId == shopId }
        val payload = JSONObject()
            .put("shopId", shopId)
            .put("deviceName", deviceName)
            .put("androidVersion", androidVersion)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("simSubscriptionId", simId)
            .put("simCarrier", carrier)
            .put("phoneNumber", phone)
        if (!existing?.deviceId.isNullOrBlank()) payload.put("existingDeviceId", existing!!.deviceId)
        val response = call("connectx/device/register", "POST", payload, prefs.adminToken)
        val device = response.getJSONObject("device")
        val shop = response.getJSONObject("shop")
        val admin = response.optJSONObject("administrator")
        if (admin != null) prefs.saveAdminProfile(admin.toAdminProfile(prefs))

        val profile = prefs.getAdminProfile()
        val connection = Connection(
            shopId = shop.getString("id"),
            shopName = shop.optStringOrNull("name") ?: "Shop",
            shopAddress = shop.optStringOrNull("address") ?: "",
            systemKey = "ems",
            systemName = "EMS",
            adminId = profile.id,
            adminEmail = profile.email,
            adminName = profile.name,
            adminCode = profile.adminCode,
            adminPhone = profile.phone,
            adminAddress = profile.address,
            deviceId = device.getString("id"),
            devicePublicId = device.optStringOrNull("device_public_id") ?: "",
            deviceToken = response.getString("deviceToken"),
            simSubscriptionId = simId,
            simCarrier = carrier,
            phoneNumber = phone,
            setupComplete = false,
            deviceName = deviceName
        )
        prefs.saveConnection(connection)
        prefs.activeShopId = connection.shopId
        return connection
    }

    private fun deviceToken(shopId: String): String =
        prefs.connections().firstOrNull { it.shopId == shopId }?.deviceToken
            ?.takeIf { it.startsWith("cxd_") }
            ?: throw IllegalStateException("This shop is not registered on EMS. Register this phone again.")

    fun heartbeat(shopId: String): JSONObject = call(
        "connectx/device/heartbeat",
        "POST",
        JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("androidVersion", android.os.Build.VERSION.RELEASE ?: ""),
        deviceToken(shopId)
    )

    fun syncControlDevice(connection: Connection) {
        val profile = prefs.getAdminProfile()
        if (connection.deviceId.isBlank()) return
        val response = controlCall("telemetry/register", JSONObject()
            .put("emsDeviceId", connection.deviceId)
            .put("adminId", profile.id.ifBlank { connection.adminId })
            .put("adminCode", profile.adminCode.ifBlank { connection.adminCode })
            .put("adminName", profile.name.ifBlank { connection.adminName })
            .put("adminEmail", profile.email.ifBlank { connection.adminEmail })
            .put("shopId", connection.shopId)
            .put("shopName", connection.shopName)
            .put("deviceName", connection.deviceName.ifBlank { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim() }.ifBlank { "Android phone" })
            .put("simSubscriptionId", connection.simSubscriptionId.toString())
            .put("simCarrier", connection.simCarrier)
            .put("phoneNumber", connection.phoneNumber)
            .put("deviceStatus", if (connection.setupComplete) "active" else "pending_test")
            .put("androidVersion", android.os.Build.VERSION.RELEASE ?: "")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE), connection.controlDeviceToken.takeIf { it.isNotBlank() })
        val token = response.getString("token")
        prefs.updateConnection(connection.shopId) { it.copy(controlDeviceToken = token) }
    }

    fun controlHeartbeat(connection: Connection) {
        val token = connection.controlDeviceToken
        if (token.isBlank()) return syncControlDevice(connection)
        val profile = prefs.getAdminProfile()
        controlCall("telemetry/heartbeat", JSONObject()
            .put("emsDeviceId", connection.deviceId)
            .put("adminId", profile.id.ifBlank { connection.adminId })
            .put("adminCode", profile.adminCode.ifBlank { connection.adminCode })
            .put("adminName", profile.name.ifBlank { connection.adminName })
            .put("adminEmail", profile.email.ifBlank { connection.adminEmail })
            .put("shopId", connection.shopId)
            .put("shopName", connection.shopName)
            .put("deviceName", connection.deviceName.ifBlank { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim() }.ifBlank { "Android phone" })
            .put("simSubscriptionId", connection.simSubscriptionId.toString())
            .put("simCarrier", connection.simCarrier)
            .put("phoneNumber", connection.phoneNumber)
            .put("deviceStatus", if (connection.setupComplete) "active" else "pending_test")
            .put("androidVersion", android.os.Build.VERSION.RELEASE ?: "")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE), token)
    }

    fun sendControlSmsEvent(event: MonitorSmsEvent) {
        val connection = prefs.connections().firstOrNull { it.shopId == event.shopId }
            ?: throw IllegalStateException("No EMS registration exists for this Control event.")
        val token = connection.controlDeviceToken
        if (token.isBlank()) throw IllegalStateException("Control device registration is pending.")
        val profile = prefs.getAdminProfile()
        controlCall("telemetry/sms", JSONObject()
            .put("jobId", event.jobId)
            .put("shopId", event.shopId)
            .put("shopName", event.shopName)
            .put("adminId", event.adminId.ifBlank { profile.id })
            .put("adminCode", event.adminCode.ifBlank { profile.adminCode })
            .put("adminName", event.adminName.ifBlank { profile.name })
            .put("recipientPhone", event.recipientPhone)
            .put("recipientName", event.recipientName)
            .put("messageType", event.messageType)
            .put("status", event.status)
            .put("error", event.error ?: JSONObject.NULL)
            .put("createdAt", event.createdAt)
            .put("sentAt", event.sentAt.ifBlank { JSONObject.NULL })
            .put("deliveredAt", event.deliveredAt.ifBlank { JSONObject.NULL })
            .put("resultAt", event.resultAt)
            .put("attempts", event.attempts)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE), token)
    }

    fun claim(shopId: String, limit: Int = 8): List<SmsJob> {
        val response = call(
            "connectx/device/jobs/claim",
            "POST",
            JSONObject().put("limit", limit.coerceIn(1, 12)),
            deviceToken(shopId)
        )
        val array = response.optJSONArray("jobs") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val rawType = item.optStringOrNull("message_type")
                    ?: item.optStringOrNull("event_type") ?: "Custom Message"
                val message = item.optStringOrNull("message") ?: ""
                add(
                    SmsJob(
                        id = item.getString("id"),
                        shopId = item.optStringOrNull("shop_id") ?: shopId,
                        phone = item.optStringOrNull("phone_number") ?: "",
                        message = message,
                        eventType = formatMessageType(rawType, item.optStringOrNull("event_type"), message),
                        recipientName = item.optStringOrNull("recipient_name") ?: "Customer",
                        createdAt = item.optStringOrNull("created_at") ?: "",
                        claimToken = item.optStringOrNull("claim_token") ?: "",
                        attempts = item.optInt("attempts", 0)
                    )
                )
            }
        }
    }

    /** Records the atomic EMS claim as entering the SMS send phase before calling SmsManager. */
    fun start(shopId: String, jobId: String, claimToken: String): JSONObject = call(
        "connectx/device/jobs/start",
        "POST",
        JSONObject().put("jobId", jobId).put("claimToken", claimToken),
        deviceToken(shopId)
    )

    fun report(
        shopId: String,
        jobId: String,
        claimToken: String,
        status: String,
        error: String? = null,
        retryable: Boolean = false
    ): JSONObject = call(
        "connectx/device/jobs/report",
        "POST",
        JSONObject()
            .put("jobId", jobId)
            .put("claimToken", claimToken)
            .put("status", status)
            .put("error", error ?: JSONObject.NULL)
            .put("retryable", retryable),
        deviceToken(shopId)
    )

    fun cancelJob(shopId: String, jobId: String): Boolean = call(
        "connectx/device/jobs/cancel",
        "POST",
        JSONObject().put("jobId", jobId),
        deviceToken(shopId)
    ).optBoolean("cancelled", false)

    fun markTest(shopId: String, ok: Boolean) {
        call("connectx/device/test", "POST", JSONObject().put("ok", ok).put("record", false), deviceToken(shopId))
        prefs.updateConnection(shopId) { it.copy(setupComplete = ok) }
    }

    fun updateSim(shopId: String, simId: Int, carrier: String, phone: String) {
        call(
            "connectx/device/sim",
            "PATCH",
            JSONObject().put("simSubscriptionId", simId).put("simCarrier", carrier).put("phoneNumber", phone),
            deviceToken(shopId)
        )
        prefs.updateConnection(shopId) {
            it.copy(simSubscriptionId = simId, simCarrier = carrier, phoneNumber = phone)
        }
    }

    fun stats(shopId: String): HomeStats {
        val response = call(
            "connectx/device/stats?utcOffsetMinutes=${localOffsetMinutes()}",
            token = deviceToken(shopId)
        )
        val shop = response.optJSONObject("shop")
        val admin = response.optJSONObject("administrator")
        val device = response.optJSONObject("device")
        admin?.let { prefs.saveAdminProfile(it.toAdminProfile(prefs)) }
        val profile = prefs.getAdminProfile()
        val connection = prefs.connections().firstOrNull { it.shopId == shopId }
        return HomeStats(
            sent = response.optInt("sent", 0),
            delivered = response.optInt("delivered", 0),
            unknown = response.optInt("unknown", 0),
            processing = response.optInt("processing", 0),
            failed = response.optInt("failed", 0),
            pending = response.optInt("pending", 0),
            lastActivity = response.optStringOrNull("lastActivity"),
            shopName = shop?.optStringOrNull("name") ?: connection?.shopName.orEmpty(),
            shopAddress = shop?.optStringOrNull("address") ?: connection?.shopAddress.orEmpty(),
            shopPhone = shop?.optStringOrNull("phone") ?: "",
            systemName = "EMS",
            adminName = profile.name,
            adminEmail = profile.email,
            adminCode = profile.adminCode,
            adminPhone = profile.phone,
            adminAddress = profile.address,
            adminCreatedAt = profile.createdAt,
            adminActive = profile.active,
            deviceOnline = device?.optStringOrNull("status") == "active",
            devicePublicId = device?.optStringOrNull("device_public_id") ?: connection?.devicePublicId.orEmpty(),
            simCarrier = connection?.simCarrier.orEmpty(),
            simPhone = connection?.phoneNumber.orEmpty()
        )
    }

    fun emailStats(shopId: String): EmailStats {
        val response = call(
            "connectx/device/emails/stats?utcOffsetMinutes=${localOffsetMinutes()}",
            token = deviceToken(shopId)
        )
        if (!response.has("sent") || !response.has("failed") || !response.has("pending"))
            throw IllegalStateException("EMS did not return email statistics.")
        return EmailStats(
            sent = response.getInt("sent"),
            failed = response.getInt("failed"),
            pending = response.getInt("pending"),
            latest = response.optJSONObject("latest")?.toEmailItem()
        )
    }

    fun emailPage(shopId: String, page: Int, snapshot: String = ""): EmailPage {
        require(page >= 0) { "Invalid email page." }
        val suffix = if (page == 0) "" else "&snapshot=${URLEncoder.encode(snapshot, "UTF-8")}"
        val response = call("connectx/device/emails?page=$page$suffix", token = deviceToken(shopId))
        val array = response.optJSONArray("items")
            ?: throw IllegalStateException("EMS did not return email history.")
        val anchor = response.optStringOrNull("snapshot")
            ?: throw IllegalStateException("EMS did not return an email history snapshot.")
        return EmailPage(
            items = (0 until array.length()).map { array.getJSONObject(it).toEmailItem() },
            page = response.getInt("page"),
            snapshot = anchor,
            hasMore = response.getBoolean("hasMore")
        )
    }

    fun emailDetail(shopId: String, emailId: String): EmailItem {
        require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(emailId)) {
            "Invalid email ID."
        }
        return call("connectx/device/emails/$emailId", token = deviceToken(shopId)).toEmailItem()
    }

    fun activityPage(shopId: String, range: String, limit: Int = 40, offset: Int = 0): ActivityPage {
        val response = call(
            "connectx/device/activity?range=${URLEncoder.encode(range, "UTF-8")}&limit=$limit&offset=$offset&utcOffsetMinutes=${localOffsetMinutes()}",
            token = deviceToken(shopId)
        )
        return ActivityPage(parseActivity(response), response.optBoolean("hasMore", false))
    }

    fun activity(shopId: String, range: String): List<ActivityItem> = activityPage(shopId, range, 80).items

    private fun parseActivity(response: JSONObject): List<ActivityItem> {
        val array = response.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val rawType = item.optStringOrNull("message_type")
                val eventType = item.optStringOrNull("event_type")
                val message = item.optStringOrNull("message_body") ?: item.optStringOrNull("message") ?: ""
                add(
                    ActivityItem(
                        id = item.getString("id"),
                        phone = item.optStringOrNull("to_phone") ?: "",
                        name = item.optStringOrNull("recipient_name") ?: "Customer",
                        type = formatMessageType(rawType, eventType, message),
                        rawType = rawType ?: eventType ?: "Custom Message",
                        status = item.optStringOrNull("status") ?: "pending",
                        error = item.optStringOrNull("error_message"),
                        message = message,
                        createdAt = item.optStringOrNull("created_at") ?: "",
                        sentAt = item.optStringOrNull("sent_at") ?: "",
                        invoiceId = item.optStringOrNull("invoice_id")
                    )
                )
            }
        }
    }

    /** A failed network request is surfaced; do not discard the local token while EMS cannot revoke it. */
    fun disconnect(shopId: String) {
        val token = prefs.connections().firstOrNull { it.shopId == shopId }?.deviceToken.orEmpty()
        if (token.startsWith("cxd_")) {
            call("connectx/device/disconnect", "POST", JSONObject(), token)
        }
        // A pre-migration Relay/Control credential cannot authorize EMS
        // revocation and is no longer accepted for dispatch. Drop only its
        // local record; never call the legacy relay website.
        prefs.removeConnection(shopId)
    }

    /** App releases remain managed by ConnectX Control, separate from SMS delivery. */
    fun checkUpdate(packageName: String, installedVersionCode: Int): AppUpdateInfo {
        val pkg = URLEncoder.encode(packageName, "UTF-8")
        val request = Request.Builder()
            .url(releaseUrl("public/releases/check?package=$pkg&versionCode=$installedVersionCode"))
            .header("Cache-Control", "no-cache")
            .get().build()
        execute(request).use { response ->
            val raw = response.body?.string().orEmpty()
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                throw IllegalStateException(obj?.optStringOrNull("error") ?: "ConnectX release server returned HTTP ${response.code}.")
            }
            if (obj == null || !obj.optBoolean("ok", false))
                throw IllegalStateException("ConnectX release server returned an invalid update response.")
            val code = obj.optInt("versionCode", obj.optInt("version_code", 0))
            val version = obj.optStringOrNull("latestVersion") ?: obj.optStringOrNull("version")
            val download = obj.optStringOrNull("downloadUrl") ?: obj.optStringOrNull("download_url")
            if (code <= 0 || version.isNullOrBlank() || download.isNullOrBlank())
                throw IllegalStateException("ConnectX release metadata is incomplete. Ask the platform owner to publish an APK in ConnectX Control → App Releases.")
            val fullUrl = when {
                download.startsWith("https://") || download.startsWith("http://") -> download
                download.startsWith("/") -> "$releaseOrigin$download"
                else -> "$releaseOrigin/${download.trimStart('/')}"
            }
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

private fun JSONObject.toAdminProfile(prefs: Prefs): AdminProfile = AdminProfile(
    id = optStringOrNull("id") ?: prefs.adminId,
    adminCode = optStringOrNull("admin_code") ?: prefs.adminCode,
    name = optStringOrNull("name") ?: prefs.adminName,
    email = optStringOrNull("email") ?: prefs.adminEmail,
    phone = optStringOrNull("phone") ?: prefs.adminPhone,
    address = optStringOrNull("address") ?: prefs.adminAddress,
    active = optBoolean("active", true),
    createdAt = optStringOrNull("created_at") ?: prefs.adminCreatedAt
)

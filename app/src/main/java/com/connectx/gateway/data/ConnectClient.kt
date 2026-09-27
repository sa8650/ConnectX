package com.connectx.gateway.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Connect App client. Same envelope the websites use. The endpoint URL
 * lives in [ConnectEndpoint] and is not part of any screen.
 */
class ConnectClient(private val prefs: Prefs) {
    fun ensureAppId(): String {
        if (prefs.connectApplicationId.isBlank()) {
            prefs.connectApplicationId = "CXAPP-" + randomToken(8)
        }
        return prefs.connectApplicationId
    }

    fun requestConnection(deviceName: String): String {
        val name = deviceName.ifBlank { Build.MODEL ?: "Android phone" }
        prefs.connectDeviceName = name
        val token = "req_" + randomToken(24)
        val code = pairingCode()
        val payload = JSONObject()
            .put("application_name", "ConnectX Android")
            .put("kind", "android")
            .put("display_name", name)
            .put("requested_permissions", JSONArray().put("sms:deliver"))
            .put("pairing_code", code)
            .put("request_token", token)
        val response = post(unsigned("CONNECT_REQUEST", payload))
        if (!response.optBoolean("ok", false)) {
            throw IllegalStateException(response.optString("error", "ConnectX did not accept this phone."))
        }
        prefs.connectRequestToken = token
        prefs.connectPairingCode = code
        prefs.connectStatus = "PENDING_APPROVAL"
        return code
    }

    /** @return ACTIVE, PENDING_APPROVAL, REJECTED, or CANCELLED */
    fun poll(): String {
        val token = prefs.connectRequestToken
        if (token.isBlank()) return prefs.connectStatus.ifBlank { "DISCONNECTED" }
        val payload = JSONObject().put("request_token", token)
        val response = post(unsigned("POLL_PAIRING", payload))
        val status = response.optString("status", "")
        if (status == "REJECTED" || status == "CANCELLED" || status == "EXPIRED") {
            prefs.connectStatus = status
            return status
        }
        val handshake = response.optJSONObject("handshake")
        if (status == "ACTIVE" && handshake != null) {
            prefs.connectConnectionId = handshake.optString("connection_id")
            prefs.connectSharedSecret = handshake.optString("shared_secret")
            prefs.connectRemoteAppId = handshake.optString("remote_application_id")
            prefs.connectStatus = "ACTIVE"
            return "ACTIVE"
        }
        prefs.connectStatus = "PENDING_APPROVAL"
        return "PENDING_APPROVAL"
    }

    fun pull(limit: Int = 5): List<SmsJob> {
        val payload = JSONObject()
            .put("limit", limit)
            .put("device_name", prefs.connectDeviceName)
            .put("sim_label", simLabel())
            .put("app_version", "")
        val response = signed("PULL_TASKS", payload)
        val arr = response.optJSONArray("tasks") ?: JSONArray()
        val shopId = prefs.activeShopId.ifBlank { prefs.connectConnectionId }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SmsJob(
                        id = o.getString("request_id"),
                        shopId = shopId,
                        phone = o.optString("recipient"),
                        message = o.optString("message"),
                        eventType = "SMS",
                        recipientName = "Recipient",
                        createdAt = o.optString("created_at")
                    )
                )
            }
        }
    }

    fun report(requestId: String, success: Boolean, reason: String? = null) {
        val payload = JSONObject()
            .put("request_id", requestId)
            .put("status", if (success) "SUCCESS" else "FAILED")
            .put("sim_used", simLabel())
            .put("timestamp", isoNow())
        if (!reason.isNullOrBlank()) payload.put("reason", reason)
        signed("TASK_RESULT", payload)
    }

    fun updateSim(simId: Int, carrier: String, phone: String) {
        val label = listOf(carrier, phone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "SIM $simId" }
        signed("SIM_UPDATE", JSONObject().put("sim_label", label).put("sim_subscription_id", simId).put("device_name", prefs.connectDeviceName))
    }

    fun activity(): JSONObject = signed("ACTIVITY", JSONObject())

    fun ping() {
        signed("PING", JSONObject())
    }

    fun disconnect() {
        if (prefs.connectStatus == "ACTIVE" && prefs.connectSharedSecret.isNotBlank()) {
            runCatching { signed("DISCONNECT", JSONObject().put("reason", "Disconnected on the phone")) }
        }
        prefs.clearConnect()
    }

    private fun simLabel(): String {
        val conn = prefs.active()
        return listOf(conn?.simCarrier.orEmpty(), conn?.phoneNumber.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun unsigned(action: String, payload: JSONObject): JSONObject {
        return JSONObject()
            .put("protocol", "connect-app/1")
            .put("action", action)
            .put("application_id", ensureAppId())
            .put("connection_id", "")
            .put("timestamp", isoNow())
            .put("nonce", randomToken(16))
            .put("payload", payload)
    }

    private fun signed(action: String, payload: JSONObject): JSONObject {
        val secret = prefs.connectSharedSecret
        if (secret.isBlank() || prefs.connectConnectionId.isBlank()) {
            throw IllegalStateException("This phone is not connected.")
        }
        val timestamp = isoNow()
        val nonce = randomToken(16)
        val hash = sha256(stableStringify(payload))
        val canonical = listOf("connect-app/1", action, prefs.connectApplicationId, prefs.connectConnectionId, timestamp, nonce, hash).joinToString("\n")
        val envelope = JSONObject()
            .put("protocol", "connect-app/1")
            .put("action", action)
            .put("application_id", prefs.connectApplicationId)
            .put("connection_id", prefs.connectConnectionId)
            .put("timestamp", timestamp)
            .put("nonce", nonce)
            .put("payload", payload)
            .put("signature", hmacHex(canonical, secret))
        return post(envelope)
    }

    private fun post(body: JSONObject): JSONObject {
        val conn = (URL(ConnectEndpoint.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val obj = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrElse { JSONObject().put("error", text) }
            if (code == 401 || code == 403) {
                if (obj.optString("code") == "not_connected") prefs.connectStatus = "DISCONNECTED"
            }
            if (code !in 200..299 || obj.optBoolean("ok", true) == false && obj.has("error")) {
                throw IllegalStateException(obj.optString("error", "Connect App request failed ($code)"))
            }
            return obj
        } finally {
            conn.disconnect()
        }
    }

    private fun isoNow(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    private fun randomToken(n: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val bytes = ByteArray(n)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { alphabet[(it.toInt() and 0xff) % alphabet.length].toString() }
    }

    private fun pairingCode(): String {
        val bytes = ByteArray(6)
        java.security.SecureRandom().nextBytes(bytes)
        val d = bytes.joinToString("") { ((it.toInt() and 0xff) % 10).toString() }
        return d.substring(0, 3) + "-" + d.substring(3)
    }
}

internal fun jsQuote(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch.code) {
            0x22 -> out.append('\\').append('"')
            0x5C -> out.append('\\').append('\\')
            0x08 -> out.append('\\').append('b')
            0x0C -> out.append('\\').append('f')
            0x0A -> out.append('\\').append('n')
            0x0D -> out.append('\\').append('r')
            0x09 -> out.append('\\').append('t')
            else -> {
                if (ch.code < 0x20) {
                    out.append('\\').append('u')
                    out.append("%04x".format(ch.code))
                } else out.append(ch)
            }
        }
    }
    out.append('"')
    return out.toString()
}

/** Same bytes as JavaScript JSON.stringify. JSONObject.quote escapes slash and would break HMAC. */
internal fun stableStringify(value: Any?): String {
    if (value == null || value == JSONObject.NULL) return "null"
    return when (value) {
        is String -> jsQuote(value)
        is Boolean -> if (value) "true" else "false"
        is Int, is Long -> value.toString()
        is Number -> {
            val d = value.toDouble()
            if (!d.isFinite()) "null" else if (d == d.toLong().toDouble()) d.toLong().toString() else value.toString()
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { stableStringify(value.get(it)) }
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(prefix = "{", postfix = "}") { key ->
                jsQuote(key) + ":" + stableStringify(value.get(key))
            }
        }
        else -> jsQuote(value.toString())
    }
}

internal fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun hmacHex(data: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}


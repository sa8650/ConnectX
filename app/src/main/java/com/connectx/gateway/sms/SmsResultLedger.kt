package com.connectx.gateway.sms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class SmsResultOutcome(
    val status: String,
    val error: String? = null,
    val retryable: Boolean = false
)

/** Durable, per-claim multipart callback aggregation; send acceptance and delivery receipts stay distinct. */
internal object SmsResultLedger {
    private val lock = Any()
    private const val STORE = "connectx.sms-results"

    fun record(
        context: Context,
        shopId: String,
        jobId: String,
        claimToken: String,
        partCount: Int,
        partIndex: Int,
        delivery: Boolean,
        success: Boolean,
        error: String?
    ): List<SmsResultOutcome> = synchronized(lock) {
        if (claimToken.isBlank() || partCount !in 1..255 || partIndex !in 0 until partCount) return@synchronized emptyList()
        val store = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val key = "claim:$claimToken"
        val state = runCatching { JSONObject(store.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val count = state.optInt("parts", partCount).coerceIn(1, 255)
        if (count != partCount) return@synchronized emptyList()
        state.put("shopId", shopId).put("jobId", jobId).put("parts", count)
        val field = if (delivery) "delivery" else "sent"
        val results = state.optJSONArray(field) ?: JSONArray().apply {
            repeat(count) { put(JSONObject.NULL) }
        }
        results.put(partIndex, success)
        state.put(field, results)

        val outcomes = mutableListOf<SmsResultOutcome>()
        val allResolved = (0 until count).all { !results.isNull(it) }
        val successes = (0 until count).count { !results.isNull(it) && results.optBoolean(it, false) }
        val failures = (0 until count).count { !results.isNull(it) && !results.optBoolean(it, false) }

        if (!delivery && allResolved && !state.optBoolean("sendReported", false)) {
            when {
                failures == 0 -> outcomes += SmsResultOutcome("sent")
                successes == 0 -> outcomes += SmsResultOutcome(
                    "failed", error ?: "The phone could not submit any SMS segment to the carrier.", retryable = true
                )
                else -> outcomes += SmsResultOutcome(
                    "unknown", "Some SMS segments may have been accepted; EMS will not automatically resend this message."
                )
            }
            state.put("sendReported", true)
        }

        if (delivery && allResolved && !state.optBoolean("deliveryReported", false)) {
            if (failures == 0) outcomes += SmsResultOutcome("delivered")
            else outcomes += SmsResultOutcome(
                "unknown", "The carrier did not confirm delivery for every SMS segment; the message was not automatically resent."
            )
            state.put("deliveryReported", true)
        }

        store.edit().putString(key, state.toString()).commit()
        outcomes
    }

    private fun JSONArray.isNull(index: Int): Boolean =
        index < 0 || index >= length() || get(index) == JSONObject.NULL
}

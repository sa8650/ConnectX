package com.connectx.gateway.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.connectx.gateway.data.CarrierBalanceConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Android API 26+; the user explicitly taps Refresh before any USSD call.
 * Always pin the call to the selected/active SIM subscription. Never fall back
 * to the phone's default SIM, which could incur charges on the wrong SIM. */
object SimUssdClient {
    suspend fun request(context: Context, subscriptionId: Int, ussdCode: String): String? {
        if (subscriptionId < 0 || !CarrierBalanceConfig.isSafeCode(ussdCode) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subscriptionId) ?: return null
        return withTimeoutOrNull(18_000L) {
            suspendCancellableCoroutine<String?> { continuation ->
                val callback = object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager, request: String, response: CharSequence
                    ) {
                        if (continuation.isActive) continuation.resume(response.toString())
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager, request: String, failureCode: Int
                    ) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                try {
                    manager.sendUssdRequest(ussdCode, callback, Handler(Looper.getMainLooper()))
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
}

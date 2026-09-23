package com.ems.connectx.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.ems.connectx.data.Api
import com.ems.connectx.data.Prefs
import kotlin.concurrent.thread

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(SmsSender.EXTRA_JOB) ?: return
        val shopId = intent.getStringExtra(SmsSender.EXTRA_SHOP) ?: return
        val parts = intent.getIntExtra("parts", 1)
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        val prefs = Prefs(context)
        val key = "sentparts:$jobId"
        val done = prefs.claimed("ok:$jobId")
        if (done) return
        val ok = resultCode == Activity.RESULT_OK
        if (!ok) {
            val err = when (resultCode) {
                SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio is off"
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SMS could not be sent"
                else -> "SMS failed ($resultCode)"
            }
            thread {
                runCatching { Api(prefs).report(shopId, jobId, false, err) }
            }
            return
        }
        val store = context.getSharedPreferences("connectx.parts", Context.MODE_PRIVATE)
        val count = store.getInt(key, 0) + 1
        store.edit().putInt(key, count).apply()
        if (count >= parts) {
            prefs.markClaimed("ok:$jobId")
            store.edit().remove(key).apply()
            thread { runCatching { Api(prefs).report(shopId, jobId, true) } }
        }
    }
}

package com.ems.connectx.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ems.connectx.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.gatewayEnabled && prefs.connections().any { it.setupComplete }) {
            GatewayService.start(context)
        }
    }
}

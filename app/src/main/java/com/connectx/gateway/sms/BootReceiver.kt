package com.connectx.gateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.connectx.gateway.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This receiver is exported for system broadcasts. Ignore explicit
        // intents from another app with no action or an unexpected action.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (prefs.gatewayEnabled && prefs.connections().any { it.setupComplete }) {
            GatewayService.start(context)
        }
    }
}

package com.connectx.gateway.sms

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.connectx.gateway.ConnectXApp
import com.connectx.gateway.MainActivity
import com.connectx.gateway.R
import com.connectx.gateway.data.Prefs
import java.util.Timer
import java.util.TimerTask

class GatewayService : Service() {
    private var timer: Timer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        val shop = prefs.active()?.shopName ?: "ConnectX"
        startForeground(42, notice("ConnectX is waiting for $shop SMS jobs."))
        timer?.cancel()
        timer = Timer("connectx-drain", true)
        timer?.schedule(object : TimerTask() {
            override fun run() {
                runCatching { QueueProcessor.drain(this@GatewayService) }
            }
        }, 2_000L, 25_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    private fun notice(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ConnectXApp.CHANNEL_GATEWAY)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("ConnectX · SMS delivery")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, GatewayService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}

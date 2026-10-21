package com.connectx.gateway.sms

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.connectx.gateway.ConnectXApp
import com.connectx.gateway.MainActivity
import com.connectx.gateway.R
import com.connectx.gateway.data.Prefs
import java.util.concurrent.Executors

class GatewayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var scheduled: Runnable? = null
    private var pollRunning = false
    private var pollAgainImmediately = false
    private var networkBackoffSeconds = 10L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        val shop = prefs.active()?.shopName ?: "your EMS shops"
        startForeground(42, notice("Connecting directly to EMS for $shop."))
        registerNetworkCallback()
        schedulePoll(0L)
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivity = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { schedulePoll(0L) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    handler.post { schedulePoll(0L) }
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
            .onFailure { networkCallback = null }
    }

    private fun schedulePoll(delayMillis: Long) {
        scheduled?.let(handler::removeCallbacks)
        val task = Runnable { runPoll() }
        scheduled = task
        handler.postDelayed(task, delayMillis.coerceAtLeast(0L))
    }

    private fun runPoll() {
        if (pollRunning) {
            pollAgainImmediately = true
            return
        }
        pollRunning = true
        worker.execute {
            val outcome = runCatching { QueueProcessor.drain(this@GatewayService) }
                .getOrElse { PollOutcome(networkFailures = 1) }
            val configuredPollSeconds = outcome.pollIntervalSeconds.coerceIn(10L, 300L)
            val delaySeconds = when {
                outcome.networkFailures > 0 -> {
                    networkBackoffSeconds = if (networkBackoffSeconds < configuredPollSeconds) {
                        configuredPollSeconds
                    } else {
                        (networkBackoffSeconds * 2).coerceAtMost(300L)
                    }
                    networkBackoffSeconds
                }
                outcome.jobsStarted > 0 -> {
                    networkBackoffSeconds = configuredPollSeconds
                    2L
                }
                outcome.reportsFlushed > 0 -> {
                    networkBackoffSeconds = configuredPollSeconds
                    5L
                }
                else -> {
                    networkBackoffSeconds = configuredPollSeconds
                    configuredPollSeconds
                }
            }
            handler.post {
                pollRunning = false
                val immediate = pollAgainImmediately
                pollAgainImmediately = false
                val label = if (outcome.networkFailures > 0) {
                    "EMS is offline; retrying with backoff (up to five minutes)."
                } else if (outcome.onlineShops > 0) {
                    "Direct to EMS · ${outcome.onlineShops} shop(s) online · checking again in ${delaySeconds}s."
                } else {
                    "Waiting for an EMS shop to be enabled."
                }
                val manager = getSystemService(android.app.NotificationManager::class.java)
                manager?.notify(42, notice(label))
                schedulePoll(if (immediate) 0L else delaySeconds * 1000L)
            }
        }
    }

    override fun onDestroy() {
        scheduled?.let(handler::removeCallbacks)
        networkCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun notice(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ConnectXApp.CHANNEL_GATEWAY)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("ConnectX · EMS SMS delivery")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, GatewayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}

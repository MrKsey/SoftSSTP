package com.example.sstp_flutter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * Tracks whether the device screen is currently interactive and reports every
 * change.
 *
 * The tunnel keeps running while the screen is off, but the work that only
 * exists for the user interface (the per-second traffic reporter, the foreground
 * notification refresh, the short socket read timeout) does not have to. Knowing
 * the screen state lets the service stop that work and wake it up again the
 * moment the screen comes back, which is what shadowsocks-android does with its
 * ACTION_SCREEN_ON/OFF receiver.
 *
 * ACTION_SCREEN_ON/OFF cannot be declared in the manifest, so the receiver is
 * registered for as long as the service lives.
 */
internal class ScreenStateMonitor(
    private val context: Context,
    private val onChanged: (Boolean) -> Unit,
) {
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** True while the screen is on (or the device has no power manager). */
    @Volatile
    var isInteractive: Boolean = powerManager?.isInteractive ?: true
        private set

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> update(true)
                Intent.ACTION_SCREEN_OFF -> update(false)
            }
        }
    }

    private fun update(interactive: Boolean) {
        if (interactive == isInteractive) return
        isInteractive = interactive
        onChanged(interactive)
    }

    /** Registers the receiver and seeds the current screen state. */
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            context.registerReceiver(receiver, filter)
            registered = true
        } catch (_: Exception) {
            // A missing registration must never break the tunnel; the monitor
            // then simply keeps reporting the state read at start().
        }
        // The receiver only reports later changes, so read the state now.
        isInteractive = powerManager?.isInteractive ?: true
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // Already unregistered.
        }
        registered = false
    }
}

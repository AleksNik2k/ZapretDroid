package com.zapret.droid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs: SharedPreferences = context.getSharedPreferences("zapret_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", false)) return

        // Check if VPN permission was already granted
        if (VpnService.prepare(context) != null) {
            Log.d("BootReceiver", "VPN permission not granted — skipping autostart")
            return
        }

        val strategyId = prefs.getString("strategy_id", "general") ?: "general"
        val telegramEnabled = prefs.getBoolean("telegram_enabled", true)

        val vpnIntent = Intent(context, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_START
            putExtra(ZapretVpnService.EXTRA_STRATEGY_ID, strategyId)
            putExtra(ZapretVpnService.EXTRA_TELEGRAM_ENABLED, telegramEnabled)
        }

        // Android 14+ may block foreground service start from background
        try {
            context.startForegroundService(vpnIntent)
            Log.d("BootReceiver", "Autostart VPN: strategy=$strategyId")
        } catch (e: Exception) {
            Log.w("BootReceiver", "ForegroundServiceStartNotAllowedException: ${e.message}")
            // Fallback: try regular startService (will fail to start foreground, but won't crash)
            try { context.startService(vpnIntent) } catch (_: Exception) {}
        }
    }
}

package com.zapret.droid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zapret.droid.MainActivity
import com.zapret.droid.R
import com.zapret.droid.proxy.*
import com.zapret.droid.strategies.Strategy
import com.zapret.droid.strategies.StrategyPresets
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream

class ZapretVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.zapret.droid.START"
        const val ACTION_STOP = "com.zapret.droid.STOP"
        const val EXTRA_STRATEGY_ID = "strategy_id"
        const val EXTRA_TELEGRAM_ENABLED = "telegram_enabled"
        const val CHANNEL_ID = "zapret_vpn"
        const val NOTIFICATION_ID = 1

        const val PREFS_NAME = "vpn_state"
        const val KEY_RUNNING = "is_running"
        const val KEY_STRATEGY = "current_strategy"
        const val KEY_LOGS = "log_buffer"

        const val ACTION_VPN_STATE_CHANGED = "com.zapret.droid.VPN_STATE_CHANGED"

        // In-memory state for this process
        private var _isRunning = false
        private var _currentStrategyId: String = ""

        val logBuffer = ArrayDeque<String>(500)
        var onLogUpdate: ((String) -> Unit)? = null

        fun isRunning(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)
        }

        fun currentStrategyId(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_STRATEGY, "") ?: ""
        }

        fun log(context: Context, msg: String) {
            logBuffer.addLast(msg)
            if (logBuffer.size > 500) logBuffer.removeFirst()

            // Persist logs
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_LOGS, "") ?: ""
            val updated = if (existing.isBlank()) msg else "$existing\n$msg"
            prefs.edit().putString(KEY_LOGS, updated).apply()

            onLogUpdate?.invoke(msg)
        }

        fun resetLogs(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_LOGS).apply()
            logBuffer.clear()
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tcpHandler: TcpProxyHandler? = null
    private var udpHandler: UdpProxyHandler? = null
    private var telegramProxy: TelegramWsProxy? = null
    private var tunnelJob: Job? = null
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ startForeground — САМЫЙ ПЕРВЫЙ вызов, до любой тяжёлой работы.
        // Android даёт 5 секунд на вызов startForeground из startForegroundService.
        createNotificationChannel()
        val placeholderNotif = buildPlaceholderNotification()
        try {
            startForeground(NOTIFICATION_ID, placeholderNotif)
        } catch (e: Exception) {
            Log.e("ZapretVpnService", "startForeground failed: ${e.message}")
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val strategyId = intent.getStringExtra(EXTRA_STRATEGY_ID) ?: "general"
                val telegramEnabled = intent.getBooleanExtra(EXTRA_TELEGRAM_ENABLED, true)
                val strategy = StrategyPresets.ALL.find { it.id == strategyId } ?: StrategyPresets.GENERAL
                startVpn(strategy, telegramEnabled)
            }
        }
        return START_STICKY
    }

    private fun startVpn(strategy: Strategy, telegramEnabled: Boolean) {
        // Обновляем уведомление с реальной информацией
        updateForegroundNotification(strategy.name)

        DomainLists.load(this)

        val builder = Builder()
            .setSession("ZapretDroid")
            .addAddress("10.0.0.1", 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .setBlocking(false)

        // Exclude our own app from VPN to prevent loops
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        vpnInterface = builder.establish() ?: run {
            log(this, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        _currentStrategyId = strategy.id
        _isRunning = true
        persistState()

        Log.d("ZapretVpnService", "VPN started: strategy=${strategy.id}, isRunning=$_isRunning")

        tcpHandler = TcpProxyHandler(this, strategy) { log(this, it) }
        udpHandler = UdpProxyHandler(this, strategy) { log(this, it) }

        if (telegramEnabled && strategy.telegramEnabled) {
            telegramProxy = TelegramWsProxy(this) { log(this, it) }
            scope.launch { telegramProxy?.start() }
        }

        log(this, "ZapretDroid started — strategy: ${strategy.name}")
        log(this, "TCP ports: ${strategy.tcpPorts.joinToString()}")
        log(this, "UDP ports: ${strategy.udpPorts.joinToString()}")

        tunnelJob = scope.launch { runTunnel() }
        cleanupJob = scope.launch {
            while (isActive) {
                delay(10_000)
                udpHandler?.cleanup()
            }
        }
    }

    private fun updateForegroundNotification(strategyName: String) {
        val notification = buildNotification(strategyName)
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("ZapretVpnService", "updateForegroundNotification failed: ${e.message}")
        }
    }

    private fun buildPlaceholderNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ZapretVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZapretDroid")
            .setContentText("Запуск...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .addAction(R.drawable.ic_stop, "Стоп", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun persistState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_RUNNING, _isRunning)
            putString(KEY_STRATEGY, _currentStrategyId)
            apply()
        }

        // Broadcast state change
        val broadcast = Intent(ACTION_VPN_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(KEY_RUNNING, _isRunning)
            putExtra(KEY_STRATEGY, _currentStrategyId)
        }
        sendBroadcast(broadcast)
    }

    private suspend fun runTunnel() {
        val pfd = vpnInterface ?: return
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32768)

        val writeTun: (ByteArray) -> Unit = { packet ->
            try {
                synchronized(outputStream) { outputStream.write(packet) }
            } catch (e: Exception) {
                log(this, "TUN write error: ${e.message}")
            }
        }

        log(this, "TUN tunnel running")
        while (!tunnelJob!!.isCancelled) {
            // Check if VPN interface is still valid (system may revoke it on Android 14+)
            if (vpnInterface == null) {
                Log.w("ZapretVpnService", "VPN interface null — stopping tunnel")
                stopVpn()
                return
            }
            val n = withContext(Dispatchers.IO) {
                try { inputStream.read(buf) } catch (_: Exception) { -1 }
            }
            if (n <= 0) {
                delay(1)
                continue
            }

            val packet = buf.copyOf(n)
            val version = PacketUtils.parseIpVersion(packet)
            if (version != 4) continue  // IPv6 not supported yet

            val protocol = PacketUtils.parseIpProtocol(packet)
            val ipHL = PacketUtils.parseIpHeaderLen(packet)
            if (ipHL > n) continue

            when (protocol) {
                6 -> {  // TCP
                    val dstPort = PacketUtils.parseTcpDstPort(packet, ipHL)
                    if (isTargetPort(dstPort, strategy = currentStrategy())) {
                        tcpHandler?.handlePacket(packet, writeTun)
                    }
                }
                17 -> {  // UDP
                    val dstPort = PacketUtils.parseUdpDstPort(packet, ipHL)
                    if (isUdpTargetPort(dstPort, strategy = currentStrategy())) {
                        udpHandler?.handlePacket(packet, writeTun)
                    }
                }
            }
        }
    }

    private fun isTargetPort(port: Int, strategy: Strategy?): Boolean {
        if (strategy == null) return true
        return port in strategy.tcpPorts
    }

    private fun isUdpTargetPort(port: Int, strategy: Strategy?): Boolean {
        if (strategy == null) return true
        return port in strategy.udpPorts || (port in 19294..19344) || (port in 50000..50100)
    }

    private fun currentStrategy(): Strategy? =
        StrategyPresets.ALL.find { it.id == _currentStrategyId }

    private fun stopVpn() {
        _isRunning = false
        tunnelJob?.cancel()
        cleanupJob?.cancel()
        tcpHandler?.shutdown()
        udpHandler?.shutdown()
        scope.launch { telegramProxy?.stop() }
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        persistState()

        log(this, "ZapretDroid stopped")
        Log.d("ZapretVpnService", "VPN stopped: isRunning=$_isRunning")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        Log.w("ZapretVpnService", "VPN revoked by system — stopping immediately")
        // Synchronous cleanup — no coroutines, system may kill process immediately
        _isRunning = false
        tunnelJob?.cancel()
        cleanupJob?.cancel()
        tcpHandler?.shutdown()
        udpHandler?.shutdown()
        telegramProxy?.stopSync()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        persistState()
        log(this, "ZapretDroid stopped (revoke)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        scope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Zapret VPN", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "DPI bypass VPN service" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(strategyName: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ZapretVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZapretDroid активен")
            .setContentText("Стратегия: $strategyName")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "Стоп", stopIntent)
            .setOngoing(true)
            .build()
    }
}

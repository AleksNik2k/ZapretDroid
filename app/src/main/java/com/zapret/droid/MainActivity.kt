package com.zapret.droid

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.zapret.droid.service.ZapretVpnService
import com.zapret.droid.proxy.ListRepositoryUpdater
import com.zapret.droid.proxy.DomainLists
import com.zapret.droid.strategies.Strategy
import com.zapret.droid.strategies.StrategyPresets
import com.zapret.droid.ui.LogAdapter
import com.zapret.droid.ui.StrategyAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var strategyAdapter: StrategyAdapter
    private lateinit var logAdapter: LogAdapter

    private lateinit var btnToggle: MaterialButton
    private lateinit var btnTelegramConnect: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var switchTelegram: SwitchMaterial
    private lateinit var switchAutostart: SwitchMaterial
    private lateinit var rvStrategies: RecyclerView
    private lateinit var rvLogs: RecyclerView
    private lateinit var statusIndicator: View
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var switchGameFilter: SwitchMaterial
    private lateinit var switchIpSetFilter: SwitchMaterial
    private lateinit var btnCustomLists: MaterialButton
    private lateinit var btnUpdateLists: MaterialButton

    private var selectedStrategy: Strategy = StrategyPresets.GENERAL

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startVpnServiceUnchecked()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("zapret_prefs", MODE_PRIVATE)

        initViews()
        loadPreferences()
        setupStrategyList()
        setupLogList()
        setupClickListeners()
        updateStatusUi()

        ZapretVpnService.onLogUpdate = { msg ->
            runOnUiThread {
                logAdapter.addLog(msg)
                rvLogs.scrollToPosition(0)
            }
        }
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        btnTelegramConnect = findViewById(R.id.btnTelegramConnect)
        tvStatus = findViewById(R.id.tvStatus)
        switchTelegram = findViewById(R.id.switchTelegram)
        switchAutostart = findViewById(R.id.switchAutostart)
        rvStrategies = findViewById(R.id.rvStrategies)
        rvLogs = findViewById(R.id.rvLogs)
        statusIndicator = findViewById(R.id.statusIndicator)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        switchGameFilter = findViewById(R.id.switchGameFilter)
        switchIpSetFilter = findViewById(R.id.switchIpSetFilter)
        btnCustomLists = findViewById(R.id.btnCustomLists)
        btnUpdateLists = findViewById(R.id.btnUpdateLists)
    }

    private fun loadPreferences() {
        val savedId = prefs.getString("strategy_id", "general") ?: "general"
        selectedStrategy = StrategyPresets.ALL.find { it.id == savedId } ?: StrategyPresets.GENERAL
        switchTelegram.isChecked = prefs.getBoolean("telegram_enabled", true)
        switchAutostart.isChecked = prefs.getBoolean("autostart", false)
        switchGameFilter.isChecked = prefs.getBoolean("game_filter_enabled", false)
        switchIpSetFilter.isChecked = prefs.getBoolean("ipset_filter_enabled", false)
    }

    private fun setupStrategyList() {
        strategyAdapter = StrategyAdapter(StrategyPresets.ALL) { strategy ->
            selectedStrategy = strategy
            prefs.edit().putString("strategy_id", strategy.id).apply()
        }
        strategyAdapter.selectedId = selectedStrategy.id
        rvStrategies.layoutManager = LinearLayoutManager(this)
        rvStrategies.adapter = strategyAdapter
    }

    private fun setupLogList() {
        logAdapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = logAdapter

        // Load existing logs
        if (ZapretVpnService.logBuffer.isNotEmpty()) {
            logAdapter.setLogs(ZapretVpnService.logBuffer.toList().reversed())
        }
    }

    private fun setupClickListeners() {
        btnToggle.setOnClickListener {
            if (ZapretVpnService.isRunning) {
                stopVpnService()
            } else {
                requestVpnPermission()
            }
        }

        switchTelegram.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("telegram_enabled", checked).apply()
            updateStatusUi()
        }

        btnTelegramConnect.setOnClickListener {
            openTelegramProxy()
        }

        switchAutostart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("autostart", checked).apply()
        }

        btnClearLogs.setOnClickListener {
            ZapretVpnService.clearLogs()
            logAdapter.clearLogs()
        }

        switchGameFilter.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("game_filter_enabled", checked).apply()
            DomainLists.load(this)
            ZapretVpnService.log(
                if (checked) "Фильтр игр/UDP включён" else "Фильтр игр/UDP выключен"
            )
        }

        switchIpSetFilter.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("ipset_filter_enabled", checked).apply()
            DomainLists.load(this)
            ZapretVpnService.log(
                if (checked) "IPSet-фильтр включён" else "IPSet-фильтр выключен"
            )
        }

        btnCustomLists.setOnClickListener { showCustomListsDialog() }
        btnUpdateLists.setOnClickListener { updateLists() }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun showCustomListsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun field(key: String, hint: String): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(prefs.getString(key, "") ?: "")
                minLines = 3
                gravity = android.view.Gravity.TOP
                container.addView(this)
            }

        val general = field("custom_general_domains", "Доп. General домены")
        val google = field("custom_google_domains", "Доп. Google домены")
        val exclude = field("custom_exclude_domains", "Исключённые домены")
        val ipset = field("custom_ipset", "Доп. IP/CIDR")
        val ipsetExclude = field("custom_ipset_exclude", "Исключённые IP/CIDR")

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("Пользовательские списки")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit()
                    .putString("custom_general_domains", general.text.toString())
                    .putString("custom_google_domains", google.text.toString())
                    .putString("custom_exclude_domains", exclude.text.toString())
                    .putString("custom_ipset", ipset.text.toString())
                    .putString("custom_ipset_exclude", ipsetExclude.text.toString())
                    .apply()
                DomainLists.load(this)
                ZapretVpnService.log("Пользовательские списки обновлены")
            }
            .show()
    }

    private fun updateLists() {
        btnUpdateLists.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            val downloaded = ListRepositoryUpdater.update(this@MainActivity)
            withContext(Dispatchers.Main) {
                btnUpdateLists.isEnabled = true
                ZapretVpnService.log(
                    if (downloaded > 0)
                        "Обновлено списков: $downloaded"
                    else
                        "Не удалось обновить списки"
                )
            }
        }
    }

    private fun startVpnService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startVpnServiceUnchecked()
    }

    private fun startVpnServiceUnchecked() {
        ZapretVpnService.log("Запуск стратегии: ${selectedStrategy.name} (${selectedStrategy.id})")
        val intent = Intent(this, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_START
            putExtra(ZapretVpnService.EXTRA_STRATEGY_ID, selectedStrategy.id)
            putExtra(ZapretVpnService.EXTRA_TELEGRAM_ENABLED, switchTelegram.isChecked)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatusUi()
    }

    private fun stopVpnService() {
        val intent = Intent(this, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_STOP
        }
        startService(intent)
        updateStatusUi()
    }

    private fun updateStatusUi() {
        val running = ZapretVpnService.isRunning
        btnToggle.text = if (running) "Остановить" else "Запустить"
        tvStatus.text = if (running) "Активен — ${ZapretVpnService.currentStrategyId}" else "Остановлен"
        statusIndicator.setBackgroundResource(
            if (running) R.drawable.indicator_active else R.drawable.indicator_inactive
        )
        rvStrategies.alpha = if (running) 0.5f else 1.0f
        rvStrategies.isEnabled = !running
        btnTelegramConnect.visibility = if (running && switchTelegram.isChecked)
            android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openTelegramProxy() {
        val deepLink = "tg://proxy?server=127.0.0.1&port=1443&secret="
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(deepLink)))
        } catch (_: Exception) {
            // Telegram не установлен — открыть t.me ссылку в браузере
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://t.me/proxy?server=127.0.0.1&port=1443&secret=")))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        ZapretVpnService.onLogUpdate = null
    }
}

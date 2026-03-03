package com.example.talking_bill

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.talking_bill.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import androidx.core.app.ActivityCompat

/**
 * Main activity for the Talking Bill application.
 * This app monitors notifications from banking apps and announces money transactions.
 * Features:
 * - Notification monitoring and filtering
 * - Text-to-speech for transaction announcements
 * - Background service for continuous monitoring
 * - Battery optimization handling
 * - Notification history management
 * - Automatic service start after permissions are granted (no manual switch)
 */
class MainActivity : AppCompatActivity() {
    // UI Components
    private lateinit var adapter: NotificationAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var binding: ActivityMainBinding
    private lateinit var statusText: TextView
    private lateinit var keywordJsonEditText: EditText
    private lateinit var enableButton: Button
    private lateinit var clearButton: Button
    private lateinit var resetButton: Button
    private lateinit var repairButton: Button
    private lateinit var saveToggle: Switch
    private lateinit var filterToggle: Switch
    private lateinit var notificationsRecyclerView: RecyclerView
    private lateinit var settingsTabContent: ScrollView
    private lateinit var keywordsTabContent: ScrollView
    private lateinit var notificationsTabContent: View
    private var batteryOptimizationDialog: AlertDialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText: TextView
    private val REQUEST_CODE_POST_NOTIFICATIONS = 1001

    /**
     * Broadcast receiver for handling notification updates from the service.
     * Receives notifications and updates the UI accordingly.
     */
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationListenerService.ACTION_NOTIFICATION_RECEIVED) {
                val notificationData = intent.getStringExtra(NotificationListenerService.EXTRA_NOTIFICATION_DATA)
                if (notificationData != null) {
                    adapter.addNotification(notificationData)
                    binding.notificationsRecyclerView.scrollToPosition(0)
                }
            }
        }
    }

    companion object {
        // SharedPreferences keys
        private const val PREFS_NAME = "NotificationPrefs"
        private const val KEY_SAVE_ENABLED = "save_enabled"
        private const val KEY_FILTER_ENABLED = "filter_enabled"
        private const val KEY_BACKGROUND_ENABLED = "background_enabled"
        private const val KEY_SERVICE_STATE = "service_state"
        private const val KEY_SPEECH_PREFIX = "speech_prefix"
        private const val KEY_SPEECH_CURRENCY = "speech_currency"
        private const val KEY_PENDING_CHANNEL_DELETE = "pending_channel_delete"
        private const val KEY_LAST_REPAIRED_VERSION_CODE = "last_repaired_version_code"
        private const val KEY_LAST_DESTROY_TIMESTAMP = "last_destroy_timestamp"
        private const val KEY_LAST_REPAIRED_UPDATE_TIME = "last_repaired_update_time"
        private const val NOTIFICATION_LOG_FILE = "notification_log.txt"
        private const val DEFAULT_SPEECH_PREFIX = "đã nhận"
        private const val DEFAULT_SPEECH_CURRENCY = "đồng"
        private const val RESET_INITIAL_DELAY_MS = 1000L
        private const val RESET_RESTART_DELAY_MS = 3000L
        private const val AUTO_REPAIR_AFTER_DESTROY_MS = 10_000L
    }

    /**
     * Initializes the activity and sets up all necessary components.
     * - Initializes UI components
     * - Sets up notification monitoring
     * - Configures user preferences
     * - Handles battery optimization
     * - Requests notification permissions (Android 13+)
     * - Service starts automatically after all permissions are granted
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize loading views
        loadingOverlay = binding.loadingOverlay
        loadingText = binding.loadingText

        // Show loading during initialization
        showLoading("Initializing app...")

        // Check and request battery optimization exemption
        checkBatteryOptimization()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (shouldAutoRepairOnStartup()) {
            scheduleAutoRepairAfterStartup()
        }
        
        // Check if we need to delete the notification channel
        if (prefs.getBoolean(KEY_PENDING_CHANNEL_DELETE, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    notificationManager.deleteNotificationChannel(NotificationListenerService.NOTIFICATION_CHANNEL_ID)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error deleting notification channel on startup", e)
                }
            }
            prefs.edit().remove(KEY_PENDING_CHANNEL_DELETE).apply()
        }
        
        // Set default values for speech settings if not already set
        if (!prefs.contains(KEY_SPEECH_PREFIX)) {
            prefs.edit().putString(KEY_SPEECH_PREFIX, DEFAULT_SPEECH_PREFIX).apply()
        }
        if (!prefs.contains(KEY_SPEECH_CURRENCY)) {
            prefs.edit().putString(KEY_SPEECH_CURRENCY, DEFAULT_SPEECH_CURRENCY).apply()
        }
        
        // Set the default values in the EditText fields
        binding.prefixEditText.setText(prefs.getString(KEY_SPEECH_PREFIX, DEFAULT_SPEECH_PREFIX))
        binding.currencyEditText.setText(prefs.getString(KEY_SPEECH_CURRENCY, DEFAULT_SPEECH_CURRENCY))

        initializeViews()
        setupAdapters()
        setupTabs()
        setupClickListeners()
        setupToggles()
        registerBroadcastReceiver()
        loadConfig()
        checkNotificationAccess()
        startServiceIfNeeded()
        updateUI()
        hideLoading()
        requestNotificationPermissionIfNeeded()
    }

    /**
     * Returns true when startup should trigger auto-repair.
     */
    private fun shouldAutoRepairOnStartup(): Boolean {
        val currentPackageUpdateTime = getPackageLastUpdateTime()
        val lastRepairedUpdateTime = prefs.getLong(KEY_LAST_REPAIRED_UPDATE_TIME, -1L)
        val lastDestroyTimestamp = prefs.getLong(KEY_LAST_DESTROY_TIMESTAMP, -1L)
        val now = System.currentTimeMillis()

        // If no tracked version exists yet (fresh install/reinstall or migrated prefs),
        // run one startup repair and then persist current version in scheduler.

        val isUpdatedPackage = currentPackageUpdateTime > 0 && currentPackageUpdateTime != lastRepairedUpdateTime
        val isLongAfterDestroy = lastDestroyTimestamp > 0 && (now - lastDestroyTimestamp) > AUTO_REPAIR_AFTER_DESTROY_MS
        Log.d(
            "MainActivity",
            "Auto-repair check: updatedPackage=$isUpdatedPackage, " +
                "longAfterDestroy=$isLongAfterDestroy, lastDestroy=$lastDestroyTimestamp, now=$now"
        )
        return isUpdatedPackage || isLongAfterDestroy
    }

    /**
     * Schedules repair on startup after install/update and records current version first
     * to avoid repeated loops if startup gets interrupted.
     */
    private fun scheduleAutoRepairAfterStartup() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_REPAIRED_VERSION_CODE, getCurrentVersionCode())
            .putLong(KEY_LAST_REPAIRED_UPDATE_TIME, getPackageLastUpdateTime())
            .putLong(KEY_LAST_DESTROY_TIMESTAMP, now)
            .apply()
        repairApp()
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to read app version code", e)
            0L
        }
    }

    private fun getPackageLastUpdateTime(): Long {
        return try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to read package lastUpdateTime", e)
            0L
        }
    }

    /**
     * Initializes all view references from binding.
     * This function sets up all UI components used throughout the activity.
     */
    private fun initializeViews() {
        statusText = binding.statusText
        keywordJsonEditText = binding.keywordJsonEditText
        enableButton = binding.enableButton
        clearButton = binding.clearButton
        resetButton = binding.resetButton
        repairButton = binding.repairButton
        saveToggle = binding.saveToggle
        filterToggle = binding.filterToggle
        notificationsRecyclerView = binding.notificationsRecyclerView
        settingsTabContent = binding.settingsTabContent
        keywordsTabContent = binding.keywordsTabContent
        notificationsTabContent = binding.notificationsTabContent
    }

    private fun setupTabs() {
        val tabLayout = binding.mainTabLayout
        if (tabLayout.tabCount == 0) {
            tabLayout.addTab(tabLayout.newTab().setText("Settings"))
            tabLayout.addTab(tabLayout.newTab().setText("Keywords"))
            tabLayout.addTab(tabLayout.newTab().setText("Notifications"))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        showTab(tabLayout.selectedTabPosition.coerceAtLeast(0))
    }

    private fun showTab(position: Int) {
        settingsTabContent.visibility = if (position == 0) View.VISIBLE else View.GONE
        keywordsTabContent.visibility = if (position == 1) View.VISIBLE else View.GONE
        notificationsTabContent.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    /**
     * Sets up the RecyclerView adapter for displaying notifications.
     * Configures the layout manager and click listeners.
     */
    private fun setupAdapters() {
        adapter = NotificationAdapter()
        notificationsRecyclerView.layoutManager = LinearLayoutManager(this)
        notificationsRecyclerView.adapter = adapter
    }

    /**
     * Sets up click listeners for all buttons in the UI.
     * Handles navigation to settings, clearing notifications, and resetting the app.
     */
    private fun setupClickListeners() {
        enableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        clearButton.setOnClickListener {
            showClearConfirmationDialog()
        }

        resetButton.setOnClickListener {
            showResetConfirmationDialog()
        }

        repairButton.setOnClickListener {
            showRepairConfirmationDialog()
        }

        binding.saveSpeechButton.setOnClickListener {
            saveSpeechSettings()
        }

        binding.saveKeywordJsonButton.setOnClickListener {
            saveKeywordJson()
        }

        binding.resetKeywordJsonButton.setOnClickListener {
            resetKeywordJsonToDefault()
        }
    }

    /**
     * Saves the speech customization settings to SharedPreferences.
     * Updates the prefix and currency text for transaction announcements.
     */
    private fun saveSpeechSettings() {
        val prefix = binding.prefixEditText.text.toString()
        val currency = binding.currencyEditText.text.toString()
        prefs.edit()
            .putString(KEY_SPEECH_PREFIX, prefix)
            .putString(KEY_SPEECH_CURRENCY, currency)
            .apply()
        Toast.makeText(this, "Speech settings saved", Toast.LENGTH_SHORT).show()
    }

    /**
     * Sets up toggle switches for save and filter functionality.
     * Configures the initial state and change listeners for each toggle.
     */
    private fun setupToggles() {
        // Setup save toggle
        saveToggle.isChecked = prefs.getBoolean(KEY_SAVE_ENABLED, true)
        saveToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_ENABLED, isChecked).apply()
            showCustomToast(
                if (isChecked) "Notifications will be saved" 
                else "Notifications will not be saved", 
                isChecked
            )
        }

        // Setup filter toggle
        filterToggle.isChecked = prefs.getBoolean(KEY_FILTER_ENABLED, true)
        filterToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_FILTER_ENABLED, isChecked).apply()
            showCustomToast(
                if (isChecked) "Filter and voice enabled" 
                else "Filter and voice disabled",
                isChecked
            )
        }
    }

    /**
     * Registers the broadcast receiver for notification updates.
     * This allows the activity to receive notification events from the service.
     */
    private fun registerBroadcastReceiver() {
        val filter = IntentFilter(NotificationListenerService.ACTION_NOTIFICATION_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(notificationReceiver, filter)
        }
    }

    /**
     * Starts the service if permission is granted and the service switch was on.
     * This ensures the service is running when the app starts if it was previously enabled.
     */
    private fun startServiceIfNeeded() {
        if (isNotificationServiceEnabled() && prefs.getBoolean(KEY_SERVICE_STATE, false)) {
            startForegroundService()
        }
    }

    /**
     * Cleans up resources when the activity is destroyed.
     * Unregisters the broadcast receiver and handles the battery optimization dialog.
     */
    override fun onDestroy() {
        super.onDestroy()
        batteryOptimizationDialog?.dismiss()
        batteryOptimizationDialog = null
        prefs.edit().putLong(KEY_LAST_DESTROY_TIMESTAMP, System.currentTimeMillis()).apply()
        
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        
        if (prefs.getBoolean(KEY_BACKGROUND_ENABLED, true)) {
            startForegroundService()
        }
    }

    /**
     * Handles activity resume events.
     * Updates the UI and checks battery optimization status.
     */
    override fun onResume() {
        super.onResume()
        updateUI()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                batteryOptimizationDialog?.dismiss()
                batteryOptimizationDialog = null
            } else {
                checkBatteryOptimization()
            }
        }
        
        if (isNotificationServiceEnabled()) {
            // Auto-enable service once notification access is granted
            if (!prefs.getBoolean(KEY_SERVICE_STATE, false)) {
                prefs.edit().putBoolean(KEY_SERVICE_STATE, true).apply()
            }
            startForegroundService()
            loadNotifications()
        } else {
            stopForegroundService()
            prefs.edit().putBoolean(KEY_SERVICE_STATE, false).apply()
        }

    }

    /**
     * Updates the UI based on notification access status.
     * Shows/hides relevant buttons and updates status text.
     */
    private fun updateUI() {
        val enabled = isNotificationServiceEnabled()
        if (enabled) {
            statusText.text = "Notification access is enabled"
            enableButton.visibility = View.GONE
            clearButton.visibility = View.VISIBLE
            resetButton.visibility = View.VISIBLE
            repairButton.visibility = View.VISIBLE
            loadNotifications()
        } else {
            statusText.text = "Please enable notification access"
            enableButton.visibility = View.VISIBLE
            clearButton.visibility = View.GONE
            resetButton.visibility = View.GONE
            repairButton.visibility = View.GONE
        }
    }

    /**
     * Checks if notification access is enabled for the app.
     * @return Boolean indicating if notification access is enabled
     */
    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null && !flat.isEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                if (name.contains(pkgName)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Loads notifications from the log file and updates the RecyclerView.
     * Handles file reading and parsing of stored notifications.
     */
    private fun loadNotifications() {
        lifecycleScope.launch {
            try {
                val notifications = withContext(Dispatchers.IO) {
                    val list = mutableListOf<String>()
                    try {
                        openFileInput(NOTIFICATION_LOG_FILE).use { input ->
                            val content = input.bufferedReader().readText()
                            content.split("---").forEach { notification ->
                                val trimmed = notification.trim()
                                if (trimmed.isNotEmpty()) {
                                    list.add(trimmed)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // No notification log file found
                    }
                    list
                }
                adapter.updateNotifications(notifications.asReversed())
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading notifications", e)
                showCustomToast("Error loading notifications", false)
            }
        }
    }

    /**
     * Shows a confirmation dialog before clearing all notifications.
     */
    private fun showClearConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Notifications")
            .setMessage("Are you sure you want to delete all notifications?")
            .setPositiveButton("Clear") { _, _ -> clearAllNotifications() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Clears all notifications from both the UI and storage.
     */
    private fun clearAllNotifications() {
        try {
            adapter.clearNotifications()
            
            try {
                openFileOutput(NOTIFICATION_LOG_FILE, Context.MODE_PRIVATE).use { output ->
                    output.write("".toByteArray())
                }
                showCustomToast("All notifications cleared", true)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error clearing notification file", e)
                showCustomToast("Error clearing notifications", false)
                loadNotifications()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error clearing notifications", e)
            showCustomToast("Error clearing notifications", false)
        }
    }

    /**
     * Loads app configuration and shows it in the JSON editor.
     */
    private fun loadConfig() {
        try {
            val appConfig = AppConfigStore.load(this)
            keywordJsonEditText.setText(AppConfigStore.toPrettyJson(appConfig))
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading app configuration", e)
            showCustomToast("Error loading configuration", false)
        }
    }

    private fun saveKeywordJson() {
        val rawJson = keywordJsonEditText.text.toString().trim()
        if (rawJson.isEmpty()) {
            showCustomToast("JSON cannot be empty", false)
            return
        }

        val parsedConfig = AppConfigStore.parseJson(rawJson)
        if (parsedConfig == null) {
            showCustomToast("Invalid JSON format", false)
            return
        }

        if (parsedConfig.isEmpty()) {
            showCustomToast("Config cannot be empty", false)
            return
        }

        if (AppConfigStore.save(this, parsedConfig)) {
            keywordJsonEditText.setText(AppConfigStore.toPrettyJson(parsedConfig))
            notifyServiceConfigUpdated()
            showCustomToast("Keyword config saved", true)
        } else {
            showCustomToast("Failed to save config", false)
        }
    }

    private fun resetKeywordJsonToDefault() {
        val defaultConfig = AppConfigStore.loadDefault(this)
        if (defaultConfig.isEmpty()) {
            showCustomToast("Default config is empty or missing", false)
            return
        }

        if (AppConfigStore.save(this, defaultConfig)) {
            keywordJsonEditText.setText(AppConfigStore.toPrettyJson(defaultConfig))
            notifyServiceConfigUpdated()
            showCustomToast("Keyword config reset to default", true)
        } else {
            showCustomToast("Failed to save config", false)
        }
    }

    private fun notifyServiceConfigUpdated() {
        if (!isNotificationServiceEnabled()) return

        try {
            val intent = Intent(this, NotificationListenerService::class.java).apply {
                action = NotificationListenerService.ACTION_RELOAD_CONFIG
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error notifying service about config update", e)
        }
    }

    /**
     * Checks and requests notification access if needed.
     * Shows a dialog to guide the user through enabling notification access.
     */
    private fun checkNotificationAccess() {
        if (!isNotificationServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Notification Access Required")
                .setMessage("This app needs notification access to work. Please enable it in the next screen.")
                .setPositiveButton("Enable") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Checks and requests battery optimization exemption.
     * Shows a dialog to guide the user through disabling battery optimization.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                batteryOptimizationDialog?.dismiss()
                
                batteryOptimizationDialog = AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("This app needs to run in the background to monitor notifications. Please disable battery optimization for this app.")
                    .setPositiveButton("Disable Optimization") { _, _ ->
                        try {
                            val intent = Intent().apply {
                                action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                data = android.net.Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching battery optimization settings", e)
                            showCustomToast("Failed to open battery settings", false)
                        }
                    }
                    .setNegativeButton("Not Now") { _, _ ->
                        batteryOptimizationDialog?.dismiss()
                        batteryOptimizationDialog = null
                    }
                    .setCancelable(false)
                    .create()
                
                batteryOptimizationDialog?.show()
            } else {
                batteryOptimizationDialog?.dismiss()
                batteryOptimizationDialog = null
            }
        }
    }

    /**
     * Starts the foreground service for notification monitoring.
     * Checks battery optimization status before starting.
     */
    private fun startForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    // Don't block startup; just warn + prompt. Some devices will still run fine without exemption.
                    showCustomToast("For best reliability, disable battery optimization for this app", false)
                    checkBatteryOptimization()
                }
            }

            // Persist that the user wants the service running (used by BootReceiver)
            prefs.edit().putBoolean(KEY_SERVICE_STATE, true).apply()

            val serviceIntent = Intent(this, NotificationListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting foreground service", e)
            showCustomToast("Failed to start service", false)
        }
    }

    /**
     * Stops the foreground service.
     */
    private fun stopForegroundService() {
        try {
            prefs.edit().putBoolean(KEY_SERVICE_STATE, false).apply()
            val serviceIntent = Intent(this, NotificationListenerService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping foreground service", e)
        }
    }

    /**
     * Shows a custom styled toast message.
     * @param message The message to display
     * @param isSuccess Whether the message indicates success or failure
     */
    private fun showCustomToast(message: String, isSuccess: Boolean) {
        try {
            val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            val view = toast.view
            view?.setBackgroundColor(if (isSuccess) getColor(R.color.toast_success) else getColor(R.color.toast_error))
            val text = view?.findViewById<TextView>(android.R.id.message)
            text?.setTextColor(Color.WHITE)
            text?.textSize = 16f
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
        } catch (e: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shows a confirmation dialog before resetting the app.
     */
    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset App")
            .setMessage("This will reset all settings and clear all data. The app will restart. Are you sure?")
            .setPositiveButton("Reset") { _, _ -> showResetFinalConfirmationDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetFinalConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Are you sure?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Yes, Reset App") { _, _ -> resetApp() }
            .setNegativeButton("No", null)
            .show()
    }

    /**
     * Shows a confirmation dialog before repairing the app.
     * Repair restarts internal app/service state without deleting saved data.
     */
    private fun showRepairConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Repair App")
            .setMessage("This will repair app/service state and restart the app without deleting saved data. Continue?")
            .setPositiveButton("Repair") { _, _ -> repairApp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Resets the app to its initial state.
     * Clears all data, stops the service, and restarts the app.
     */
    private fun resetApp() {
        performResetOrRepair(clearSavedData = true)
    }

    /**
     * Repairs app/service state while preserving all saved user data.
     */
    private fun repairApp() {
        performResetOrRepair(clearSavedData = false)
    }

    /**
     * Performs reset/repair flow and restarts the app.
     * @param clearSavedData true for full reset, false for repair (keep saved data)
     */
    private fun performResetOrRepair(clearSavedData: Boolean) {
        showLoading(if (clearSavedData) "Resetting app..." else "Repairing app...")

        try {
            stopForegroundService()

            mainHandler.postDelayed({
                try {
                    prefs.edit().putBoolean(KEY_PENDING_CHANNEL_DELETE, true).apply()

                    if (clearSavedData) {
                        prefs.edit().clear().apply()
                        deleteFile(NOTIFICATION_LOG_FILE)
                        AppConfigStore.clearOverride(this)

                        saveToggle.isChecked = true
                        filterToggle.isChecked = true

                        prefs.edit()
                            .putString(KEY_SPEECH_PREFIX, DEFAULT_SPEECH_PREFIX)
                            .putString(KEY_SPEECH_CURRENCY, DEFAULT_SPEECH_CURRENCY)
                            .putBoolean(KEY_SAVE_ENABLED, true)
                            .putBoolean(KEY_FILTER_ENABLED, true)
                            .putBoolean(KEY_BACKGROUND_ENABLED, true)
                            .apply()

                        binding.prefixEditText.setText(DEFAULT_SPEECH_PREFIX)
                        binding.currencyEditText.setText(DEFAULT_SPEECH_CURRENCY)

                        adapter.clearNotifications()
                    }

                    val componentName = ComponentName(this, NotificationListenerService::class.java)

                    packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )

                    packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )

                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

                    mainHandler.postDelayed({
                        hideLoading()
                        restartApp()
                    }, RESET_RESTART_DELAY_MS)

                } catch (e: Exception) {
                    Log.e("MainActivity", if (clearSavedData) "Error during reset" else "Error during repair", e)
                    hideLoading()
                    showCustomToast(
                        if (clearSavedData) "Error resetting app" else "Error repairing app",
                        false
                    )
                }
            }, RESET_INITIAL_DELAY_MS)

        } catch (e: Exception) {
            Log.e("MainActivity", if (clearSavedData) "Error stopping service during reset" else "Error stopping service during repair", e)
            hideLoading()
            showCustomToast("Error stopping service", false)
        }
    }

    /**
     * Restarts the app completely.
     * Clears all activities and starts fresh.
     */
    private fun restartApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            finishAffinity()
            startActivity(intent)
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error restarting app", e)
            finish()
        }
    }

    /**
     * Shows the loading overlay with a custom message.
     * @param message The message to display in the loading overlay
     */
    private fun showLoading(message: String = "Loading...") {
        loadingText.text = message
        loadingOverlay.visibility = View.VISIBLE
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
    }

    /**
     * Hides the loading overlay.
     */
    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission Required")
                        .setMessage("This app needs notification permission to show important alerts and run reliably in the background.")
                        .setPositiveButton("Allow") { _, _ ->
                            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showCustomToast("Notification permission granted", true)
                startForegroundService()
            } else {
                showCustomToast("Notification permission denied. Some features may not work.", false)
            }
        }
    }
}
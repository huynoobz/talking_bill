package com.example.talking_bill

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService as AndroidNotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service that listens for notifications and processes them based on configuration.
 * Handles text-to-speech functionality for money amounts and notification filtering.
 */
class NotificationListenerService : AndroidNotificationListenerService(), TextToSpeech.OnInitListener {
    private var appConfig: AppConfig? = null
    private var textToSpeech: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // Log.d(TAG, "Service onCreate called")
        loadConfig()
        createNotificationChannel()
        startForeground()
        initTextToSpeech()
    }

    /**
     * Initialize TextToSpeech engine.
     */
    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(this, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        try {
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale("vi"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported")
                }
                // Set slower speech rate (0.8 is slower than default 1.0)
                textToSpeech?.setSpeechRate(0.8f)
            } else {
                Log.e(TAG, "TextToSpeech initialization failed with status: $status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in TextToSpeech initialization", e)
        }
    }

    /**
     * Speak the money amount using TextToSpeech.
     */
    private fun speakAmount(amount: String) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefix = prefs.getString(KEY_SPEECH_PREFIX, "Đã nhận") ?: "Đã nhận"
            val currency = prefs.getString(KEY_SPEECH_CURRENCY, "đồng") ?: "đồng"
            val text = "$prefix, $amount, $currency"
            
            textToSpeech?.let { tts ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MONEY_ANNOUNCEMENT")
                    } else {
                        @Suppress("DEPRECATION")
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error speaking amount", e)
                }
            } ?: run {
                Log.e(TAG, "TextToSpeech is null")
                // Try to reinitialize TextToSpeech
                initTextToSpeech()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing speech", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Log.d(TAG, "Service onStartCommand called")
        // Reload config to ensure we have the latest data
        loadConfig()
        
        // If service was killed, restart it
        if (intent == null) {
            // Log.d(TAG, "Service was killed, restarting...")
            val restartIntent = Intent(applicationContext, NotificationListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
        
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Log.d(TAG, "Notification listener disconnected")
    }

    /**
     * Load app configuration from internal override or assets fallback.
     */
    private fun loadConfig() {
        try {
            appConfig = AppConfigStore.load(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading config", e)
            appConfig = null
            // Retry config load after delay to handle transient asset read failures.
            mainHandler.postDelayed({
                loadConfig()
            }, 5000) // Retry after 5 seconds
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        
        // Log.d(TAG, "Notification received")
        
        // Get notification details
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""

        // Build full content
        val fullContent = buildString {
            append("Title: $title\n")
            append("Text: $text\n")
            append("SubText: $subText\n")
            append("Additional Info:\n")
            extras.keySet().forEach { key ->
                append("$key: ${getBundleValue(extras, key)}\n")
            }
        }

        // Check if we should collect this notification
        val (shouldCollect, moneyAmount) = shouldCollectNotification(packageName, fullContent)
        
        if (shouldCollect) {
            // Get current timestamp
            val timestamp = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val formattedDate = dateFormat.format(Date(timestamp))

            // Format the notification data with package name
            val formattedData = buildString {
                append("$formattedDate - $packageName: ${moneyAmount.ifEmpty { "0" }}\n")
                append(fullContent)
            }

            // Check if saving is enabled
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isSaveEnabled = prefs.getBoolean(KEY_SAVE_ENABLED, true)

            // Only save to file if saving is enabled
            if (isSaveEnabled) {
                if (saveToFile(formattedData)) {
                    // Broadcast the new notification to update UI
                    broadcastNotification(formattedData)
                }
            } else {
                // If saving is disabled, just broadcast for live display without saving
                broadcastNotification(formattedData)
            }
        }
    }
    
    /**
     * Check if a notification should be collected based on package name and content.
     * @return Pair of (should collect, money amount)
     */
    private fun shouldCollectNotification(packageName: String, content: String): Pair<Boolean, String> {
        // Log.d(TAG, "Starting notification filter for package: $packageName")
        
        // Check if filtering is enabled
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFilterEnabled = prefs.getBoolean(KEY_FILTER_ENABLED, true)
        // Log.d(TAG, "Filter enabled: $isFilterEnabled")

        // If filtering is disabled, collect all notifications with money = 0
        if (!isFilterEnabled) {
            //Log.d(TAG, "Filtering disabled - collecting all notifications")
            return Pair(true, "0")
        }

        // Defensive: Try to load config if null
        if (appConfig == null) {
            //Log.d(TAG, "AppConfig is null, attempting to reload")
            loadConfig()
        }

        if (appConfig == null) {
            //Log.e(TAG, "Failed to load AppConfig, rejecting notification")
            return Pair(false, "")
        }
        
        // Stage 1: Check if package name contains any app name from config
        val lowerPackageName = packageName.lowercase()
        //Log.d(TAG, "Stage 1: Checking package name '$lowerPackageName' against configured apps")
        
        val matchingApp = appConfig?.entries?.find { (appName, _) ->
            lowerPackageName.contains(appName.lowercase())
        }

        if (matchingApp == null) {
            //Log.d(TAG, "Stage 1: No matching app found in config")
            return Pair(false, "")
        }
        //Log.d(TAG, "Stage 1: Found matching app: ${matchingApp.key}")

        // Stage 2: Check if notification content contains any of the app's keywords
        val lowerContent = normalizeContent(content).lowercase()
        //Log.d(TAG, "Stage 2: Checking content against keywords: ${matchingApp.value.receive_keyword}")
        //Log.d(TAG, "${lowerContent}")
        
        val hasKeyword = matchingApp.value.receive_keyword.any { keyword ->
            val lowerKeyword = keyword.lowercase()
            val contains = lowerContent.contains(lowerKeyword)
            //Log.d(TAG, "Stage 2: Checking keyword '$lowerKeyword' - Found: $contains")
            contains
        }

        if (!hasKeyword) {
            //Log.d(TAG, "Stage 2: No matching keywords found in content")
            return Pair(false, "")
        }
        //Log.d(TAG, "Stage 2: Found matching keyword in content")

        // Stage 3: Check money regex pattern
        val mRegexList = matchingApp.value.m_regex
        //Log.d(TAG, "Stage 3: Checking money regex patterns: $mRegexList")
        
        if (mRegexList.isNotEmpty()) {
            var matchResult: MatchResult? = null
            var matchedText: String? = null

            // Try each regex pattern until we find a match
            for (mRegex_ in mRegexList) {
                // Extract prefix, separator and postfix from m_regex
                val mRegex = mRegex_.lowercase()
                val prefix = if (mRegex.startsWith("0")) "" else mRegex.substringBefore("0")
                val postfix = if (mRegex.endsWith("0")) "" else mRegex.substringAfterLast("0")
                val separator = mRegex.substringAfter("0").substringBeforeLast("0")
                val escapedSeparator = Regex.escape(separator)

                // Match numeric groups and treat separators like '.' as literal.
                val numberPattern = if (separator.isEmpty()) {
                    "\\d+"
                } else {
                    "\\d+(?:$escapedSeparator\\d+)*"
                }
                val fullPattern = if (prefix.isEmpty() && postfix.isEmpty()) {
                    numberPattern
                } else {
                    "${prefix}${numberPattern}${postfix}"
                }

                //Log.d(TAG, "Stage 3: Testing pattern: $fullPattern")
                val regex = fullPattern.toRegex()
                val result = regex.find(lowerContent)
                
                if (result != null) {
                    matchResult = result
                    matchedText = result.value
                    //Log.d(TAG, "Stage 3: Found match: $matchedText")
                    break
                }
            }
            
            if (matchResult == null) {
                //Log.d(TAG, "Stage 3: No money amount found matching any pattern")
                return Pair(false, "")
        }

            // Extract money amount as integer
            val cleanedText = matchedText!!.replace(Regex("[^0-9.,]"), "")
            // Convert to integer by removing decimal separators
            val moneyAmount = cleanedText.replace(Regex("[,.]"), "").toIntOrNull()
            
            if (moneyAmount == null) {
                //Log.d(TAG, "Stage 3: Failed to parse money amount from: $cleanedText")
                return Pair(false, "")
            }

            //Log.d(TAG, "Stage 3: Successfully extracted money amount: $moneyAmount")

            // Only speak the amount if filtering is enabled
            if (isFilterEnabled) {
                speakAmount(moneyAmount.toString())
            }
            return Pair(true, moneyAmount.toString())
        }

        //Log.d(TAG, "No money regex patterns defined, collecting notification with amount 0")
        return Pair(true, "0")
    }
    
    private fun normalizeContent(content: String): String {
        return content.replace('\u00A0', ' ') // Replace NBSP with regular space
    }

    private fun getBundleValue(bundle: Bundle, key: String): Any? {
        @Suppress("DEPRECATION")
        return bundle.get(key)
    }

    /**
     * Save notification data to file.
     */
    private fun saveToFile(logEntry: String): Boolean {
        return try {
            // Double check if saving is enabled before writing to file
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isSaveEnabled = prefs.getBoolean(KEY_SAVE_ENABLED, true)
            
            if (!isSaveEnabled) {
                return false
            }
            
            openFileOutput("notification_log.txt", Context.MODE_APPEND).use { output ->
                output.write("$logEntry\n---\n".toByteArray())
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving notification", e)
            false
        }
    }

    private fun broadcastNotification(notification: String) {
        try {
            val intent = Intent(ACTION_NOTIFICATION_RECEIVED).apply {
                putExtra(EXTRA_NOTIFICATION_DATA, notification)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting notification", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Log.d(TAG, "Service onTaskRemoved called")
        try {
            // Restart the service if it's killed
            val restartServiceIntent = Intent(applicationContext, NotificationListenerService::class.java)
            restartServiceIntent.setPackage(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartServiceIntent)
            } else {
            startService(restartServiceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Log.d(TAG, "Service onDestroy called")
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TextToSpeech", e)
        }
        
        try {
            // Restart the service if it's destroyed
            val restartServiceIntent = Intent(applicationContext, NotificationListenerService::class.java)
            restartServiceIntent.setPackage(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartServiceIntent)
            } else {
            startService(restartServiceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting service", e)
        }
    }

    /**
     * Create notification channel for foreground service.
     */
    private fun createNotificationChannel() {
        try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Talking Bill Service",
                    NotificationManager.IMPORTANCE_HIGH  // Changed to HIGH for better reliability
            ).apply {
                description = "Background service for Talking Bill"
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(true)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel", e)
        }
    }

    /**
     * Start the service in foreground mode.
     */
    private fun startForeground() {
        try {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Talking Bill")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)  // Added high priority
                .setOngoing(true)  // Make notification persistent
            .build()

        startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    companion object {
        private const val TAG = "NotificationListener"
        private const val PREFS_NAME = "NotificationPrefs"
        private const val KEY_FILTER_ENABLED = "filter_enabled"
        private const val KEY_SAVE_ENABLED = "save_enabled"
        private const val KEY_SPEECH_PREFIX = "speech_prefix"
        private const val KEY_SPEECH_CURRENCY = "speech_currency"
        const val ACTION_NOTIFICATION_RECEIVED = "com.example.talking_bill.NOTIFICATION_RECEIVED"
        const val ACTION_RELOAD_CONFIG = "com.example.talking_bill.RELOAD_CONFIG"
        const val EXTRA_NOTIFICATION_DATA = "notification_data"
        const val NOTIFICATION_CHANNEL_ID = "talking_bill_channel"
        private const val NOTIFICATION_ID = 1
    }
} 
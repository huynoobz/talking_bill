package com.example.talking_bill

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Configuration class for notification filtering.
 * Handles loading and managing notification filtering rules.
 */
data class NotificationConfig(
    val excludedApps: List<String>,
    val includedApps: List<String>,
    val excludedKeywords: List<String>,
    val includedKeywords: List<String>
) {
    companion object {
        private const val TAG = "NotificationConfig"
        private const val CONFIG_FILE = "notification_config.json"

        /**
         * Load notification configuration from assets.
         * @param context Application context
         * @return NotificationConfig object with filtering rules
         */
        fun loadConfig(context: Context): NotificationConfig {
            return try {
                val json = context.assets.open(CONFIG_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
                Gson().fromJson(json, object : TypeToken<NotificationConfig>() {}.type)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading notification config", e)
                // Return default config if loading fails
                NotificationConfig(
                    excludedApps = listOf(),
                    includedApps = listOf(),
                    excludedKeywords = listOf(),
                    includedKeywords = listOf()
                )
            }
        }
    }
} 
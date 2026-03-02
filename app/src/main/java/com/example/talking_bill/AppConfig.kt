package com.example.talking_bill

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

typealias AppConfig = Map<String, AppConfigItem>

data class AppConfigItem(
    val receive_keyword: List<String>,
    val m_regex: List<String>
) 

object AppConfigStore {
    private const val TAG = "AppConfigStore"
    private const val DEFAULT_CONFIG_ASSET = "app_config.json"
    private const val OVERRIDE_CONFIG_FILE = "app_config_override.json"

    private val gson = Gson()
    private val appConfigType = object : TypeToken<AppConfig>() {}.type

    fun load(context: Context): AppConfig {
        return loadOverrideConfig(context) ?: loadAssetConfig(context) ?: emptyMap()
    }

    fun save(context: Context, config: AppConfig): Boolean {
        return try {
            val json = gson.toJson(config, appConfigType)
            context.openFileOutput(OVERRIDE_CONFIG_FILE, Context.MODE_PRIVATE).use { output ->
                output.write(json.toByteArray())
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving config override", e)
            false
        }
    }

    fun clearOverride(context: Context): Boolean {
        return context.deleteFile(OVERRIDE_CONFIG_FILE)
    }

    private fun loadOverrideConfig(context: Context): AppConfig? {
        return try {
            context.openFileInput(OVERRIDE_CONFIG_FILE).use { input ->
                val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                gson.fromJson<AppConfig>(json, appConfigType)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAssetConfig(context: Context): AppConfig? {
        return try {
            context.assets.open(DEFAULT_CONFIG_ASSET).use { input ->
                val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                gson.fromJson<AppConfig>(json, appConfigType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading default app config", e)
            null
        }
    }
}
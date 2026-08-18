package io.refueler.merchant.core.cashu

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EventModeManager(private val context: Context) {
    private val prefs: SharedPreferences = getOrCreateEncryptedPrefs()
    companion object {
        private const val PREFS_NAME = "event_mode_config"
        private const val KEY_EVENT_MODE = "is_event_mode"
        private const val ENV_EVENT_MODE = "NUMO_EVENT_MODE"
    }
    init {
        val envEventMode = System.getenv(ENV_EVENT_MODE)?.toBoolean() ?: false
        if (!prefs.contains(KEY_EVENT_MODE)) {
            prefs.edit().putBoolean(KEY_EVENT_MODE, envEventMode).apply()
        }
    }
    fun isEventMode(): Boolean = prefs.getBoolean(KEY_EVENT_MODE, false)
    fun setEventMode(enabled: Boolean) { prefs.edit().putBoolean(KEY_EVENT_MODE, enabled).apply() }
    private fun getOrCreateEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, PREFS_NAME, masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            android.util.Log.w("EventModeManager", "Falling back", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}

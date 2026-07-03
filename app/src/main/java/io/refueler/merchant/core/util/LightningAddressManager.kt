package io.refueler.merchant.core.util

import android.content.Context
import android.content.SharedPreferences
import io.refueler.merchant.core.prefs.EncryptedPreferenceStore

/**
 * Centralized manager for the user's Lightning withdrawal address.
 * Persisted in EncryptedSharedPreferences (AES-256-GCM).
 */
class LightningAddressManager private constructor(context: Context) {

    companion object {
        private const val LEGACY_PREFS_NAME     = "LightningAddressPreferences"
        private const val ENC_PREFS_NAME        = "lightning_address_enc"
        private const val KEY_ALIAS             = "refueler_lightning_key"
        private const val KEY_LIGHTNING_ADDRESS = "lightningAddress"

        @Volatile
        private var instance: LightningAddressManager? = null

        fun getInstance(context: Context): LightningAddressManager {
            return instance ?: synchronized(this) {
                instance ?: LightningAddressManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = run {
        val enc = EncryptedPreferenceStore.open(context, ENC_PREFS_NAME, KEY_ALIAS)
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            val editor = enc.edit()
            legacy.all.forEach { (k, v) -> if (v is String) editor.putString(k, v) }
            editor.apply()
            legacy.edit().clear().apply()
        }
        enc
    }

    fun getLightningAddress(): String = prefs.getString(KEY_LIGHTNING_ADDRESS, "") ?: ""

    fun setLightningAddress(address: String) {
        prefs.edit().putString(KEY_LIGHTNING_ADDRESS, address.trim()).apply()
    }

    fun hasLightningAddress(): Boolean = getLightningAddress().isNotBlank()

    fun clearLightningAddress() {
        prefs.edit().remove(KEY_LIGHTNING_ADDRESS).apply()
    }

    fun isValidLightningAddress(address: String): Boolean {
        val trimmed = address.trim()
        if (!trimmed.contains("@")) return false
        val parts = trimmed.split("@")
        if (parts.size != 2) return false
        return parts[0].isNotBlank() && parts[1].isNotBlank() && parts[1].contains(".")
    }
}

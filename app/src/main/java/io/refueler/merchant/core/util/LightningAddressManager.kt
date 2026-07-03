package io.refueler.merchant.core.util

import android.content.Context
import android.content.SharedPreferences
import io.refueler.merchant.core.prefs.EncryptedPreferenceStore

/**
 * Centralized manager for the user's Lightning withdrawal address.
 *
 * The address is stored in EncryptedSharedPreferences so it is not readable
 * from the plaintext XML file on unencrypted or rooted devices.
 * The legacy plaintext "LightningAddressPreferences" file is migrated and
 * wiped on first access.
 */
class LightningAddressManager private constructor(context: Context) {

    companion object {
        private const val LEGACY_PREFS_NAME = "LightningAddressPreferences"
        private const val ENC_PREFS_NAME = "lightning_address_enc"
        private const val KEY_ALIAS = "_numo_lightning_address_master_key_"
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

    private val prefs: SharedPreferences = EncryptedPreferenceStore.open(
        context,
        ENC_PREFS_NAME,
        KEY_ALIAS,
    )

    init {
        migrateLegacyPlaintextStore(context)
    }

    fun getLightningAddress(): String {
        return prefs.getString(KEY_LIGHTNING_ADDRESS, "") ?: ""
    }

    fun setLightningAddress(address: String) {
        prefs.edit().putString(KEY_LIGHTNING_ADDRESS, address.trim()).apply()
    }

    fun hasLightningAddress(): Boolean {
        return getLightningAddress().isNotBlank()
    }

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

    private fun migrateLegacyPlaintextStore(context: Context) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyAddress = legacyPrefs.getString(KEY_LIGHTNING_ADDRESS, null) ?: return
        if (prefs.getString(KEY_LIGHTNING_ADDRESS, null) == null) {
            prefs.edit().putString(KEY_LIGHTNING_ADDRESS, legacyAddress).apply()
        }
        legacyPrefs.edit().clear().apply()
    }
}

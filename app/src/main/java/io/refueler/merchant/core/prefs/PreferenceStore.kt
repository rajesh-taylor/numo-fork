package io.refueler.merchant.core.prefs

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralizes SharedPreferences access.
 *
 * - app()    : plaintext prefs for non-sensitive toggles/settings.
 * - wallet() : EncryptedSharedPreferences for Cashu wallet data (mnemonic, tokens).
 * - secure() : EncryptedSharedPreferences for BTCPay API credentials.
 *
 * wallet() and secure() migrate legacy plaintext files on first access then wipe them.
 */
object PreferenceStore {

    private const val APP_PREFS_NAME        = "numo_prefs"
    private const val WALLET_ENC_NAME       = "cashu_wallet_prefs_enc"
    private const val WALLET_LEGACY_NAME    = "cashu_wallet_prefs"
    private const val SECURE_ENC_NAME       = "numo_secure_prefs_enc"

    private val stores = ConcurrentHashMap<String, ThreadSafePreferences>()

    fun app(context: Context): ThreadSafePreferences = stores.getOrPut(APP_PREFS_NAME) {
        ThreadSafePreferences(
            context.applicationContext,
            APP_PREFS_NAME,
            legacyNames = listOf("app_prefs", "NumoPrefs"),
        )
    }

    fun wallet(context: Context): ThreadSafePreferences = stores.getOrPut(WALLET_ENC_NAME) {
        val encPrefs = EncryptedPreferenceStore.open(
            context.applicationContext, WALLET_ENC_NAME, "refueler_wallet_key"
        )
        ThreadSafePreferences(
            context.applicationContext,
            WALLET_ENC_NAME,
            legacyNames = listOf(WALLET_LEGACY_NAME),
            encryptedPrefs = encPrefs,
        )
    }

    fun secure(context: Context): ThreadSafePreferences = stores.getOrPut(SECURE_ENC_NAME) {
        val encPrefs = EncryptedPreferenceStore.open(
            context.applicationContext, SECURE_ENC_NAME, "refueler_secure_key"
        )
        ThreadSafePreferences(
            context.applicationContext,
            SECURE_ENC_NAME,
            encryptedPrefs = encPrefs,
        )
    }
}

class ThreadSafePreferences(
    private val context: Context,
    private val name: String,
    private val legacyNames: List<String> = emptyList(),
    private val encryptedPrefs: SharedPreferences? = null,
) {
    private val prefs: SharedPreferences =
        encryptedPrefs ?: context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val lock = Any()

    init {
        if (legacyNames.isNotEmpty()) migrateLegacyPreferences()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        synchronized(lock) { prefs.getBoolean(key, default) }

    fun putBoolean(key: String, value: Boolean) {
        synchronized(lock) { prefs.edit().putBoolean(key, value).apply() }
    }

    fun getString(key: String, default: String? = null): String? =
        synchronized(lock) { prefs.getString(key, default) }

    fun putString(key: String, value: String?) {
        synchronized(lock) { prefs.edit().putString(key, value).apply() }
    }

    fun remove(key: String) {
        synchronized(lock) { prefs.edit().remove(key).apply() }
    }

    private fun migrateLegacyPreferences() {
        legacyNames.forEach { legacyName ->
            if (legacyName == name) return@forEach
            val legacyPrefs = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            if (legacyPrefs.all.isEmpty()) return@forEach

            synchronized(lock) {
                val editor = prefs.edit()
                legacyPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Float   -> editor.putFloat(key, value)
                        is Int     -> editor.putInt(key, value)
                        is Long    -> editor.putLong(key, value)
                        is String  -> editor.putString(key, value)
                        is Set<*>  -> editor.putStringSet(
                            key, value.filterIsInstance<String>().toSet()
                        )
                    }
                }
                editor.apply()
            }
            legacyPrefs.edit().clear().apply()
        }
    }
}

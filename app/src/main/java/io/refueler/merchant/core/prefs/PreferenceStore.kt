package io.refueler.merchant.core.prefs

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralizes SharedPreferences access with explicit synchronization.
 *
 * Storage tiers:
 *  - app()    — plaintext numo_prefs (non-sensitive UI state, feature flags)
 *  - wallet() — EncryptedSharedPreferences (mnemonic / seed phrase)
 *  - secure() — EncryptedSharedPreferences for any other sensitive value that
 *               doesn't warrant its own dedicated manager
 *
 * IMPORTANT: wallet() and secure() are backed by EncryptedSharedPreferences
 * (AES-256-GCM via Android Keystore). Do NOT store mnemonics, API keys, tokens,
 * or webhook secrets in app().
 */
object PreferenceStore {

    private const val APP_PREFS_NAME = "numo_prefs"
    private const val WALLET_PREFS_NAME = "cashu_wallet_prefs_enc"
    private const val SECURE_PREFS_NAME = "numo_secure_prefs_enc"

    private const val WALLET_KEY_ALIAS = "_numo_wallet_master_key_"
    private const val SECURE_KEY_ALIAS = "_numo_secure_master_key_"

    private val stores = ConcurrentHashMap<String, ThreadSafePreferences>()

    fun app(context: Context): ThreadSafePreferences = stores.getOrPut(APP_PREFS_NAME) {
        ThreadSafePreferences(
            context.applicationContext,
            APP_PREFS_NAME,
            legacyNames = listOf("app_prefs", "NumoPrefs"),
        )
    }

    /**
     * Encrypted store for the wallet mnemonic.
     * Migrates plaintext data from the legacy "cashu_wallet_prefs" file on first access.
     */
    fun wallet(context: Context): ThreadSafePreferences = stores.getOrPut(WALLET_PREFS_NAME) {
        val encPrefs = EncryptedPreferenceStore.open(
            context.applicationContext,
            WALLET_PREFS_NAME,
            WALLET_KEY_ALIAS,
        )
        ThreadSafePreferences(
            context.applicationContext,
            WALLET_PREFS_NAME,
            encPrefs = encPrefs,
            legacyPlaintextNames = listOf("cashu_wallet_prefs"),
        )
    }

    /**
     * Encrypted store for any additional sensitive value (e.g. BTCPay API key).
     */
    fun secure(context: Context): ThreadSafePreferences = stores.getOrPut(SECURE_PREFS_NAME) {
        val encPrefs = EncryptedPreferenceStore.open(
            context.applicationContext,
            SECURE_PREFS_NAME,
            SECURE_KEY_ALIAS,
        )
        ThreadSafePreferences(
            context.applicationContext,
            SECURE_PREFS_NAME,
            encPrefs = encPrefs,
        )
    }
}

class ThreadSafePreferences(
    private val context: Context,
    private val name: String,
    /** Supply a pre-built EncryptedSharedPreferences instance for encrypted stores. */
    private val encPrefs: SharedPreferences? = null,
    private val legacyNames: List<String> = emptyList(),
    /** Plaintext legacy files whose contents should be migrated into this encrypted store. */
    private val legacyPlaintextNames: List<String> = emptyList(),
) {
    private val prefs: SharedPreferences =
        encPrefs ?: context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val lock = Any()

    init {
        migrateLegacyPreferences()
        migrateLegacyPlaintextPreferences()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        synchronized(lock) { prefs.getBoolean(key, default) }

    fun putBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    fun getString(key: String, default: String? = null): String? =
        synchronized(lock) { prefs.getString(key, default) }

    fun putString(key: String, value: String?) {
        synchronized(lock) {
            prefs.edit().putString(key, value).apply()
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            prefs.edit().remove(key).apply()
        }
    }

    /** Migrate between same-type (both plaintext or both encrypted) named stores. */
    private fun migrateLegacyPreferences() {
        if (legacyNames.isEmpty()) return

        legacyNames.forEach { legacyName ->
            if (legacyName == name) return@forEach
            val legacyPrefs = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            if (legacyPrefs.all.isEmpty()) return@forEach

            synchronized(lock) {
                val editor = prefs.edit()
                legacyPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> editor.putStringSet(
                            key,
                            value.filterIsInstance<String>().toSet(),
                        )
                    }
                }
                editor.apply()
            }

            legacyPrefs.edit().clear().apply()
        }
    }

    /**
     * Migrate sensitive values from a plaintext SharedPreferences file into this
     * EncryptedSharedPreferences store, then wipe the plaintext file.
     */
    private fun migrateLegacyPlaintextPreferences() {
        if (legacyPlaintextNames.isEmpty() || encPrefs == null) return

        legacyPlaintextNames.forEach { legacyName ->
            val legacyPrefs = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            if (legacyPrefs.all.isEmpty()) return@forEach

            synchronized(lock) {
                val editor = prefs.edit()
                legacyPrefs.all.forEach { (key, value) ->
                    // Only migrate string values — the mnemonic and other secrets are strings.
                    // Non-string values in the legacy wallet store are safe to drop; they are
                    // runtime caches that will be regenerated.
                    if (value is String) editor.putString(key, value)
                }
                editor.apply()
            }

            // Wipe the plaintext copy.
            legacyPrefs.edit().clear().apply()
        }
    }
}

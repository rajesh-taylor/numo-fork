package io.refueler.merchant.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Factory for AES-256-GCM EncryptedSharedPreferences backed by Android Keystore.
 *
 * Usage:
 *   val store = EncryptedPreferenceStore.open(context, "my_prefs_enc", "my_key_alias")
 *   store.edit().putString("key", "value").apply()
 */
object EncryptedPreferenceStore {

    fun open(context: Context, fileName: String, keyAlias: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext, keyAlias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

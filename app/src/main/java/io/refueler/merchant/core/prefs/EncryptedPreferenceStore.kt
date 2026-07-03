package io.refueler.merchant.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Factory for EncryptedSharedPreferences backed by AES-256-GCM via Android Keystore.
 *
 * Each sensitive store gets its own file and its own MasterKey alias so that
 * a compromise of one key does not affect others.
 */
object EncryptedPreferenceStore {

    /**
     * Open (or create) an EncryptedSharedPreferences file.
     *
     * @param context   Application context.
     * @param fileName  Prefs file name — must be stable across app launches.
     * @param keyAlias  Android Keystore alias used to protect this file's key.
     */
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

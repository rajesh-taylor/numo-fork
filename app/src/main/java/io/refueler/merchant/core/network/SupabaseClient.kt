package io.refueler.merchant.core.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_SESSION_JWT
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_SUPABASE_URL
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_VENUE_ID
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.PREFS_SESSION_FILE
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.PREFS_SESSION_KEY_ALIAS
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Singleton HTTP client for Supabase PostgREST and Edge Function calls.
 *
 * Reads Supabase URL and session JWT from EncryptedSharedPreferences at
 * call time — not at construction — so a re-provisioned device automatically
 * picks up a new JWT without needing to recreate the singleton.
 *
 * All calls are blocking and must be made from a coroutine / background thread.
 *
 * Architecture note (ADR §2): NumoPay holds no funds and custodies nothing.
 * This client writes to merchant_orders (floor orders) and reads from
 * merchant_menu_items and merchant_orders. It never touches the orders table
 * or the payments layer directly.
 */
object SupabaseClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Credential accessors
    // ------------------------------------------------------------------

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_SESSION_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun supabaseUrl(context: Context): String =
        prefs(context).getString(KEY_SUPABASE_URL, null)
            ?: error("Device not provisioned — no Supabase URL in secure prefs")

    fun sessionJwt(context: Context): String =
        prefs(context).getString(KEY_SESSION_JWT, null)
            ?: error("Device not provisioned — no session JWT in secure prefs")

    fun venueId(context: Context): String =
        prefs(context).getString(KEY_VENUE_ID, null)
            ?: error("Device not provisioned — no venue_id in secure prefs")

    fun isProvisioned(context: Context): Boolean = try {
        val p = prefs(context)
        !p.getString(KEY_SUPABASE_URL, null).isNullOrBlank() &&
                !p.getString(KEY_SESSION_JWT, null).isNullOrBlank() &&
                !p.getString(KEY_VENUE_ID, null).isNullOrBlank()
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // PostgREST helpers
    // ------------------------------------------------------------------

    /**
     * GET /rest/v1/{table}?{query}
     * Returns raw JSON string or throws on HTTP error.
     */
    fun postgrestGet(context: Context, table: String, query: String): String {
        val url = "${supabaseUrl(context)}/rest/v1/$table?$query"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${sessionJwt(context)}")
            .addHeader("apikey", sessionJwt(context))
            .addHeader("Accept", "application/json")
            .build()
        val response = http.newCall(request).execute()
        return response.bodyOrThrow()
    }

    /**
     * POST /rest/v1/{table} with JSON body.
     * Returns raw JSON string of created row(s) or throws on HTTP error.
     */
    fun postgrestPost(context: Context, table: String, body: String): String {
        val url = "${supabaseUrl(context)}/rest/v1/$table"
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer ${sessionJwt(context)}")
            .addHeader("apikey", sessionJwt(context))
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=representation")
            .build()
        val response = http.newCall(request).execute()
        return response.bodyOrThrow()
    }

    /**
     * POST /functions/v1/{functionName} with JSON body.
     * Returns raw JSON string or throws on HTTP error.
     */
    fun edgeFunctionPost(context: Context, functionName: String, body: String): String {
        val url = "${supabaseUrl(context)}/functions/v1/$functionName"
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer ${sessionJwt(context)}")
            .addHeader("Content-Type", "application/json")
            .build()
        val response = http.newCall(request).execute()
        return response.bodyOrThrow()
    }

    // ------------------------------------------------------------------
    // Connectivity check
    // ------------------------------------------------------------------

    /**
     * Lightweight connectivity probe — hits verify-pin with a deliberately
     * empty payload (will return 400, not 200, but proves network is reachable).
     * Returns true if the server responded with any HTTP status (even an error).
     */
    fun isServerReachable(context: Context): Boolean = try {
        val url = "${supabaseUrl(context)}/functions/v1/verify-pin"
        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody(JSON))
            .addHeader("Authorization", "Bearer ${sessionJwt(context)}")
            .addHeader("Content-Type", "application/json")
            .build()
        http.newCall(request).execute().use { true }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun Response.bodyOrThrow(): String {
        val bodyString = use { it.body?.string() } ?: ""
        if (!isSuccessful) {
            throw SupabaseException(code, bodyString)
        }
        return bodyString
    }
}

class SupabaseException(val httpCode: Int, val responseBody: String) :
    Exception("Supabase HTTP $httpCode: $responseBody")

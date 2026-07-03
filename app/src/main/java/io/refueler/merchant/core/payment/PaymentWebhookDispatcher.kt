package io.refueler.merchant.core.payment

import android.content.Context
import io.refueler.merchant.core.prefs.PreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Dispatches payment event notifications to configured webhook endpoints.
 *
 * Signing: Svix-compatible HMAC-SHA256.
 *   - Secret stored in PreferenceStore.secure() under "webhook_signing_secret" (whsec_ prefix)
 *   - Signed content: "{svix-id}.{svix-timestamp}.{rawBody}"
 *   - Headers sent: svix-id, svix-timestamp, svix-signature ("v1,<base64sig>")
 *
 * The Supabase blink-webhook Edge Function verifies with the same algorithm.
 */
object PaymentWebhookDispatcher {

    private const val KEY_WEBHOOK_SECRET = "webhook_signing_secret"

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun dispatch(context: Context, endpointUrl: String, jsonBody: String) {
        withContext(Dispatchers.IO) {
            val secret = PreferenceStore.secure(context).getString(KEY_WEBHOOK_SECRET)
            if (secret.isNullOrBlank()) {
                android.util.Log.w("PaymentWebhookDispatcher", "No webhook_signing_secret configured — skipping dispatch")
                return@withContext
            }
            val svixId        = "msg_${UUID.randomUUID().toString().replace("-", "")}"
            val svixTimestamp = Instant.now().epochSecond.toString()
            val signature     = computeSvixSignature(secret, svixId, svixTimestamp, jsonBody)

            val request = Request.Builder()
                .url(endpointUrl)
                .post(jsonBody.toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")
                .addHeader("svix-id",        svixId)
                .addHeader("svix-timestamp", svixTimestamp)
                .addHeader("svix-signature", "v1,$signature")
                .build()

            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        android.util.Log.w(
                            "PaymentWebhookDispatcher",
                            "Webhook ${endpointUrl} responded ${resp.code}"
                        )
                    }
                }
            }.onFailure { e ->
                android.util.Log.e("PaymentWebhookDispatcher", "Webhook dispatch failed: ${e.message}")
            }
        }
    }

    /**
     * Svix HMAC-SHA256:
     *   1. Strip "whsec_" prefix from secret, base64-decode to raw key bytes.
     *   2. Sign "{svix-id}.{svix-timestamp}.{body}" with HMAC-SHA256.
     *   3. Return base64-encoded signature (no prefix — caller prepends "v1,").
     */
    private fun computeSvixSignature(
        secret: String,
        svixId: String,
        svixTimestamp: String,
        body: String,
    ): String {
        val rawSecret = secret.removePrefix("whsec_")
        val keyBytes  = Base64.getDecoder().decode(rawSecret)
        val mac       = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val signedContent = "$svixId.$svixTimestamp.$body"
        val sigBytes = mac.doFinal(signedContent.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sigBytes)
    }
}

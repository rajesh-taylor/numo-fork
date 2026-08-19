package io.refueler.merchant.feature.provisioning

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.refueler.merchant.R
import io.refueler.merchant.core.prefs.EncryptedPreferenceStore
import io.refueler.merchant.feature.pin.PinEntryActivity
import io.refueler.merchant.feature.scanner.QRScannerActivity

/**
 * First-time device provisioning screen.
 *
 * Shown only when no valid session exists (no JWT in EncryptedSharedPreferences,
 * or JWT expiry has passed). On normal shift-start the app goes directly to
 * PinEntryActivity.
 *
 * Flow:
 *   AM generates provisioning QR in Command Centre
 *   → Staff taps [Scan setup QR]
 *   → QRScannerActivity returns JSON payload
 *   → Payload validated and stored in EncryptedSharedPreferences
 *   → PinEntryActivity launched (shift-start PIN gate)
 *
 * QR payload schema:
 *   { "url": "https://<supabase-project>.supabase.co",
 *     "token": "<bootstrap JWT, 5-min TTL>",
 *     "venue_id": "<uuid>" }
 */
class RefuelerProvisioningActivity : AppCompatActivity() {

    private lateinit var scanButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var qrScannerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_refueler_provisioning)

        scanButton   = findViewById(R.id.btn_scan_qr)
        statusText   = findViewById(R.id.tv_status)
        progressBar  = findViewById(R.id.progress_bar)

        qrScannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val qrValue = result.data?.getStringExtra(QRScannerActivity.EXTRA_QR_VALUE)
                if (!qrValue.isNullOrBlank()) {
                    handleProvisioningQr(qrValue)
                } else {
                    showError(getString(R.string.provisioning_error_empty_qr))
                }
            }
        }

        scanButton.setOnClickListener {
            clearStatus()
            val intent = Intent(this, QRScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        }
    }

    // ------------------------------------------------------------------
    // QR handling
    // ------------------------------------------------------------------

    private fun handleProvisioningQr(raw: String) {
        showLoading()
        val payload = parsePayload(raw)

        if (payload == null ||
            payload.url.isNullOrBlank() ||
            payload.token.isNullOrBlank() ||
            payload.venue_id.isNullOrBlank()
        ) {
            hideLoading()
            showError(getString(R.string.provisioning_error_invalid_qr))
            return
        }

        storeSession(payload)
        hideLoading()
        proceedToPin()
    }

    private fun parsePayload(raw: String): ProvisioningQrPayload? {
        return try {
            Gson().fromJson(raw, ProvisioningQrPayload::class.java)
        } catch (e: JsonSyntaxException) {
            null
        }
    }

    private fun storeSession(payload: ProvisioningQrPayload) {
        val prefs = EncryptedPreferenceStore.open(
            this,
            PREFS_SESSION_FILE,
            PREFS_SESSION_KEY_ALIAS
        )
        // Bootstrap JWT lifetime: 5 minutes from issuance.
        // The JWT itself carries the exp claim; we store the raw token
        // so verify-pin EF can authenticate it. The 12h session JWT is
        // established at first shift-start verify-pin call — the EF
        // returns a refreshed JWT which overwrites KEY_SESSION_JWT.
        prefs.edit()
            .putString(KEY_SUPABASE_URL, payload.url)
            .putString(KEY_SESSION_JWT, payload.token)
            .putString(KEY_VENUE_ID, payload.venue_id)
            .putLong(KEY_LOCAL_GRANT_UNTIL, 0L) // no grant yet — PIN required
            .apply()
    }

    private fun proceedToPin() {
        val intent = Intent(this, PinEntryActivity::class.java).apply {
            putExtra(PinEntryActivity.EXTRA_TITLE, getString(R.string.pin_entry_shift_start_title))
            putExtra(PinEntryActivity.EXTRA_SUBTITLE, getString(R.string.pin_entry_shift_start_subtitle))
            putExtra(PinEntryActivity.EXTRA_ALLOW_BACK, false)
        }
        startActivity(intent)
        finish() // provisioning screen never returns to back-stack
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        scanButton.isEnabled = false
        statusText.visibility = View.GONE
    }

    private fun hideLoading() {
        progressBar.visibility = View.GONE
        scanButton.isEnabled = true
    }

    private fun showError(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
    }

    private fun clearStatus() {
        statusText.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private data class ProvisioningQrPayload(
        val url: String?,
        val token: String?,
        val venue_id: String?
    )

    companion object {
        // EncryptedSharedPreferences file + key alias — shared with PinEntryActivity
        const val PREFS_SESSION_FILE      = "refueler_session_enc"
        const val PREFS_SESSION_KEY_ALIAS = "refueler_session_key"

        // Keys — consumed by PinEntryActivity and ModernPOSActivity
        const val KEY_SUPABASE_URL       = "refueler_supabase_url"
        const val KEY_SESSION_JWT        = "refueler_session_jwt"
        const val KEY_VENUE_ID           = "refueler_venue_id"
        const val KEY_LOCAL_GRANT_UNTIL  = "refueler_local_grant_until"
    }
}

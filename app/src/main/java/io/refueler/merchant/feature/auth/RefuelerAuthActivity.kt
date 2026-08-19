package io.refueler.merchant.feature.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.refueler.merchant.core.prefs.EncryptedPreferenceStore
import io.refueler.merchant.feature.pin.PinEntryActivity
import io.refueler.merchant.ModernPOSActivity
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_LOCAL_GRANT_UNTIL
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_SESSION_JWT
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_VENUE_ID
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.PREFS_SESSION_FILE
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.PREFS_SESSION_KEY_ALIAS
import io.refueler.merchant.R

/**
 * App entry point. Routing logic only — no UI of its own beyond the
 * "not provisioned" screen.
 *
 * Decision tree on launch:
 *   1. No JWT in EncryptedSharedPreferences → show provisioning screen
 *   2. JWT present + local grant still valid → go straight to ModernPOSActivity
 *   3. JWT present + local grant expired → go to PinEntryActivity (shift-start)
 *
 * This activity is the LAUNCHER activity in AndroidManifest. It is never
 * shown directly to the user when a session exists — it finishes() after routing.
 */
class RefuelerAuthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = try {
            EncryptedPreferenceStore.open(this, PREFS_SESSION_FILE, PREFS_SESSION_KEY_ALIAS)
        } catch (e: Exception) {
            // EncryptedSharedPreferences can throw if the Keystore is wiped
            // (factory reset without uninstall). Treat as unprovisioned.
            routeToProvisioning()
            return
        }

        val jwt = prefs.getString(KEY_SESSION_JWT, null)
        val venueId = prefs.getString(KEY_VENUE_ID, null)

        if (jwt.isNullOrBlank() || venueId.isNullOrBlank()) {
            routeToProvisioning()
            return
        }

        val grantUntil = prefs.getLong(KEY_LOCAL_GRANT_UNTIL, 0L)
        if (System.currentTimeMillis() < grantUntil) {
            // Grant still valid — skip PIN, go straight to POS
            routeToPOS()
        } else {
            // Require shift-start PIN
            routeToPin()
        }
    }

    private fun routeToProvisioning() {
        startActivity(Intent(this, RefuelerProvisioningActivity::class.java))
        finish()
    }

    private fun routeToPin() {
        val intent = Intent(this, PinEntryActivity::class.java).apply {
            putExtra(PinEntryActivity.EXTRA_TITLE, getString(R.string.pin_entry_shift_start_title))
            putExtra(PinEntryActivity.EXTRA_SUBTITLE, getString(R.string.pin_entry_shift_start_subtitle))
            putExtra(PinEntryActivity.EXTRA_ALLOW_BACK, false)
        }
        startActivity(intent)
        finish()
    }

    private fun routeToPOS() {
        startActivity(Intent(this, ModernPOSActivity::class.java))
        finish()
    }
}

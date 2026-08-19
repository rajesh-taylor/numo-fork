package io.refueler.merchant.feature.pin

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.refueler.merchant.R
import io.refueler.merchant.core.prefs.EncryptedPreferenceStore
import io.refueler.merchant.ModernPOSActivity
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_LOCAL_GRANT_UNTIL
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_SESSION_JWT
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_SUPABASE_URL
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.KEY_VENUE_ID
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.PREFS_SESSION_FILE
import io.refueler.merchant.feature.provisioning.RefuelerProvisioningActivity.Companion.PREFS_SESSION_KEY_ALIAS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.app.Activity

/**
 * Shift-start PIN entry screen.
 *
 * Behaviour:
 * - Sends 4-digit PIN to verify-pin v2 Edge Function (server-side bcrypt).
 * - On 200 OK: writes 30-min local grant to EncryptedSharedPreferences,
 *   launches ModernPOSActivity, finishes.
 * - On network failure + valid local grant: proceeds with "offline — limited
 *   mode" banner (waiter must not be locked out by a wifi blip).
 * - On 401: "Session expired — contact your manager." No self-service re-auth.
 * - On 429 / lockout from EF: shows remaining wait time from response body.
 *
 * The UI shell (PinDotsView, PinKeypadView, error/lockout overlays) is
 * retained from the original Numo PinEntryActivity. The backend call is
 * replaced wholesale — PinManager and its Keystore-encrypted local PIN are
 * no longer used.
 */
class PinEntryActivity : AppCompatActivity() {

    private lateinit var pinDots: PinDotsView
    private lateinit var pinKeypad: PinKeypadView
    private lateinit var errorMessage: TextView
    private lateinit var lockoutOverlay: FrameLayout
    private lateinit var lockoutMessage: TextView
    private lateinit var forgotPinButton: TextView
    private lateinit var backButton: ImageButton

    private val enteredPin = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var lockoutUpdateRunnable: Runnable? = null
    private var cooldownTimer: CountDownTimer? = null
    private var isInputDisabled = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_entry)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        pinDots        = findViewById(R.id.pin_dots)
        pinKeypad      = findViewById(R.id.pin_keypad)
        errorMessage   = findViewById(R.id.error_message)
        lockoutOverlay = findViewById(R.id.lockout_overlay)
        lockoutMessage = findViewById(R.id.lockout_message)
        forgotPinButton = findViewById(R.id.forgot_pin_button)
        backButton     = findViewById(R.id.back_button)

        intent.getStringExtra(EXTRA_TITLE)?.let {
            findViewById<TextView>(R.id.title).text = it
        }
        intent.getStringExtra(EXTRA_SUBTITLE)?.let {
            findViewById<TextView>(R.id.subtitle).text = it
        }

        // PIN reset is an Owner tab operation on the tablet terminal.
        // The floor device never resets its own PIN.
        forgotPinButton.visibility = View.GONE
    }

    private fun setupListeners() {
        pinKeypad.setOnKeyListener(object : PinKeypadView.OnKeyListener {
            override fun onDigitPressed(digit: String) {
                if (!isInputDisabled && enteredPin.length < PIN_LENGTH) {
                    enteredPin.append(digit)
                    pinDots.addDigit()
                    if (enteredPin.length == PIN_LENGTH) {
                        submitPin(enteredPin.toString())
                    }
                }
            }
            override fun onDeletePressed() {
                if (enteredPin.isNotEmpty()) {
                    enteredPin.deleteCharAt(enteredPin.length - 1)
                    pinDots.removeDigit()
                }
            }
        })

        val allowBack = intent.getBooleanExtra(EXTRA_ALLOW_BACK, false)
        if (allowBack) {
            backButton.visibility = View.VISIBLE
            backButton.setOnClickListener {
                setResult(RESULT_CANCELLED)
                finish()
            }
        } else {
            backButton.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------
    // PIN verification
    // ------------------------------------------------------------------

    private fun submitPin(pin: String) {
        isInputDisabled = true
        pinKeypad.alpha = 0.4f

        lifecycleScope.launch {
            val result = verifyPinRemote(pin)
            withContext(Dispatchers.Main) {
                when (result) {
                    is VerifyResult.Success  -> onVerifySuccess(result.refreshedJwt)
                    is VerifyResult.Offline  -> onVerifyOffline()
                    is VerifyResult.BadPin   -> onVerifyBadPin(result.attemptsRemaining)
                    is VerifyResult.Locked   -> onVerifyLocked(result.retryAfterMs)
                    is VerifyResult.Expired  -> onVerifyExpired()
                    is VerifyResult.Error    -> onVerifyError(result.message)
                }
            }
        }
    }

    private suspend fun verifyPinRemote(pin: String): VerifyResult = withContext(Dispatchers.IO) {
        val prefs = try {
            EncryptedPreferenceStore.open(
                applicationContext, PREFS_SESSION_FILE, PREFS_SESSION_KEY_ALIAS
            )
        } catch (e: Exception) {
            return@withContext VerifyResult.Error("Storage unavailable")
        }

        val supabaseUrl = prefs.getString(KEY_SUPABASE_URL, null)
            ?: return@withContext VerifyResult.Error("Device not provisioned")
        val jwt = prefs.getString(KEY_SESSION_JWT, null)
            ?: return@withContext VerifyResult.Error("Device not provisioned")
        val venueId = prefs.getString(KEY_VENUE_ID, null)
            ?: return@withContext VerifyResult.Error("Device not provisioned")

        val body = JSONObject().apply {
            put("pin", pin)
            put("venue_id", venueId)
        }.toString()

        val request = Request.Builder()
            .url("$supabaseUrl/functions/v1/verify-pin")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $jwt")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            return@withContext when (response.code) {
                200 -> {
                    // EF may return a refreshed JWT — consume it if present.
                    val refreshedJwt = try {
                        JSONObject(responseBody).optString("token", jwt)
                    } catch (e: Exception) { jwt }
                    VerifyResult.Success(refreshedJwt)
                }
                401 -> VerifyResult.Expired
                429 -> {
                    val retryAfterMs = try {
                        JSONObject(responseBody).optLong("retry_after_ms", 300_000L)
                    } catch (e: Exception) { 300_000L }
                    VerifyResult.Locked(retryAfterMs)
                }
                403 -> {
                    val remaining = try {
                        JSONObject(responseBody).optInt("attempts_remaining", 0)
                    } catch (e: Exception) { 0 }
                    VerifyResult.BadPin(remaining)
                }
                else -> VerifyResult.Error("Unexpected response: ${response.code}")
            }
        } catch (e: Exception) {
            // Network failure — check local grant
            val grantUntil = prefs.getLong(KEY_LOCAL_GRANT_UNTIL, 0L)
            return@withContext if (System.currentTimeMillis() < grantUntil) {
                VerifyResult.Offline
            } else {
                VerifyResult.Error(e.message ?: "Network error")
            }
        }
    }

    // ------------------------------------------------------------------
    // Result handlers
    // ------------------------------------------------------------------

    private fun onVerifySuccess(refreshedJwt: String) {
        val prefs = EncryptedPreferenceStore.open(
            this, PREFS_SESSION_FILE, PREFS_SESSION_KEY_ALIAS
        )
        prefs.edit()
            .putString(KEY_SESSION_JWT, refreshedJwt)
            .putLong(KEY_LOCAL_GRANT_UNTIL, System.currentTimeMillis() + GRANT_DURATION_MS)
            .apply()

        launchPOS(offlineMode = false)
    }

    private fun onVerifyOffline() {
        // Local grant still valid — proceed with banner
        launchPOS(offlineMode = true)
    }

    private fun onVerifyBadPin(attemptsRemaining: Int) {
        enteredPin.clear()
        pinDots.clear()
        pinDots.showError()
        isInputDisabled = false
        pinKeypad.alpha = 1f

        val msg = if (attemptsRemaining > 0) {
            getString(R.string.pin_entry_wrong_pin_attempts, attemptsRemaining)
        } else {
            getString(R.string.pin_entry_wrong_pin)
        }
        showError(msg)
    }

    private fun onVerifyLocked(retryAfterMs: Long) {
        isInputDisabled = true
        lockoutOverlay.visibility = View.VISIBLE
        startLockoutCountdown(retryAfterMs)
    }

    private fun onVerifyExpired() {
        // JWT is stale — device needs re-provisioning via AM
        showError(getString(R.string.pin_entry_session_expired))
        // Disable keypad; merchant must contact AM
        isInputDisabled = true
        pinKeypad.alpha = 0.3f
    }

    private fun onVerifyError(message: String) {
        enteredPin.clear()
        pinDots.clear()
        isInputDisabled = false
        pinKeypad.alpha = 1f
        showError(getString(R.string.pin_entry_network_error))
    }

    private fun launchPOS(offlineMode: Boolean) {
        val intent = Intent(this, ModernPOSActivity::class.java).apply {
            putExtra(ModernPOSActivity.EXTRA_OFFLINE_MODE, offlineMode)
        }
        startActivity(intent)
        finish()
    }

    // ------------------------------------------------------------------
    // Lockout UI
    // ------------------------------------------------------------------

    private fun startLockoutCountdown(durationMs: Long) {
        lockoutMessage.text = formatLockoutMs(durationMs)
        lockoutUpdateRunnable = object : Runnable {
            private var remaining = durationMs
            override fun run() {
                remaining -= 1000L
                if (remaining > 0) {
                    lockoutMessage.text = formatLockoutMs(remaining)
                    handler.postDelayed(this, 1000L)
                } else {
                    lockoutOverlay.visibility = View.GONE
                    isInputDisabled = false
                    pinKeypad.alpha = 1f
                    enteredPin.clear()
                    pinDots.clear()
                }
            }
        }
        handler.post(lockoutUpdateRunnable!!)
    }

    private fun formatLockoutMs(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        val seconds = ((ms % 60_000) / 1_000).toInt()
        return if (minutes > 0) {
            getString(R.string.pin_entry_try_again_minutes, minutes, if (minutes > 1) "s" else "")
        } else {
            getString(R.string.pin_entry_try_again_seconds, seconds, if (seconds > 1) "s" else "")
        }
    }

    private fun showError(msg: String) {
        errorMessage.text = msg
        errorMessage.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutUpdateRunnable?.let { handler.removeCallbacks(it) }
        cooldownTimer?.cancel()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_ALLOW_BACK, false)) {
            setResult(RESULT_CANCELLED)
            super.onBackPressed()
        }
        // else: swallow back — shift-start PIN is non-dismissable
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------

    private sealed class VerifyResult {
        data class Success(val refreshedJwt: String) : VerifyResult()
        object Offline : VerifyResult()
        data class BadPin(val attemptsRemaining: Int) : VerifyResult()
        data class Locked(val retryAfterMs: Long) : VerifyResult()
        object Expired : VerifyResult()
        data class Error(val message: String) : VerifyResult()
    }

    companion object {
        const val EXTRA_TITLE      = "extra_title"
        const val EXTRA_SUBTITLE   = "extra_subtitle"
        const val EXTRA_ALLOW_BACK = "extra_allow_back"
        const val RESULT_PIN_VERIFIED = Activity.RESULT_OK
        const val RESULT_CANCELLED    = Activity.RESULT_CANCELED

        private const val PIN_LENGTH       = 4
        private const val GRANT_DURATION_MS = 30L * 60L * 1_000L // 30 minutes
    }
}

package io.refueler.merchant.feature.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.refueler.merchant.R
import io.refueler.merchant.core.payment.BTCPayConfig
import io.refueler.merchant.core.payment.BtcPayAppsService
import io.refueler.merchant.core.payment.BtcPayPosApp
import io.refueler.merchant.core.prefs.PreferenceStore
import io.refueler.merchant.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BtcPaySettingsActivity : AppCompatActivity() {

    private lateinit var enableSwitch: SwitchCompat
    private lateinit var serverUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var storeIdInput: EditText
    private lateinit var testConnectionStatus: TextView
    private lateinit var posSectionLabel: View
    private lateinit var posAppCard: View
    private lateinit var posAppSubtitle: TextView

    private var connectionTestPassed = false
    private var isLoadingSettings = false

    companion object {
        // Non-sensitive toggle — stored in plaintext app prefs.
        private const val KEY_ENABLED = "btcpay_enabled"

        // Credential keys — stored in encrypted secure prefs.
        private const val KEY_SERVER_URL = "btcpay_server_url"
        private const val KEY_API_KEY = "btcpay_api_key"
        private const val KEY_STORE_ID = "btcpay_store_id"
        private const val KEY_POS_APP_ID = "btcpay_pos_app_id"
        private const val KEY_POS_APP_NAME = "btcpay_pos_app_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_btcpay_settings)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        initViews()
        setupListeners()
        loadSettings()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        enableSwitch = findViewById(R.id.btcpay_enable_switch)
        serverUrlInput = findViewById(R.id.btcpay_server_url_input)
        apiKeyInput = findViewById(R.id.btcpay_api_key_input)
        storeIdInput = findViewById(R.id.btcpay_store_id_input)
        testConnectionStatus = findViewById(R.id.test_connection_status)
        posSectionLabel = findViewById(R.id.pos_section_label)
        posAppCard = findViewById(R.id.pos_app_card)
        posAppSubtitle = findViewById(R.id.pos_app_subtitle)
    }

    private fun hasAllFields(): Boolean {
        return serverUrlInput.text.toString().isNotBlank() &&
            apiKeyInput.text.toString().isNotBlank() &&
            storeIdInput.text.toString().isNotBlank()
    }

    private fun updateToggleEnabled() {
        val canEnable = hasAllFields()
        enableSwitch.isEnabled = canEnable
        if (!canEnable && enableSwitch.isChecked) {
            enableSwitch.isChecked = false
            // KEY_ENABLED is non-sensitive — stays in app prefs.
            PreferenceStore.app(this).putBoolean(KEY_ENABLED, false)
        }
    }

    private fun updatePosVisibility() {
        val visible = if (enableSwitch.isChecked) View.VISIBLE else View.GONE
        posSectionLabel.visibility = visible
        posAppCard.visibility = visible
    }

    private fun setupListeners() {
        val enableToggleRow = findViewById<LinearLayout>(R.id.enable_toggle_row)
        enableToggleRow.setOnClickListener {
            if (hasAllFields()) enableSwitch.toggle()
        }

        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !connectionTestPassed) {
                enableSwitch.isChecked = false
                testConnection(onSuccess = {
                    enableSwitch.isChecked = true
                    updatePosVisibility()
                })
            } else {
                PreferenceStore.app(this).putBoolean(KEY_ENABLED, isChecked)
                updatePosVisibility()
            }
        }

        val fieldWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isLoadingSettings) return
                connectionTestPassed = false
                updateToggleEnabled()
                clearPosSelection()
            }
        }
        serverUrlInput.addTextChangedListener(fieldWatcher)
        apiKeyInput.addTextChangedListener(fieldWatcher)
        storeIdInput.addTextChangedListener(fieldWatcher)

        findViewById<LinearLayout>(R.id.test_connection_row).setOnClickListener {
            testConnection()
        }

        findViewById<LinearLayout>(R.id.pos_app_row).setOnClickListener {
            showPosAppPicker()
        }
    }

    private fun loadSettings() {
        isLoadingSettings = true
        // Credentials come from the encrypted secure store.
        val securePrefs = PreferenceStore.secure(this)
        serverUrlInput.setText(securePrefs.getString(KEY_SERVER_URL, "") ?: "")
        apiKeyInput.setText(securePrefs.getString(KEY_API_KEY, "") ?: "")
        storeIdInput.setText(securePrefs.getString(KEY_STORE_ID, "") ?: "")
        isLoadingSettings = false

        // The enabled toggle is non-sensitive and lives in the plaintext app store.
        val alreadyEnabled = PreferenceStore.app(this).getBoolean(KEY_ENABLED, false)
        if (alreadyEnabled && hasAllFields()) connectionTestPassed = true
        val canEnable = hasAllFields() && connectionTestPassed
        enableSwitch.isEnabled = canEnable
        enableSwitch.isChecked = canEnable && alreadyEnabled

        val posAppName = securePrefs.getString(KEY_POS_APP_NAME, null)
        posAppSubtitle.text = posAppName ?: getString(R.string.btcpay_pos_app_subtitle_none)

        updatePosVisibility()
    }

    private fun saveTextFields() {
        val securePrefs = PreferenceStore.secure(this)
        securePrefs.putString(KEY_SERVER_URL, serverUrlInput.text.toString().trim())
        securePrefs.putString(KEY_API_KEY, apiKeyInput.text.toString().trim())
        securePrefs.putString(KEY_STORE_ID, storeIdInput.text.toString().trim())
    }

    private fun clearPosSelection() {
        val securePrefs = PreferenceStore.secure(this)
        val hadSelection = !securePrefs.getString(KEY_POS_APP_ID, null).isNullOrBlank()
        securePrefs.remove(KEY_POS_APP_ID)
        securePrefs.remove(KEY_POS_APP_NAME)
        if (hadSelection) {
            posAppSubtitle.text = getString(R.string.btcpay_pos_app_subtitle_none)
        }
    }

    private fun showPosAppPicker() {
        val serverUrl = serverUrlInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        val storeId = storeIdInput.text.toString().trim()

        if (serverUrl.isBlank() || apiKey.isBlank() || storeId.isBlank()) {
            Toast.makeText(this, R.string.btcpay_test_fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }

        val config = BTCPayConfig(serverUrl = serverUrl, apiKey = apiKey, storeId = storeId)

        val loadingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.btcpay_pos_select_title)
            .setMessage(R.string.btcpay_test_connecting)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BtcPayAppsService.fetchPosApps(config) }
            }
            loadingDialog.dismiss()

            result.onSuccess { apps ->
                presentPosAppDialog(apps)
            }.onFailure { e ->
                val msg = getString(R.string.btcpay_pos_load_error, e.message ?: "")
                Toast.makeText(this@BtcPaySettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun presentPosAppDialog(apps: List<BtcPayPosApp>) {
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.btcpay_pos_no_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val securePrefs = PreferenceStore.secure(this)
        val currentId = securePrefs.getString(KEY_POS_APP_ID, null)

        val options = listOf(getString(R.string.btcpay_pos_none_option)) + apps.map { it.name }
        val currentIndex = apps.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it + 1 }
        var selectedIndex = currentIndex

        AlertDialog.Builder(this)
            .setTitle(R.string.btcpay_pos_select_title)
            .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex == 0) {
                    clearPosSelection()
                } else {
                    val app = apps[selectedIndex - 1]
                    securePrefs.putString(KEY_POS_APP_ID, app.id)
                    securePrefs.putString(KEY_POS_APP_NAME, app.name)
                    posAppSubtitle.text = app.name
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testConnection(onSuccess: (() -> Unit)? = null) {
        val serverUrl = serverUrlInput.text.toString().trim().trimEnd('/')
        val apiKey = apiKeyInput.text.toString().trim()
        val storeId = storeIdInput.text.toString().trim()

        if (serverUrl.isBlank() || apiKey.isBlank() || storeId.isBlank()) {
            testConnectionStatus.text = getString(R.string.btcpay_test_fill_all_fields)
            testConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_error))
            return
        }

        testConnectionStatus.text = getString(R.string.btcpay_test_connecting)
        testConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("$serverUrl/api/v1/stores/$storeId/invoices")
                        .header("Authorization", "token $apiKey")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val code = response.code
                    response.close()

                    if (code in 200..299) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("HTTP $code"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            if (result.isSuccess) {
                connectionTestPassed = true
                updateToggleEnabled()
                onSuccess?.invoke()
                testConnectionStatus.text = getString(R.string.btcpay_test_success)
                testConnectionStatus.setTextColor(ContextCompat.getColor(this@BtcPaySettingsActivity, R.color.color_success_green))
            } else {
                val error = result.exceptionOrNull()?.message ?: getString(R.string.btcpay_test_unknown_error)
                testConnectionStatus.text = getString(R.string.btcpay_test_failed, error)
                testConnectionStatus.setTextColor(ContextCompat.getColor(this@BtcPaySettingsActivity, R.color.color_error))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveTextFields()
    }
}

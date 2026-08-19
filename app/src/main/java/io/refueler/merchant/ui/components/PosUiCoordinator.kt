package io.refueler.merchant.ui.components

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import io.refueler.merchant.R
import io.refueler.merchant.core.network.SupabaseClient
import io.refueler.merchant.core.util.NetworkUtils
import io.refueler.merchant.core.worker.BitcoinPriceWorker
import io.refueler.merchant.feature.history.PaymentsHistoryActivity
import io.refueler.merchant.feature.items.ItemSelectionActivity
import io.refueler.merchant.feature.order.FloorOrderActivity
import io.refueler.merchant.feature.settings.SettingsActivity
import io.refueler.merchant.ui.theme.ThemeManager

/**
 * Coordinates all UI managers and handles main POS interface logic.
 *
 * NumoPay-C: Cashu wallet, NFC payment processor, PaymentMethodHandler,
 * PaymentResultHandler, MintManager all removed. Charge button now
 * launches FloorOrderActivity (Lightning QR / cash / card flow).
 */
class PosUiCoordinator(
    private val activity: AppCompatActivity,
    private val bitcoinPriceWorker: BitcoinPriceWorker?
) {

    // UI Components
    private lateinit var amountDisplay: TextView
    private lateinit var secondaryAmountDisplay: TextView
    private lateinit var submitButton: Button
    private lateinit var submitButtonSpinner: ProgressBar
    private lateinit var switchCurrencyButton: View
    private lateinit var inputModeContainer: ConstraintLayout
    private lateinit var errorMessage: TextView

    // Input state
    private val satoshiInput = StringBuilder()
    private val fiatInput = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Manager instances
    private lateinit var amountDisplayManager: AmountDisplayManager
    private lateinit var keypadManager: KeypadManager
    private lateinit var themeManager: ThemeManager

    /** Initialize all UI components and managers */
    fun initialize() {
        initializeViews()
        initializeManagers()
        setupNavigationButtons()

        // Enable charge button — no wallet-readiness gate, network check at tap time
        submitButton.isEnabled = true
        submitButton.alpha = 1.0f
    }

    /** Handle initial payment amount from basket (pre-order flow) */
    fun handleInitialPaymentAmount(paymentAmount: Long) {
        resetToInputMode()
        if (paymentAmount > 0) {
            satoshiInput.clear()
            satoshiInput.append(paymentAmount.toString())
            fiatInput.clear()
            amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
            Handler(Looper.getMainLooper()).postDelayed({
                if (submitButton.isEnabled) {
                    launchFloorOrder()
                }
            }, 500)
        } else {
            amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
        }
    }

    /** Reset to input mode */
    fun resetToInputMode() {
        inputModeContainer.visibility = View.VISIBLE
        satoshiInput.clear()
        fiatInput.clear()
        amountDisplayManager.resetRequestedAmount()
        amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
        hideChargeButtonSpinner()
    }

    /** Show amount required error */
    fun showAmountRequiredError() {
        errorMessage.visibility = View.VISIBLE
        amountDisplayManager.shakeAmountDisplay()
        mainHandler.postDelayed({
            errorMessage.visibility = View.GONE
        }, 3000)
    }

    /** Apply theme to all components */
    fun applyTheme() {
        themeManager.applyTheme(amountDisplay, secondaryAmountDisplay, errorMessage, switchCurrencyButton, submitButton)
    }

    /** Refresh the display when currency or other settings may have changed */
    fun refreshDisplay() {
        amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)
    }

    /** Stop services — no-op in NumoPay-C (NFC HCE removed) */
    fun stopServices() {
        // NFC HCE removed. Nothing to stop.
    }

    /** Get requested amount */
    fun getRequestedAmount(): Long = amountDisplayManager.requestedAmount

    /** Hide spinner on charge button and re-enable */
    fun hideChargeButtonSpinner() {
        submitButtonSpinner.visibility = View.GONE
        submitButton.text = activity.getString(R.string.pos_charge_button)
        submitButton.isEnabled = true
        submitButton.alpha = 1.0f
    }

    companion object {
        private const val TAG = "PosUiCoordinator"
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    private fun launchFloorOrder() {
        val amount = amountDisplayManager.requestedAmount
        if (amount <= 0) {
            showAmountRequiredError()
            return
        }
        showChargeButtonSpinner()
        val intent = Intent(activity, FloorOrderActivity::class.java).apply {
            putExtra(FloorOrderActivity.EXTRA_AMOUNT_GBP, amountToGbp(amount))
        }
        activity.startActivity(intent)
        // Spinner hidden on resume
    }

    private fun amountToGbp(sats: Long): Double {
        val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
        if (price <= 0.0) return 0.0
        return (sats.toDouble() / 100_000_000.0) * price
    }

    private fun showChargeButtonSpinner() {
        submitButtonSpinner.visibility = View.VISIBLE
        submitButton.text = ""
        submitButton.isEnabled = false
    }

    private fun initializeViews() {
        amountDisplay = activity.findViewById(R.id.amount_display)
        secondaryAmountDisplay = activity.findViewById(R.id.secondary_amount_display)
        submitButton = activity.findViewById(R.id.submit_button)
        submitButtonSpinner = activity.findViewById(R.id.submit_button_spinner)
        errorMessage = activity.findViewById(R.id.error_message)
        switchCurrencyButton = activity.findViewById(R.id.currency_switch_button)
        inputModeContainer = activity.findViewById(R.id.input_mode_container)

        val iconOffsetPx = 2f * activity.resources.displayMetrics.density
        switchCurrencyButton.translationY = iconOffsetPx
    }

    private fun initializeManagers() {
        themeManager = ThemeManager(activity)
        themeManager.applyTheme(amountDisplay, secondaryAmountDisplay, errorMessage, switchCurrencyButton, submitButton)

        amountDisplayManager = AmountDisplayManager(
            activity, amountDisplay, secondaryAmountDisplay, switchCurrencyButton, submitButton, bitcoinPriceWorker
        )
        amountDisplayManager.initializeInputMode()

        val keypad: GridLayout = activity.findViewById(R.id.keypad)
        keypadManager = KeypadManager(activity, keypad) { label ->
            keypadManager.handleKeypadInput(label, satoshiInput, fiatInput, amountDisplayManager.isUsdInputMode)
            amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.DIGIT_ENTRY)
        }
    }

    private fun setupNavigationButtons() {
        val secondaryAmountContainer = activity.findViewById<View>(R.id.secondary_amount_container)
        secondaryAmountContainer.setOnClickListener {
            if (amountDisplayManager.toggleInputMode(satoshiInput, fiatInput)) {
                amountDisplayManager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.CURRENCY_SWITCH)
            }
        }

        activity.findViewById<ImageButton>(R.id.action_more_options)?.setOnClickListener {
            showOverflowMenu(it)
        }
        activity.findViewById<ImageButton>(R.id.action_history)?.setOnClickListener {
            activity.startActivity(Intent(activity, PaymentsHistoryActivity::class.java))
        }
        activity.findViewById<ImageButton>(R.id.action_catalog)?.setOnClickListener {
            activity.startActivity(Intent(activity, ItemSelectionActivity::class.java))
        }
        activity.findViewById<ImageButton>(R.id.action_settings)?.setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }

        submitButton.setOnClickListener {
            if (!NetworkUtils.isNetworkAvailable(activity)) {
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.pos_error_no_network_charge),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (amountDisplayManager.requestedAmount > 0) {
                launchFloorOrder()
            } else {
                showAmountRequiredError()
            }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.overflow_menu, popup.menu)
        popup.setOnMenuItemClickListener { false }
        popup.show()
    }
}

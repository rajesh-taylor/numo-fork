package io.refueler.merchant

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import io.refueler.merchant.core.worker.BitcoinPriceWorker
import io.refueler.merchant.feature.history.PaymentsHistoryActivity
import io.refueler.merchant.ui.components.PosUiCoordinator
import android.os.Vibrator

/**
 * Primary POS screen for floor staff.
 *
 * NumoPay-B scope: shell, lifecycle, window settings, BitcoinPriceWorker,
 * offline-mode banner, FLAG_KEEP_SCREEN_ON.
 *
 * Removed in NumoPay-B:
 *   - CashuWalletManager.init()
 *   - AutoWithdrawManager + AutoWithdrawProgressListener
 *   - NFC HCE: NfcAdapter, CardEmulation, NdefHostCardEmulationService
 *   - BTCMap banner (layout_btcmap_banner, BtcMapExplainerActivity)
 *   - setupThemeSettings() / AppCompatDelegate night-mode toggle
 *   - ThemeManager.resolveBackgroundColor() — replaced with hardcoded Carbon #1A1A1A
 *   - PaymentMethodHandler.REQUEST_CODE_PAYMENT onActivityResult branch
 *
 * NumoPay-C will add:
 *   - ItemListActivity / basket integration
 *   - create-order EF call + LNURL QR display
 *   - Supabase Realtime poll for payment confirmation
 *   - Cash / card record-only flow
 *
 * The offline-mode banner is shown when PinEntryActivity signals that the
 * shift-start PIN was verified via the local grant (server unreachable).
 */
class ModernPOSActivity : AppCompatActivity() {

    private var bitcoinPriceWorker: BitcoinPriceWorker? = null
    private var vibrator: Vibrator? = null

    private lateinit var uiCoordinator: PosUiCoordinator
    private var offlineBanner: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Carbon is the always-on theme. No toggle, no AppCompatDelegate night-mode.
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor(COLOR_BG_CARBON)))

        setContentView(R.layout.activity_modern_pos)

        // Keep screen on while the app is foregrounded — no mid-shift re-auth.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupWindowInsets()
        setupBitcoinPriceWorker()

        uiCoordinator = PosUiCoordinator(this, bitcoinPriceWorker)
        uiCoordinator.initialize()

        val paymentAmount = intent.getLongExtra("EXTRA_PAYMENT_AMOUNT", 0L)
        uiCoordinator.handleInitialPaymentAmount(paymentAmount)

        vibrator = io.refueler.merchant.util.getVibrator(this)

        // Show offline banner if shift-start PIN was verified via local grant
        if (intent.getBooleanExtra(EXTRA_OFFLINE_MODE, false)) {
            showOfflineBanner()
        }
    }

    // ------------------------------------------------------------------
    // Window setup
    // ------------------------------------------------------------------

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ------------------------------------------------------------------
    // Bitcoin price worker
    // ------------------------------------------------------------------

    private fun setupBitcoinPriceWorker() {
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this).also { worker ->
            worker.setPriceUpdateListener(object : BitcoinPriceWorker.PriceUpdateListener {
                override fun onPriceUpdated(price: Double) {
                    // Delegated to uiCoordinator in NumoPay-C
                }
            })
            worker.start()
        }
    }

    // ------------------------------------------------------------------
    // Offline banner
    // ------------------------------------------------------------------

    private fun showOfflineBanner() {
        // Lightweight banner wired to the existing layout's offline indicator
        // view (id: offline_mode_banner). If the layout doesn't have it yet,
        // this is a no-op — add the view id in the NumoPay-C layout pass.
        val banner = findViewById<View>(R.id.offline_mode_banner)
        if (banner != null) {
            banner.visibility = View.VISIBLE
        }
    }

    private fun hideOfflineBanner() {
        findViewById<View>(R.id.offline_mode_banner)?.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        uiCoordinator.applyTheme()
        uiCoordinator.refreshDisplay()
        // NFC HCE removed — no NfcAdapter calls here.
    }

    override fun onPause() {
        super.onPause()
        // NFC HCE removed — no unsetPreferredService call here.
    }

    override fun onDestroy() {
        uiCoordinator.stopServices()
        bitcoinPriceWorker?.stop()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_history -> {
            startActivity(Intent(this, PaymentsHistoryActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val TAG = "ModernPOSActivity"

        /** Refueler Carbon bg — hardcoded, no ThemeManager needed. */
        private const val COLOR_BG_CARBON = "#1A1A1A"

        /** Set by PinEntryActivity when proceeding via offline local grant. */
        const val EXTRA_OFFLINE_MODE = "extra_offline_mode"
    }
}

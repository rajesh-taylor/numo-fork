package io.refueler.merchant.feature.order

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Vibrator
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.refueler.merchant.R
import io.refueler.merchant.core.network.SupabaseClient
import io.refueler.merchant.core.network.SupabaseException
import android.content.Context
import android.os.Build
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Floor order payment screen.
 *
 * Launched by ModernPOSActivity when staff tap Charge.
 *
 * Receives:
 *   EXTRA_AMOUNT_GBP  — basket total as Double
 *   EXTRA_ITEMS_JSON  — JSON array of {name, price_gbp} for receipt context
 *
 * Two flows:
 *
 * Lightning — calls create-order EF with origin:'floor', receives BOLT11 invoice,
 * displays QR. Polls merchant_orders by order_code every 2s for up to 90s.
 * On confirmed: vibrate + success screen. On timeout: "check with customer" message.
 *
 * Cash / Card — direct PostgREST insert to merchant_orders with
 * payment_method:'cash'|'card_external', status:'confirmed', origin:'floor'.
 * No QR, no poll. Shows confirmation immediately.
 *
 * Architecture note (ADR §2): this device is never in the custody chain.
 * The BOLT11 goes to venue_partners.lightning_address via LNURL-pay.
 * Refueler's Blink float is not touched here.
 */
class FloorOrderActivity : AppCompatActivity() {

    // ------------------------------------------------------------------
    // Extras
    // ------------------------------------------------------------------

    companion object {
        const val EXTRA_AMOUNT_GBP = "extra_amount_gbp"
        const val EXTRA_ITEMS_JSON = "extra_items_json"

        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_TIMEOUT_MS = 90_000L
        private const val QR_SIZE_PX = 600
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private var amountGbp: Double = 0.0
    private var itemsJson: String = "[]"
    private var currentOrderCode: String? = null

    private var pollJob: Job? = null
    private var countdownTimer: CountDownTimer? = null
    private var vibrator: Vibrator? = null

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    // Method choice panel
    private lateinit var panelMethodChoice: LinearLayout
    private lateinit var tvTotal: TextView
    private lateinit var btnLightning: Button
    private lateinit var btnCash: Button
    private lateinit var btnCard: Button

    // Lightning QR panel
    private lateinit var panelLightning: LinearLayout
    private lateinit var ivQr: ImageView
    private lateinit var tvLightningStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var progressLightning: ProgressBar
    private lateinit var btnCancelLightning: Button

    // Success panel
    private lateinit var panelSuccess: LinearLayout
    private lateinit var tvSuccessMessage: TextView
    private lateinit var btnDone: Button

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floor_order)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        amountGbp = intent.getDoubleExtra(EXTRA_AMOUNT_GBP, 0.0)
        itemsJson = intent.getStringExtra(EXTRA_ITEMS_JSON) ?: "[]"
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }

        bindViews()
        showMethodChoice()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        countdownTimer?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // View binding
    // ------------------------------------------------------------------

    private fun bindViews() {
        panelMethodChoice = findViewById(R.id.panel_method_choice)
        tvTotal = findViewById(R.id.tv_total)
        btnLightning = findViewById(R.id.btn_pay_lightning)
        btnCash = findViewById(R.id.btn_pay_cash)
        btnCard = findViewById(R.id.btn_pay_card)

        panelLightning = findViewById(R.id.panel_lightning)
        ivQr = findViewById(R.id.iv_qr)
        tvLightningStatus = findViewById(R.id.tv_lightning_status)
        tvCountdown = findViewById(R.id.tv_countdown)
        progressLightning = findViewById(R.id.progress_lightning)
        btnCancelLightning = findViewById(R.id.btn_cancel_lightning)

        panelSuccess = findViewById(R.id.panel_success)
        tvSuccessMessage = findViewById(R.id.tv_success_message)
        btnDone = findViewById(R.id.btn_done)
    }

    // ------------------------------------------------------------------
    // Method choice panel
    // ------------------------------------------------------------------

    private fun showMethodChoice() {
        panelMethodChoice.visibility = View.VISIBLE
        panelLightning.visibility = View.GONE
        panelSuccess.visibility = View.GONE

        tvTotal.text = formatGbp(amountGbp)

        btnLightning.setOnClickListener { startLightningFlow() }
        btnCash.setOnClickListener { recordCashCard("cash") }
        btnCard.setOnClickListener { recordCashCard("card_external") }
    }

    // ------------------------------------------------------------------
    // Lightning flow
    // ------------------------------------------------------------------

    private fun startLightningFlow() {
        panelMethodChoice.visibility = View.GONE
        panelLightning.visibility = View.VISIBLE
        panelSuccess.visibility = View.GONE

        tvLightningStatus.text = getString(R.string.floor_order_lightning_waiting)
        progressLightning.visibility = View.VISIBLE
        ivQr.visibility = View.GONE
        tvCountdown.visibility = View.GONE
        btnCancelLightning.setOnClickListener { cancelLightningFlow() }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = buildCreateOrderBody()
                val responseJson = SupabaseClient.edgeFunctionPost(
                    context = this@FloorOrderActivity,
                    functionName = "create-order",
                    body = body
                )
                val resp = Gson().fromJson(responseJson, JsonObject::class.java)
                val invoice = resp.get("invoice")?.asString
                    ?: throw IllegalStateException("No invoice in create-order response")
                val orderCode = resp.get("order_code")?.asString
                    ?: throw IllegalStateException("No order_code in create-order response")

                currentOrderCode = orderCode

                withContext(Dispatchers.Main) {
                    showQr(invoice)
                    startPoll(orderCode)
                }
            } catch (e: Exception) {
                val msg = if (e is SupabaseException) e.responseBody else e.message ?: "Error"
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FloorOrderActivity,
                        getString(R.string.floor_order_lightning_error, msg),
                        Toast.LENGTH_LONG
                    ).show()
                    showMethodChoice()
                }
            }
        }
    }

    private fun buildCreateOrderBody(): String {
        val venueId = SupabaseClient.venueId(this)
        val obj = JsonObject().apply {
            addProperty("venue_id", venueId)
            addProperty("amount_gbp", amountGbp)
            addProperty("origin", "floor")
        }
        return Gson().toJson(obj)
    }

    private fun showQr(invoice: String) {
        progressLightning.visibility = View.GONE
        ivQr.visibility = View.VISIBLE
        tvLightningStatus.text = getString(R.string.floor_order_lightning_scan)
        tvCountdown.visibility = View.VISIBLE

        val bitmap = generateQrBitmap(invoice)
        ivQr.setImageBitmap(bitmap)
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints)
        val bitmap = Bitmap.createBitmap(QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.RGB_565)
        for (x in 0 until QR_SIZE_PX) {
            for (y in 0 until QR_SIZE_PX) {
                // Carbon bg (#1A1A1A) with Paper (#E8E2D8) modules — readable on dark screen
                bitmap.setPixel(x, y, if (bits[x, y]) Color.parseColor("#E8E2D8") else Color.parseColor("#1A1A1A"))
            }
        }
        return bitmap
    }

    // ------------------------------------------------------------------
    // Polling — Realtime not available on plain OkHttp; use PostgREST poll
    // with 2s interval, 90s max. ADR §2 fallback pattern.
    // ------------------------------------------------------------------

    private fun startPoll(orderCode: String) {
        val startMs = System.currentTimeMillis()

        // Countdown timer for UI only
        countdownTimer = object : CountDownTimer(POLL_TIMEOUT_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                tvCountdown.text = "${millisUntilFinished / 1_000}s"
            }
            override fun onFinish() { /* pollJob handles timeout */ }
        }.start()

        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= POLL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { onLightningTimeout() }
                    break
                }

                try {
                    val json = SupabaseClient.postgrestGet(
                        context = this@FloorOrderActivity,
                        table = "merchant_orders",
                        query = "order_code=eq.$orderCode&select=status,settled_sats,routing_fee_sats"
                    )
                    val rows = Gson().fromJson(json, Array<JsonObject>::class.java)
                    val status = rows.firstOrNull()?.get("status")?.asString
                    if (status == "confirmed") {
                        val settledSats = rows.first().get("settled_sats")?.asLong ?: 0L
                        val feeSats = rows.first().get("routing_fee_sats")?.asLong
                        withContext(Dispatchers.Main) {
                            onLightningConfirmed(settledSats, feeSats)
                        }
                        break
                    }
                } catch (e: Exception) {
                    // Network blip — keep polling
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun onLightningConfirmed(settledSats: Long, feeSats: Long?) {
        countdownTimer?.cancel()
        pollJob?.cancel()

        @Suppress("DEPRECATION")
        vibrator?.vibrate(200)

        val feeText = when {
            feeSats == null || feeSats == 0L -> "fee: pending"
            else -> "routing fee: ${"%,d".format(feeSats)} sats"
        }
        val msg = "${getString(R.string.floor_order_lightning_success)}\n" +
                "${"%,d".format(settledSats)} sats received  ·  $feeText"
        showSuccess(msg)
    }

    private fun onLightningTimeout() {
        countdownTimer?.cancel()
        pollJob?.cancel()
        tvCountdown.visibility = View.GONE
        tvLightningStatus.text = getString(R.string.floor_order_lightning_timeout)
        btnCancelLightning.text = getString(android.R.string.ok)
        btnCancelLightning.setOnClickListener { showMethodChoice() }
    }

    private fun cancelLightningFlow() {
        pollJob?.cancel()
        countdownTimer?.cancel()
        currentOrderCode = null
        showMethodChoice()
    }

    // ------------------------------------------------------------------
    // Cash / Card record-only flow
    // ------------------------------------------------------------------

    private fun recordCashCard(paymentMethod: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val venueId = SupabaseClient.venueId(this@FloorOrderActivity)
                val body = JsonObject().apply {
                    addProperty("venue_id", venueId)
                    addProperty("payment_method", paymentMethod)
                    addProperty("status", "confirmed")
                    addProperty("origin", "floor")
                    addProperty("amount_gbp", amountGbp)
                    // settled_sats and routing_fee_sats left null — external payment
                }.let { Gson().toJson(it) }

                SupabaseClient.postgrestPost(
                    context = this@FloorOrderActivity,
                    table = "merchant_orders",
                    body = body
                )

                withContext(Dispatchers.Main) {
                    val msg = when (paymentMethod) {
                        "cash" -> getString(R.string.floor_order_cash_confirmed)
                        else -> getString(R.string.floor_order_card_confirmed)
                    }
                    showSuccess(msg)
                }
            } catch (e: Exception) {
                val msg = if (e is SupabaseException) e.responseBody else e.message ?: "Error"
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FloorOrderActivity,
                        getString(R.string.floor_order_record_error, msg),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Success panel
    // ------------------------------------------------------------------

    private fun showSuccess(message: String) {
        panelMethodChoice.visibility = View.GONE
        panelLightning.visibility = View.GONE
        panelSuccess.visibility = View.VISIBLE

        tvSuccessMessage.text = message
        btnDone.setOnClickListener { finish() }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun formatGbp(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.UK).format(amount)
}

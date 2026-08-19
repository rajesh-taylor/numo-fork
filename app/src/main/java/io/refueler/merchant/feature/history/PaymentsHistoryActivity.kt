package io.refueler.merchant.feature.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.refueler.merchant.R
import io.refueler.merchant.core.data.repository.MerchantOrder
import io.refueler.merchant.core.data.repository.MerchantOrdersRepository
import io.refueler.merchant.core.network.SupabaseException
import io.refueler.merchant.databinding.ActivityHistoryBinding
import io.refueler.merchant.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Payment history screen for floor staff.
 *
 * NumoPay-C: data source replaced with merchant_orders via MerchantOrdersRepository.
 * Removed: CashuWalletManager, AutoWithdrawManager, WithdrawHistoryEntry,
 *           ActivityCsvExportHelper (Cashu-based), BalanceRefreshBroadcast.
 *
 * Retained: ActivityHistoryBinding layout, date-range filter (day/week/month),
 *           CSV export (now writes merchant_orders data), back button.
 *
 * The static getPaymentHistory(context) method that InsightsRepository was calling
 * is intentionally NOT reproduced here — InsightsActivity now calls
 * MerchantOrdersRepository directly, as does this activity.
 */
class PaymentsHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private var orders: List<MerchantOrder> = emptyList()

    private val csvExportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportCsv(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        binding.backButton?.setOnClickListener { finish() }

        binding.transactionsRecycler?.layoutManager = LinearLayoutManager(this)
        binding.transactionsRecycler?.adapter = OrderHistoryAdapter(emptyList())

        // CSV export button (optional — only shown if the view exists in layout)
        binding.exportCsvButton?.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date())
            csvExportLauncher.launch("refueler-history-$timestamp.csv")
        }

        // Filter buttons (optional — only shown if views exist in layout)
        binding.filterDay?.setOnClickListener { loadOrders(daysBack = 1) }
        binding.filterWeek?.setOnClickListener { loadOrders(daysBack = 7) }
        binding.filterMonth?.setOnClickListener { loadOrders(daysBack = 30) }

        loadOrders(daysBack = 7) // Default: last 7 days
    }

    override fun onResume() {
        super.onResume()
        loadOrders(daysBack = 7)
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private fun loadOrders(daysBack: Int) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    MerchantOrdersRepository.fetchConfirmed(this@PaymentsHistoryActivity)
                }
                // Client-side date filter
                val cutoff = System.currentTimeMillis() - daysBack.toLong() * 86_400_000L
                orders = result.filter { it.date.time >= cutoff }
                render()
            } catch (e: Exception) {
                val msg = if (e is SupabaseException) e.responseBody else e.message ?: "Error"
                Toast.makeText(
                    this@PaymentsHistoryActivity,
                    getString(R.string.history_load_error, msg),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render() {
        if (orders.isEmpty()) {
            binding.emptyState?.visibility = View.VISIBLE
            binding.transactionsRecycler?.visibility = View.GONE
        } else {
            binding.emptyState?.visibility = View.GONE
            binding.transactionsRecycler?.visibility = View.VISIBLE
            (binding.transactionsRecycler?.adapter as? OrderHistoryAdapter)?.update(orders)
        }
    }

    // ------------------------------------------------------------------
    // CSV export
    // ------------------------------------------------------------------

    private fun exportCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val csv = buildCsvString(orders)
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaymentsHistoryActivity, "Exported", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaymentsHistoryActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildCsvString(rows: List<MerchantOrder>): String {
        val sb = StringBuilder()
        sb.appendLine("order_code,date,status,origin,payment_method,amount_gbp,settled_sats,routing_fee_sats")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        rows.forEach { o ->
            sb.appendLine(
                listOf(
                    o.orderCode,
                    dateFmt.format(o.date),
                    o.status,
                    o.origin,
                    o.paymentMethod ?: "lightning",
                    o.amountGbp?.toString() ?: "",
                    o.settledSats?.toString() ?: "",
                    o.routingFeeSats?.toString() ?: ""
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private fun formatGbp(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.UK).format(amount)

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    inner class OrderHistoryAdapter(private var items: List<MerchantOrder>) :
        RecyclerView.Adapter<OrderHistoryAdapter.VH>() {

        fun update(newItems: List<MerchantOrder>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_entry, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvLabel: TextView? = view.findViewById(R.id.entry_label)
            private val tvAmount: TextView? = view.findViewById(R.id.entry_amount)
            private val tvDate: TextView? = view.findViewById(R.id.entry_date)
            private val tvSecondary: TextView? = view.findViewById(R.id.entry_secondary)

            fun bind(order: MerchantOrder) {
                tvLabel?.text = order.orderCode

                val amountText = when {
                    order.isLightning && (order.settledSats ?: 0L) > 0L -> {
                        val satsStr = "%,d".format(order.settledSats)
                        val feeStr = when {
                            (order.routingFeeSats ?: 0L) > 0L ->
                                "  ·  fee: %,d sats".format(order.routingFeeSats)
                            else -> "  ·  fee: pending"
                        }
                        "$satsStr sats$feeStr"
                    }
                    order.amountGbp != null -> formatGbp(order.amountGbp)
                    else -> "—"
                }
                tvAmount?.text = amountText

                tvDate?.text = SimpleDateFormat("HH:mm  dd MMM", Locale.UK).format(order.date)

                val methodLabel = when {
                    order.isCash -> getString(R.string.history_payment_cash)
                    order.isCard -> getString(R.string.history_payment_card)
                    else -> getString(R.string.history_payment_lightning)
                }
                val originLabel = when {
                    order.isFloor -> getString(R.string.history_origin_floor)
                    else -> getString(R.string.history_origin_preorder)
                }
                tvSecondary?.text = "$originLabel  ·  $methodLabel"
            }
        }
    }

    companion object {
        /**
         * Stub — legacy callers (PaymentFailureActivity, PaymentReceivedActivity)
         * referenced this static method. Returns empty list; those activities
         * will be removed in the same commit.
         */
        @JvmStatic
        fun getPaymentHistory(context: android.content.Context): List<Any> = emptyList()
    }
}

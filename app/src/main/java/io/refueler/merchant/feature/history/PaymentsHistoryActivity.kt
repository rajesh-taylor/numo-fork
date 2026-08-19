package io.refueler.merchant.feature.history

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
 * NumoPay-C: reads from merchant_orders via MerchantOrdersRepository.
 * Uses actual activity_history.xml binding IDs:
 *   historyRecyclerView, emptyView (+ top_bar, overflow_button retained from layout)
 *
 * Removed: CashuWalletManager, AutoWithdrawManager, WithdrawHistoryEntry,
 *           ActivityCsvExportHelper, BalanceRefreshBroadcast.
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

        // Back button uses top_bar back arrow (overflow_button repurposed as back)
        binding.overflowButton?.setOnClickListener { finish() }

        // Hide Cashu-specific views that exist in the layout but have no data
        binding.balanceSection?.visibility = View.GONE
        binding.insightsButton?.visibility = View.GONE
        binding.filterHeader?.visibility = View.GONE
        binding.filtersContainer?.visibility = View.GONE

        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = OrderHistoryAdapter(emptyList())

        loadOrders()
    }

    override fun onResume() {
        super.onResume()
        loadOrders()
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private fun loadOrders() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    MerchantOrdersRepository.fetchConfirmed(this@PaymentsHistoryActivity)
                }
                orders = result
                render()
            } catch (e: Exception) {
                val msg = if (e is SupabaseException) e.responseBody else e.message ?: "Error"
                Toast.makeText(this@PaymentsHistoryActivity,
                    "Could not load history: $msg", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render() {
        if (orders.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.historyRecyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.historyRecyclerView.visibility = View.VISIBLE
            (binding.historyRecyclerView.adapter as? OrderHistoryAdapter)?.update(orders)
        }
    }

    // ------------------------------------------------------------------
    // CSV export
    // ------------------------------------------------------------------

    private fun exportCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val csv = buildCsvString(orders)
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaymentsHistoryActivity, "Exported", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaymentsHistoryActivity,
                        "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildCsvString(rows: List<MerchantOrder>): String {
        val sb = StringBuilder()
        sb.appendLine("order_code,date,status,origin,payment_method,amount_gbp,settled_sats,routing_fee_sats")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        rows.forEach { o ->
            sb.appendLine(listOf(
                o.orderCode, dateFmt.format(o.date), o.status, o.origin,
                o.paymentMethod ?: "lightning",
                o.amountGbp?.toString() ?: "",
                o.settledSats?.toString() ?: "",
                o.routingFeeSats?.toString() ?: ""
            ).joinToString(","))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private fun formatGbp(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.UK).format(amount)

    // ------------------------------------------------------------------
    // Companion — legacy stub
    // ------------------------------------------------------------------

    companion object {
        @JvmStatic
        fun getPaymentHistory(context: android.content.Context): List<Any> = emptyList()
    }

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
                .inflate(io.refueler.merchant.R.layout.item_history_entry, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvLabel: TextView? = view.findViewById(io.refueler.merchant.R.id.entry_label)
            private val tvAmount: TextView? = view.findViewById(io.refueler.merchant.R.id.entry_amount)
            private val tvDate: TextView? = view.findViewById(io.refueler.merchant.R.id.entry_date)
            private val tvSecondary: TextView? = view.findViewById(io.refueler.merchant.R.id.entry_secondary)

            fun bind(order: MerchantOrder) {
                tvLabel?.text = order.orderCode
                val amountText = when {
                    order.isLightning && (order.settledSats ?: 0L) > 0L -> {
                        val fee = if ((order.routingFeeSats ?: 0L) > 0L)
                            "  ·  fee: %,d sats".format(order.routingFeeSats)
                        else "  ·  fee: pending"
                        "%,d sats$fee".format(order.settledSats)
                    }
                    order.amountGbp != null -> formatGbp(order.amountGbp)
                    else -> "—"
                }
                tvAmount?.text = amountText
                tvDate?.text = SimpleDateFormat("HH:mm  dd MMM", Locale.UK).format(order.date)
                val method = when {
                    order.isCash -> "Cash"
                    order.isCard -> "Card"
                    else -> "Lightning"
                }
                tvSecondary?.text = "${if (order.isFloor) "Floor" else "Pre-order"}  ·  $method"
            }
        }
    }
}

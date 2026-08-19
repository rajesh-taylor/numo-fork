package io.refueler.merchant.feature.insights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.refueler.merchant.core.data.repository.MerchantOrder
import io.refueler.merchant.core.data.repository.MerchantOrdersRepository
import io.refueler.merchant.core.network.SupabaseException
import io.refueler.merchant.databinding.ActivityInsightsBinding
import io.refueler.merchant.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Insights / analytics screen.
 *
 * NumoPay-C: reads from merchant_orders via MerchantOrdersRepository.
 * Uses actual activity_insights.xml binding IDs:
 *   backButton, viewOptionsButton, barChart, statPair, statLabel,
 *   statValue, statSecondary, statSecondaryValue, transactionsRecycler, emptyText
 *
 * Removed: CashuWalletManager, BalanceRefreshBroadcast, InsightsRepository (Cashu).
 * Bar chart hidden — not wired to Supabase data in v1.
 */
class InsightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInsightsBinding

    private var range: Range = Range.DAY
    private var orders: List<MerchantOrder> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        binding.backButton.setOnClickListener { finish() }
        binding.viewOptionsButton?.setOnClickListener { cycleRange() }

        // Bar chart hidden — not wired in v1
        binding.barChart?.visibility = View.GONE

        binding.transactionsRecycler?.layoutManager = LinearLayoutManager(this)
        binding.transactionsRecycler?.adapter = MerchantOrderAdapter(emptyList())

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private fun refresh() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    MerchantOrdersRepository.fetchConfirmed(this@InsightsActivity)
                }
                orders = result
                render()
            } catch (e: Exception) {
                val msg = if (e is SupabaseException) e.responseBody else e.message ?: "Error"
                binding.emptyText?.text = "Could not load history: $msg"
                binding.emptyText?.visibility = View.VISIBLE
                binding.transactionsRecycler?.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render() {
        val filtered = filterForRange(orders, range)
        val totalGbp = filtered.sumOf { it.amountGbp ?: 0.0 }
        val lightningTotal = filtered.filter { it.isLightning }.sumOf { it.settledSats ?: 0L }

        binding.statLabel?.text = rangeLabel()
        binding.statValue?.text = formatGbp(totalGbp)
        binding.statPair?.visibility = View.VISIBLE

        if (lightningTotal > 0L) {
            binding.statSecondaryValue?.text = "%,d sats".format(lightningTotal)
            binding.statSecondary?.visibility = View.VISIBLE
        } else {
            binding.statSecondary?.visibility = View.GONE
        }

        if (filtered.isEmpty()) {
            binding.emptyText?.text = getString(io.refueler.merchant.R.string.history_empty)
            binding.emptyText?.visibility = View.VISIBLE
            binding.transactionsRecycler?.visibility = View.GONE
        } else {
            binding.emptyText?.visibility = View.GONE
            binding.transactionsRecycler?.visibility = View.VISIBLE
            (binding.transactionsRecycler?.adapter as? MerchantOrderAdapter)?.update(filtered)
        }
    }

    // ------------------------------------------------------------------
    // Range helpers
    // ------------------------------------------------------------------

    enum class Range { DAY, WEEK, MONTH }

    private fun cycleRange() {
        range = when (range) {
            Range.DAY -> Range.WEEK
            Range.WEEK -> Range.MONTH
            Range.MONTH -> Range.DAY
        }
        render()
    }

    private fun rangeLabel(): String = when (range) {
        Range.DAY -> "Today"
        Range.WEEK -> "This week"
        Range.MONTH -> "This month"
    }

    private fun filterForRange(all: List<MerchantOrder>, r: Range): List<MerchantOrder> {
        val cal = Calendar.getInstance()
        val now = cal.time
        val start: Date = when (r) {
            Range.DAY -> { cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.time }
            Range.WEEK -> { cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.time }
            Range.MONTH -> { cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.time }
        }
        return all.filter { it.date >= start && it.date <= now }
    }

    private fun formatGbp(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.UK).format(amount)

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    inner class MerchantOrderAdapter(private var items: List<MerchantOrder>) :
        RecyclerView.Adapter<MerchantOrderAdapter.VH>() {

        fun update(newItems: List<MerchantOrder>) { items = newItems; notifyDataSetChanged() }

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
                    order.isLightning && (order.settledSats ?: 0L) > 0L ->
                        "%,d sats".format(order.settledSats)
                    order.amountGbp != null -> formatGbp(order.amountGbp)
                    else -> "—"
                }
                tvAmount?.text = amountText
                tvDate?.text = SimpleDateFormat("HH:mm  dd MMM", Locale.UK).format(order.date)
                tvSecondary?.text = if (order.isFloor) "Floor" else "Pre-order"
            }
        }
    }
}

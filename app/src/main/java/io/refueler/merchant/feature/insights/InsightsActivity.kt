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
import io.refueler.merchant.R
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
 * NumoPay-C: data source replaced with merchant_orders via MerchantOrdersRepository.
 * Removed: CashuWalletManager, BalanceRefreshBroadcast, InsightsRepository (Cashu),
 * InsightsData, BucketTotal, InsightsRange, InsightsTransactionAdapter (Cashu).
 *
 * Replaced with: simple day/week/month aggregation over MerchantOrder list,
 * MerchantOrderAdapter for the recycler, GBP totals (sats secondary where available).
 *
 * The ActivityInsightsBinding layout is retained — we bind the same view IDs
 * from the existing layout. Chart (binding.barChart) is hidden in v1; it can
 * be re-enabled when a Supabase-native bar chart component is ready.
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

        // Range selector — reuse existing viewOptionsButton if present
        binding.viewOptionsButton?.setOnClickListener { cycleRange() }

        binding.transactionsRecycler.layoutManager = LinearLayoutManager(this)
        binding.transactionsRecycler.adapter = MerchantOrderAdapter(emptyList())

        // Hide bar chart — not wired to Supabase data in v1
        binding.barChart?.visibility = View.GONE

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
                showError(getString(R.string.history_load_error, msg))
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render() {
        val filtered = filterForRange(orders, range)

        val totalSats = filtered.sumOf { it.settledSats ?: 0L }
        val totalGbp = filtered.sumOf { it.amountGbp ?: 0.0 }
        val count = filtered.size

        // Primary stat — total GBP
        binding.statLabel?.text = rangeLabel()
        binding.primaryValue?.text = formatGbp(totalGbp)

        // Secondary stat — sats received (Lightning only)
        val lightningTotal = filtered.filter { it.isLightning }.sumOf { it.settledSats ?: 0L }
        if (lightningTotal > 0L) {
            binding.statSecondaryValue?.text = "${"%,d".format(lightningTotal)} sats"
            binding.statSecondary?.visibility = View.VISIBLE
        } else {
            binding.statSecondary?.visibility = View.GONE
        }

        // Order count
        binding.statPair?.visibility = View.VISIBLE

        if (filtered.isEmpty()) {
            binding.emptyText?.text = getString(R.string.history_empty)
            binding.emptyText?.visibility = View.VISIBLE
            binding.transactionsRecycler.visibility = View.GONE
        } else {
            binding.emptyText?.visibility = View.GONE
            binding.transactionsRecycler.visibility = View.VISIBLE
            (binding.transactionsRecycler.adapter as MerchantOrderAdapter).update(filtered)
        }
    }

    private fun showError(message: String) {
        binding.emptyText?.text = message
        binding.emptyText?.visibility = View.VISIBLE
        binding.transactionsRecycler.visibility = View.GONE
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
            Range.DAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.time
            }
            Range.WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.time
            }
            Range.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.time
            }
        }
        return all.filter { it.date >= start && it.date <= now }
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private fun formatGbp(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.UK).format(amount)

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    inner class MerchantOrderAdapter(private var items: List<MerchantOrder>) :
        RecyclerView.Adapter<MerchantOrderAdapter.VH>() {

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
        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvLabel: TextView? = view.findViewById(R.id.entry_label)
            private val tvAmount: TextView? = view.findViewById(R.id.entry_amount)
            private val tvDate: TextView? = view.findViewById(R.id.entry_date)
            private val tvOrigin: TextView? = view.findViewById(R.id.entry_secondary)

            fun bind(order: MerchantOrder) {
                tvLabel?.text = order.orderCode

                val amountText = when {
                    order.isLightning && (order.settledSats ?: 0L) > 0L ->
                        "${"%,d".format(order.settledSats)} sats"
                    order.amountGbp != null ->
                        formatGbp(order.amountGbp)
                    else -> "—"
                }
                tvAmount?.text = amountText

                tvDate?.text = SimpleDateFormat("HH:mm  dd MMM", Locale.UK).format(order.date)

                val originLabel = when {
                    order.isFloor && order.isCash -> "Floor · Cash"
                    order.isFloor && order.isCard -> "Floor · Card"
                    order.isFloor -> "Floor · Lightning"
                    else -> "Pre-order"
                }
                tvOrigin?.text = originLabel
            }
        }
    }
}

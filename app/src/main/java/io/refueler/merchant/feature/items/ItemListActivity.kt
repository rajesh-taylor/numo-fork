package io.refueler.merchant.feature.items

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.refueler.merchant.R
import io.refueler.merchant.core.network.SupabaseClient
import io.refueler.merchant.core.network.SupabaseException
import io.refueler.merchant.core.worker.BitcoinPriceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Catalogue screen for floor staff.
 *
 * NumoPay-C: data source is merchant_menu_items (Supabase PostgREST),
 * filtered by venue_id from EncryptedSharedPreferences and available=true.
 *
 * Write paths (add / edit / delete items) are locked out on the floor device.
 * Items are managed exclusively via Menu Management v1 on the counter terminal.
 *
 * When staff tap an item, it is added to the in-memory basket and the activity
 * returns RESULT_OK with EXTRA_ITEM_NAME and EXTRA_ITEM_PRICE_GBP so the
 * caller (ModernPOSActivity / basket) can accumulate a total.
 */
class ItemListActivity : AppCompatActivity() {

    // ------------------------------------------------------------------
    // Data model for merchant_menu_items rows
    // ------------------------------------------------------------------

    data class MenuItem(
        val id: String,
        val venue_id: String,
        val name: String,
        val description: String?,
        val price_gbp: Double,
        val available: Boolean,
        val category: String?,
        val display_order: Int?
    )

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingView: View
    private lateinit var categoryStrip: LinearLayout
    private lateinit var adapter: MenuItemAdapter

    private var allItems: List<MenuItem> = emptyList()
    private var selectedCategory: String? = null   // null = All
    private var btcPriceGbp: Double = 0.0
    private var loadJob: Job? = null

    private var bitcoinPriceWorker: BitcoinPriceWorker? = null

    companion object {
        const val EXTRA_ITEM_NAME = "extra_item_name"
        const val EXTRA_ITEM_PRICE_GBP = "extra_item_price_gbp"
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_list)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        recyclerView = findViewById(R.id.items_recycler_view)
        emptyView = findViewById(R.id.empty_text_view)
        loadingView = findViewById(R.id.loading_view)
        categoryStrip = findViewById(R.id.category_strip)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = MenuItemAdapter(emptyList()) { item -> onItemTapped(item) }
        recyclerView.adapter = adapter

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        // BitcoinPriceWorker for indicative sats display
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this).also { w ->
            w.setPriceUpdateListener(object : BitcoinPriceWorker.PriceUpdateListener {
                override fun onPriceUpdated(price: Double) {
                    btcPriceGbp = price
                    adapter.notifyDataSetChanged()
                }
            })
            w.start()
        }

        loadMenu()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        bitcoinPriceWorker?.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private fun loadMenu() {
        loadingView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        categoryStrip.visibility = View.GONE

        loadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val venueId = SupabaseClient.venueId(this@ItemListActivity)
                val json = SupabaseClient.postgrestGet(
                    context = this@ItemListActivity,
                    table = "merchant_menu_items",
                    query = "venue_id=eq.$venueId&available=eq.true&order=display_order.asc"
                )
                val type = object : TypeToken<List<MenuItem>>() {}.type
                val items: List<MenuItem> = Gson().fromJson(json, type)
                withContext(Dispatchers.Main) { onItemsLoaded(items) }
            } catch (e: Exception) {
                val msg = if (e is SupabaseException) e.responseBody else e.message ?: "Unknown error"
                withContext(Dispatchers.Main) { onLoadError(msg) }
            }
        }
    }

    private fun onItemsLoaded(items: List<MenuItem>) {
        loadingView.visibility = View.GONE
        allItems = items

        if (items.isEmpty()) {
            emptyView.text = getString(R.string.item_list_empty)
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            categoryStrip.visibility = View.GONE
            return
        }

        buildCategoryStrip(items)
        applyFilter()
        recyclerView.visibility = View.VISIBLE
    }

    private fun onLoadError(message: String) {
        loadingView.visibility = View.GONE
        emptyView.text = getString(R.string.item_list_load_error, message)
        emptyView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        Toast.makeText(this, getString(R.string.item_list_load_error, message), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Category strip
    // ------------------------------------------------------------------

    private fun buildCategoryStrip(items: List<MenuItem>) {
        categoryStrip.removeAllViews()
        val categories = listOf(null) + items.mapNotNull { it.category }.distinct().sorted()

        if (categories.size <= 2) {
            // Only "All" (and possibly one category) — no strip needed
            categoryStrip.visibility = View.GONE
            return
        }

        categoryStrip.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)

        categories.forEach { cat ->
            val chip = inflater.inflate(
                R.layout.item_category_chip, categoryStrip, false
            ) as TextView
            chip.text = cat ?: getString(R.string.history_origin_preorder).let { "All" }
            chip.isSelected = (cat == selectedCategory)
            chip.setOnClickListener {
                selectedCategory = cat
                updateChipSelection()
                applyFilter()
            }
            categoryStrip.addView(chip)
        }
    }

    private fun updateChipSelection() {
        for (i in 0 until categoryStrip.childCount) {
            val chip = categoryStrip.getChildAt(i) as? TextView ?: continue
            val catLabel = if (selectedCategory == null) "All" else selectedCategory
            chip.isSelected = chip.text.toString() == (catLabel ?: "All")
        }
    }

    private fun applyFilter() {
        val filtered = if (selectedCategory == null) allItems
        else allItems.filter { it.category == selectedCategory }
        adapter.updateItems(filtered)
    }

    // ------------------------------------------------------------------
    // Item tap — return to caller for basket accumulation
    // ------------------------------------------------------------------

    private fun onItemTapped(item: MenuItem) {
        val result = Intent().apply {
            putExtra(EXTRA_ITEM_NAME, item.name)
            putExtra(EXTRA_ITEM_PRICE_GBP, item.price_gbp)
        }
        setResult(Activity.RESULT_OK, result)
        // Don't finish — allow multiple items to be added.
        // Caller signals "done" via a separate Charge button.
        // For now: add to basket in ModernPOSActivity via broadcast / shared ViewModel.
        // Full basket wiring is NumoPay-C's FloorOrderActivity scope.
        Toast.makeText(
            this,
            "${item.name} — ${formatGbp(item.price_gbp)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    private fun formatGbp(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.UK).format(amount)

    private fun gbpToSats(amountGbp: Double): Long {
        if (btcPriceGbp <= 0.0) return 0L
        return ((amountGbp / btcPriceGbp) * 100_000_000L).toLong()
    }

    private fun formatSats(sats: Long): String =
        "%,d".format(sats)

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    inner class MenuItemAdapter(
        private var items: List<MenuItem>,
        private val onTap: (MenuItem) -> Unit
    ) : RecyclerView.Adapter<MenuItemAdapter.ViewHolder>() {

        fun updateItems(newItems: List<MenuItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_product, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView: TextView = view.findViewById(R.id.item_name)
            private val priceView: TextView = view.findViewById(R.id.item_price)
            private val variationView: TextView? = view.findViewById(R.id.item_variation)
            private val quantityView: TextView? = view.findViewById(R.id.item_quantity)
            private val divider: View? = view.findViewById(R.id.divider)

            fun bind(item: MenuItem) {
                nameView.text = item.name

                val gbpStr = formatGbp(item.price_gbp)
                val sats = gbpToSats(item.price_gbp)
                priceView.text = if (sats > 0)
                    "$gbpStr  ≈ ${formatSats(sats)} sats"
                else gbpStr

                variationView?.visibility = View.GONE
                quantityView?.visibility = View.GONE

                divider?.visibility =
                    if (adapterPosition == itemCount - 1) View.GONE else View.VISIBLE

                itemView.setOnClickListener { onTap(item) }
            }
        }
    }
}

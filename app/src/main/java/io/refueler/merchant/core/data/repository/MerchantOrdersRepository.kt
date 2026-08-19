package io.refueler.merchant.core.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.refueler.merchant.core.network.SupabaseClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple model for a merchant_orders row as returned by PostgREST.
 *
 * Covers both pre-orders (origin='preorder') and floor orders (origin='floor').
 * settled_sats and routing_fee_sats are null for cash/card_external orders.
 */
data class MerchantOrder(
    @SerializedName("id") val id: String,
    @SerializedName("order_code") val orderCode: String,
    @SerializedName("venue_id") val venueId: String,
    @SerializedName("status") val status: String,
    @SerializedName("payment_method") val paymentMethod: String?,
    @SerializedName("origin") val origin: String,
    @SerializedName("settled_sats") val settledSats: Long?,
    @SerializedName("routing_fee_sats") val routingFeeSats: Long?,
    @SerializedName("amount_gbp") val amountGbp: Double?,
    @SerializedName("created_at") val createdAt: String
) {
    val date: Date by lazy {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(
                createdAt.substringBefore("+").substringBefore("Z")
            ) ?: Date(0)
        }.getOrDefault(Date(0))
    }

    val isLightning: Boolean get() = paymentMethod == null || paymentMethod == "lightning"
    val isCash: Boolean get() = paymentMethod == "cash"
    val isCard: Boolean get() = paymentMethod == "card_external"
    val isFloor: Boolean get() = origin == "floor"
    val isPreorder: Boolean get() = origin == "preorder"
    val isConfirmed: Boolean get() = status == "confirmed"
}

/**
 * Repository for reading merchant_orders from Supabase PostgREST.
 * All calls are blocking — call from Dispatchers.IO.
 */
object MerchantOrdersRepository {

    private val gson = Gson()
    private val listType = object : TypeToken<List<MerchantOrder>>() {}.type

    /**
     * Fetch all confirmed orders for the current venue, newest first.
     * Filters: status=confirmed so pending/failed rows are excluded from history.
     */
    fun fetchConfirmed(context: Context): List<MerchantOrder> {
        val venueId = SupabaseClient.venueId(context)
        val json = SupabaseClient.postgrestGet(
            context = context,
            table = "merchant_orders",
            query = "venue_id=eq.$venueId&status=eq.confirmed&order=created_at.desc&limit=200"
        )
        return gson.fromJson(json, listType)
    }

    /**
     * Fetch all confirmed orders in a date range (ISO strings, inclusive).
     * Used by InsightsActivity for period aggregation.
     */
    fun fetchConfirmedInRange(
        context: Context,
        fromIso: String,
        toIso: String
    ): List<MerchantOrder> {
        val venueId = SupabaseClient.venueId(context)
        val json = SupabaseClient.postgrestGet(
            context = context,
            table = "merchant_orders",
            query = "venue_id=eq.$venueId" +
                    "&status=eq.confirmed" +
                    "&created_at=gte.$fromIso" +
                    "&created_at=lte.$toIso" +
                    "&order=created_at.desc"
        )
        return gson.fromJson(json, listType)
    }

    /**
     * Quick summary stats for the Owner tab / Insights header.
     * Returns total Lightning sats received and total order count for today.
     */
    fun fetchTodaySummary(context: Context): TodaySummary {
        val venueId = SupabaseClient.venueId(context)
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + "T00:00:00"
        val json = SupabaseClient.postgrestGet(
            context = context,
            table = "merchant_orders",
            query = "venue_id=eq.$venueId&status=eq.confirmed&created_at=gte.$todayIso&select=settled_sats,payment_method"
        )
        val orders: List<MerchantOrder> = gson.fromJson(json, listType)
        val totalSats = orders.sumOf { it.settledSats ?: 0L }
        return TodaySummary(
            orderCount = orders.size,
            lightningOrderCount = orders.count { it.isLightning },
            totalSatsReceived = totalSats
        )
    }

    data class TodaySummary(
        val orderCount: Int,
        val lightningOrderCount: Int,
        val totalSatsReceived: Long
    )
}

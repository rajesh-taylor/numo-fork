package io.refueler.merchant.core.data.model

import java.util.Date

/**
 * Union type for all transaction history entries.
 * Both [PaymentHistoryEntry] and [io.refueler.merchant.feature.autowithdraw.WithdrawHistoryEntry]
 * implement this interface so they can be displayed in a single history list without lossy conversion.
 */
interface HistoryEntry {
    val id: String
    val amount: Long            // signed: positive = incoming, negative = outgoing
    val date: Date
    val enteredAmount: Long
    val mintUrl: String?
    val label: String?
    val status: String

    fun getEntryUnit(): String
    fun getBaseAmountSats(): Long
    fun isPending(): Boolean
    fun isCompleted(): Boolean
    fun isExpired(): Boolean = false
    fun isFailed(): Boolean = false
}

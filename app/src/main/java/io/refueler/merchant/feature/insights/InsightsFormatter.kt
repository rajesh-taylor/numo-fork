package io.refueler.merchant.feature.insights

import io.refueler.merchant.core.model.Amount

object InsightsFormatter {

    fun format(unit: DisplayUnit, sats: Long, fiatMinor: Long, fiatCurrency: Amount.Currency): String =
        when (unit) {
            DisplayUnit.FIAT -> Amount(fiatMinor, fiatCurrency).toString()
            DisplayUnit.SATS -> Amount(sats, Amount.Currency.BTC).toString()
        }
}

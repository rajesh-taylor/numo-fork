package io.refueler.merchant.core.payment

import android.content.Context
import io.refueler.merchant.core.cashu.CashuWalletManager
import io.refueler.merchant.core.payment.impl.BTCPayPaymentService
import io.refueler.merchant.core.payment.impl.LocalPaymentService
import io.refueler.merchant.core.prefs.PreferenceStore
import io.refueler.merchant.core.util.MintManager

/**
 * Creates the appropriate [IPaymentService] based on user settings.
 *
 * When `btcpay_enabled` is true **and** the BTCPay configuration is complete
 * the factory returns a [BTCPayPaymentService]; otherwise it falls back to
 * the [LocalPaymentService] that wraps the existing CDK wallet flow.
 */
object PaymentServiceFactory {

    fun create(context: Context): IPaymentService {
        val prefs = PreferenceStore.app(context)

        if (prefs.getBoolean("btcpay_enabled", false)) {
            val config = BTCPayConfig(
                serverUrl = prefs.getString("btcpay_server_url") ?: "",
                apiKey = prefs.getString("btcpay_api_key") ?: "",
                storeId = prefs.getString("btcpay_store_id") ?: "",
                posAppId = prefs.getString("btcpay_pos_app_id")?.takeIf { it.isNotBlank() },
            )
            if (config.serverUrl.isNotBlank()
                && config.apiKey.isNotBlank()
                && config.storeId.isNotBlank()
            ) {
                return BTCPayPaymentService(config)
            }
        }

        return LocalPaymentService(
            walletProvider = CashuWalletManager.getWalletProvider(),
            mintManager = MintManager.getInstance(context)
        )
    }
}

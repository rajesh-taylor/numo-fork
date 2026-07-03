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
 *
 * Storage split:
 *  - btcpay_enabled (non-sensitive toggle) → PreferenceStore.app()
 *  - btcpay_api_key, btcpay_server_url, btcpay_store_id, btcpay_pos_app_id
 *    (credentials) → PreferenceStore.secure()
 */
object PaymentServiceFactory {

    fun create(context: Context): IPaymentService {
        val appPrefs = PreferenceStore.app(context)

        if (appPrefs.getBoolean("btcpay_enabled", false)) {
            val securePrefs = PreferenceStore.secure(context)
            val config = BTCPayConfig(
                serverUrl = securePrefs.getString("btcpay_server_url") ?: "",
                apiKey = securePrefs.getString("btcpay_api_key") ?: "",
                storeId = securePrefs.getString("btcpay_store_id") ?: "",
                posAppId = securePrefs.getString("btcpay_pos_app_id")?.takeIf { it.isNotBlank() },
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

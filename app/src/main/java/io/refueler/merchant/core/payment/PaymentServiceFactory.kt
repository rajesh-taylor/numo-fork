package io.refueler.merchant.core.payment

import android.content.Context
import io.refueler.merchant.core.cashu.CashuWalletManager
import io.refueler.merchant.core.payment.impl.BTCPayPaymentService
import io.refueler.merchant.core.payment.impl.LocalPaymentService
import io.refueler.merchant.core.prefs.PreferenceStore
import io.refueler.merchant.core.util.MintManager

/**
 * Creates the appropriate [IPaymentService] based on user settings.
 * BTCPay credentials are read from PreferenceStore.secure() (EncryptedSharedPreferences).
 */
object PaymentServiceFactory {

    fun create(context: Context): IPaymentService {
        val appPrefs    = PreferenceStore.app(context)
        val securePrefs = PreferenceStore.secure(context)

        if (appPrefs.getBoolean("btcpay_enabled", false)) {
            val config = BTCPayConfig(
                serverUrl = securePrefs.getString("btcpay_server_url") ?: "",
                apiKey    = securePrefs.getString("btcpay_api_key") ?: "",
                storeId   = securePrefs.getString("btcpay_store_id") ?: "",
                posAppId  = securePrefs.getString("btcpay_pos_app_id")?.takeIf { it.isNotBlank() },
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
            mintManager    = MintManager.getInstance(context),
        )
    }
}
